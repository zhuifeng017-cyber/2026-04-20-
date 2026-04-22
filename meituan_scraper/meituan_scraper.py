#!/usr/bin/env python3
"""
美团店铺商品爬虫
用法: python meituan_scraper.py "店铺名称" [--limit 100]
"""

import argparse
import csv
import json
import logging
import os
import random
import sys
import time
from datetime import datetime
from typing import Optional

import requests
from requests.adapters import HTTPAdapter
from urllib3.util.retry import Retry

import config

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.StreamHandler(sys.stdout),
        logging.FileHandler("scraper.log", encoding="utf-8"),
    ],
)
log = logging.getLogger(__name__)


# ── 请求头模板（模拟美团 iOS App）────────────────────────────────────────────

BASE_HEADERS = {
    "Accept": "*/*",
    "Accept-Language": "zh-CN,zh-Hans;q=0.9",
    "Accept-Encoding": "gzip, deflate, br",
    "Connection": "keep-alive",
    # UA 解码自抓包：美团/321059 CFNetwork/3860.500.112 Darwin/25.4.0
    "User-Agent": "美团/321059 CFNetwork/3860.500.112 Darwin/25.4.0",
}

# 部分接口需要额外标识是移动端来源
MOBILE_EXTRA = {
    "platform": "iphone",
    "version": "12.56.202",
    "appid": "1",
}


# ── API 端点 ─────────────────────────────────────────────────────────────────

class API:
    # 外卖店铺搜索（返回 wmPoiId）
    WAIMAI_SEARCH       = "https://apimobile.meituan.com/group/v4/poi/search"
    # 外卖店铺商品列表（按分类）
    WAIMAI_PRODUCT_LIST = "https://apimobile.meituan.com/v1/poi/{poi_id}/product/list"
    # 外卖店铺全量商品（支持翻页）
    WAIMAI_FOOD_LIST    = "https://apimobile.meituan.com/v1/poi/{poi_id}/food/list"
    # 店铺详情（包含 poiId 和基础信息）
    POI_DETAIL          = "https://apimobile.meituan.com/v1/poi/{poi_id}/detail"
    # 备用：waimai web 搜索
    WEB_SEARCH          = "https://waimai.meituan.com/api/v1/poi/search"
    # 备用：到店（美食）搜索
    DIANPING_SEARCH     = "https://apimobile.meituan.com/group/v2/home/search"


# ── 核心爬虫类 ────────────────────────────────────────────────────────────────

class MeituanScraper:

    def __init__(self, limit: int = 100):
        self.limit = limit
        self.city_id = config.CITY_ID
        self.session = self._build_session(config.COOKIE_STRING)

    # ── Session 构建 ──────────────────────────────────────────────────────────

    def _build_session(self, cookie_string: str) -> requests.Session:
        session = requests.Session()

        # 连接级别的重试（仅针对网络层，非 HTTP 状态码）
        retry = Retry(
            total=config.MAX_RETRIES,
            backoff_factor=0.5,
            status_forcelist=[500, 502, 503, 504],
            allowed_methods=["GET", "POST"],
        )
        adapter = HTTPAdapter(max_retries=retry)
        session.mount("https://", adapter)
        session.mount("http://", adapter)

        session.headers.update(BASE_HEADERS)

        # 解析 cookie 字符串
        for chunk in cookie_string.split(";"):
            chunk = chunk.strip()
            if "=" in chunk:
                key, _, val = chunk.partition("=")
                session.cookies.set(key.strip(), val.strip(), domain=".meituan.com")

        return session

    # ── 通用请求封装 ──────────────────────────────────────────────────────────

    def _get(
        self,
        url: str,
        params: Optional[dict] = None,
        extra_headers: Optional[dict] = None,
        delay_range: tuple = None,
    ) -> Optional[dict]:
        if delay_range is None:
            delay_range = (config.DELAY_MIN, config.DELAY_MAX)

        time.sleep(random.uniform(*delay_range))

        headers = {}
        if extra_headers:
            headers.update(extra_headers)

        for attempt in range(1, config.MAX_RETRIES + 1):
            try:
                resp = self.session.get(
                    url, params=params, headers=headers, timeout=15
                )
                log.debug("GET %s %s → %d", url, params, resp.status_code)

                if resp.status_code == 200:
                    return resp.json()

                if resp.status_code in (401, 403):
                    log.warning("鉴权失败 %d，Cookie 可能已过期，请重新抓包更新", resp.status_code)
                    return None

                if resp.status_code == 429:
                    wait = config.RETRY_BACKOFF ** attempt * 5
                    log.warning("触发限流 (429)，等待 %.1f 秒后重试", wait)
                    time.sleep(wait)
                    continue

                log.warning("HTTP %d，尝试 %d/%d", resp.status_code, attempt, config.MAX_RETRIES)

            except requests.exceptions.ConnectionError as e:
                log.warning("连接错误 (尝试 %d): %s", attempt, e)
            except requests.exceptions.Timeout:
                log.warning("请求超时 (尝试 %d)", attempt)
            except Exception as e:
                log.error("未知错误 (尝试 %d): %s", attempt, e)

            time.sleep(config.RETRY_BACKOFF ** attempt)

        return None

    # ── 店铺搜索 ──────────────────────────────────────────────────────────────

    def search_store(self, store_name: str) -> Optional[dict]:
        """
        搜索店铺名称，返回第一个匹配结果的基本信息。
        依次尝试外卖搜索 → 备用 Web 搜索 → 到店搜索。
        """
        log.info("正在搜索店铺：%s", store_name)

        # ① 外卖搜索（最常用）
        result = self._search_waimai(store_name)
        if result:
            return result

        # ② 备用：Web 外卖搜索
        result = self._search_waimai_web(store_name)
        if result:
            return result

        # ③ 备用：到店（美食/超市）搜索
        result = self._search_dianping(store_name)
        if result:
            return result

        log.error("未找到店铺：%s。请确认店铺名称或检查 Cookie 是否有效", store_name)
        return None

    def _search_waimai(self, store_name: str) -> Optional[dict]:
        params = {
            "q": store_name,
            "cityid": self.city_id,
            "platform": "iphone",
            "version": "12.56.202",
            "appid": "1",
            "userid": self.session.cookies.get("userId", ""),
            "uuid": self.session.cookies.get("uuid", ""),
            "limit": "5",
            "offset": "0",
        }
        data = self._get(API.WAIMAI_SEARCH, params=params)
        return self._extract_poi_from_search(data, store_name, "waimai")

    def _search_waimai_web(self, store_name: str) -> Optional[dict]:
        params = {
            "keyword": store_name,
            "cityId": self.city_id,
            "platform": "2",  # 2 = web
        }
        headers = {
            "Referer": "https://waimai.meituan.com/",
            "Origin": "https://waimai.meituan.com",
        }
        data = self._get(API.WEB_SEARCH, params=params, extra_headers=headers)
        return self._extract_poi_from_search(data, store_name, "waimai_web")

    def _search_dianping(self, store_name: str) -> Optional[dict]:
        params = {
            "q": store_name,
            "cityid": self.city_id,
            "platform": "iphone",
            "version": "12.56.202",
        }
        data = self._get(API.DIANPING_SEARCH, params=params)
        return self._extract_poi_from_search(data, store_name, "dianping")

    def _extract_poi_from_search(
        self, data: Optional[dict], store_name: str, source: str
    ) -> Optional[dict]:
        """从各搜索接口的响应中提取第一个匹配的 POI 信息"""
        if not data:
            return None

        # 尝试多种常见响应结构
        candidates = []
        for path in [
            ["data", "searchResult", "poiInfos"],
            ["data", "poiList"],
            ["data", "result"],
            ["data"],
        ]:
            node = data
            try:
                for key in path:
                    node = node[key]
                if isinstance(node, list):
                    candidates = node
                    break
            except (KeyError, TypeError):
                continue

        for item in candidates:
            name = item.get("name", "") or item.get("title", "")
            if store_name in name:
                poi_id = (
                    item.get("wmPoiId")
                    or item.get("poiId")
                    or item.get("id")
                    or item.get("poi_id")
                )
                if poi_id:
                    log.info(
                        "[%s] 找到店铺：%s (POI ID: %s)", source, name, poi_id
                    )
                    return {
                        "poi_id": str(poi_id),
                        "name": name,
                        "address": item.get("address", ""),
                        "avg_price": item.get("avgPrice", item.get("mean", "")),
                        "score": item.get("score", item.get("wm_poi_score", "")),
                        "month_sale_num": item.get("monthSaleNum", ""),
                        "shipping_fee": item.get("shippingFee", ""),
                        "min_price": item.get("minPrice", ""),
                        "delivery_time": item.get("deliveryTime", ""),
                        "latitude": item.get("latitude", ""),
                        "longitude": item.get("longitude", ""),
                        "source": source,
                    }

        log.debug("[%s] 响应中未匹配到目标店铺，原始 keys: %s", source, list(data.keys()) if data else [])
        return None

    # ── 商品列表抓取 ──────────────────────────────────────────────────────────

    def get_products(self, poi_info: dict) -> list[dict]:
        """
        抓取店铺下所有商品，优先尝试全量列表接口，
        失败则按分类逐页抓取。
        """
        poi_id = poi_info["poi_id"]
        log.info("开始抓取店铺商品，POI ID: %s，上限: %d 条", poi_id, self.limit)

        # ① 尝试全量商品列表（分页）
        products = self._fetch_food_list(poi_id)

        # ② 备用：按分类抓取
        if not products:
            products = self._fetch_by_category(poi_id)

        if not products:
            log.error("无法获取商品数据，请检查 POI ID 和 Cookie 是否有效")
            return []

        log.info("共抓取到 %d 件商品（限制 %d 件）", min(len(products), self.limit), self.limit)
        return products[: self.limit]

    def _fetch_food_list(self, poi_id: str) -> list[dict]:
        """全量翻页抓取"""
        products = []
        page = 0
        page_size = 20

        while len(products) < self.limit:
            url = API.WAIMAI_FOOD_LIST.format(poi_id=poi_id)
            params = {
                "wmPoiId": poi_id,
                "offset": page * page_size,
                "limit": page_size,
                "platform": "iphone",
                "version": "12.56.202",
            }
            data = self._get(
                url,
                params=params,
                delay_range=(config.PAGE_DELAY_MIN, config.PAGE_DELAY_MAX),
            )
            if not data:
                break

            items = self._parse_food_list(data)
            if not items:
                break

            products.extend(items)
            log.info("  已抓取 %d 件商品（第 %d 页）", len(products), page + 1)

            # 判断是否还有下一页
            total = (
                data.get("data", {}).get("total")
                or data.get("total")
                or 0
            )
            if len(products) >= total or len(items) < page_size:
                break

            page += 1

        return products

    def _parse_food_list(self, data: dict) -> list[dict]:
        """解析全量列表响应"""
        items_raw = []
        for path in [
            ["data", "foodList"],
            ["data", "foods"],
            ["data", "list"],
            ["foodList"],
        ]:
            node = data
            try:
                for key in path:
                    node = node[key]
                if isinstance(node, list):
                    items_raw = node
                    break
            except (KeyError, TypeError):
                continue

        return [self._normalize_product(item) for item in items_raw]

    def _fetch_by_category(self, poi_id: str) -> list[dict]:
        """先获取分类列表，再按分类抓取商品"""
        url = API.WAIMAI_PRODUCT_LIST.format(poi_id=poi_id)
        params = {
            "wmPoiId": poi_id,
            "platform": "iphone",
            "version": "12.56.202",
        }
        data = self._get(url, params=params)
        if not data:
            return []

        # 解析分类
        categories = []
        for path in [
            ["data", "foodSpu", "categories"],
            ["data", "categories"],
            ["categories"],
        ]:
            node = data
            try:
                for key in path:
                    node = node[key]
                if isinstance(node, list):
                    categories = node
                    break
            except (KeyError, TypeError):
                continue

        if not categories:
            # 若无分类结构，尝试直接从响应中提取所有商品
            return self._parse_food_list(data)

        products = []
        for cat in categories:
            if len(products) >= self.limit:
                break
            cat_name = cat.get("name", "未知分类")
            foods = cat.get("foods", cat.get("spus", []))
            log.info("  分类「%s」：%d 件商品", cat_name, len(foods))
            for food in foods:
                if len(products) >= self.limit:
                    break
                product = self._normalize_product(food)
                product["category"] = cat_name
                products.append(product)
            time.sleep(random.uniform(0.3, 0.8))

        return products

    def _normalize_product(self, raw: dict) -> dict:
        """将各接口返回的字段统一为标准格式"""
        # 价格（单位：分 → 元）
        price_fen = raw.get("price") or raw.get("minPrice") or 0
        origin_fen = raw.get("originPrice") or raw.get("originalPrice") or 0

        def fen_to_yuan(v):
            try:
                v = int(v)
                return round(v / 100, 2) if v > 100 else round(float(v), 2)
            except (TypeError, ValueError):
                return v

        # 规格/口味
        skus = raw.get("skuList", raw.get("skus", []))
        sku_summary = "; ".join(
            f"{s.get('name','')}: {fen_to_yuan(s.get('price',0))}元"
            for s in (skus or [])
            if s.get("name")
        )

        return {
            "商品ID":     raw.get("spuId") or raw.get("foodId") or raw.get("id", ""),
            "商品名称":   raw.get("name") or raw.get("title", ""),
            "分类":       raw.get("categoryName") or raw.get("category", ""),
            "价格(元)":   fen_to_yuan(price_fen),
            "原价(元)":   fen_to_yuan(origin_fen) if origin_fen else "",
            "月销量":     raw.get("monthSaleNum") or raw.get("soldCount", ""),
            "描述":       (raw.get("description") or raw.get("desc", "")).replace("\n", " "),
            "图片URL":    raw.get("picture") or raw.get("imgUrl") or raw.get("pictureUrl", ""),
            "规格/口味":  sku_summary,
            "库存状态":   "有货" if raw.get("stock", 1) else "售罄",
            "是否推荐":   "是" if raw.get("isRecommend") or raw.get("recommend") else "否",
            "标签":       ", ".join(raw.get("labelList", []) or []),
        }

    # ── 数据保存 ──────────────────────────────────────────────────────────────

    def save(self, store_name: str, poi_info: dict, products: list[dict]) -> str:
        os.makedirs(config.OUTPUT_DIR, exist_ok=True)
        safe_name = store_name.replace("/", "_").replace("\\", "_")
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        base = os.path.join(config.OUTPUT_DIR, f"{safe_name}_{ts}")

        # JSON（包含店铺元信息 + 商品列表）
        json_path = base + ".json"
        with open(json_path, "w", encoding="utf-8") as f:
            json.dump(
                {"store_info": poi_info, "products": products},
                f,
                ensure_ascii=False,
                indent=2,
            )

        # CSV（仅商品列表）
        csv_path = base + ".csv"
        if products:
            with open(csv_path, "w", newline="", encoding="utf-8-sig") as f:
                writer = csv.DictWriter(f, fieldnames=list(products[0].keys()))
                writer.writeheader()
                writer.writerows(products)

        log.info("已保存 JSON: %s", json_path)
        log.info("已保存 CSV:  %s", csv_path)
        return base

    # ── 入口 ─────────────────────────────────────────────────────────────────

    def run(self, store_name: str) -> int:
        poi_info = self.search_store(store_name)
        if not poi_info:
            return 1

        products = self.get_products(poi_info)
        if not products:
            return 1

        self.save(store_name, poi_info, products)
        log.info("完成！共采集 %d 件商品", len(products))
        return 0


# ── CLI ────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description="美团店铺商品爬虫")
    parser.add_argument("store_name", nargs="?", default="", help="店铺名称")
    parser.add_argument("--limit", type=int, default=None, help="最大采集商品数（默认 config.py 中的 LIMIT）")
    args = parser.parse_args()

    store_name = args.store_name or config.STORE_NAME
    if not store_name:
        store_name = input("请输入店铺名称：").strip()
    if not store_name:
        print("错误：店铺名称不能为空")
        sys.exit(1)

    limit = args.limit if args.limit is not None else config.LIMIT

    scraper = MeituanScraper(limit=limit)
    sys.exit(scraper.run(store_name))


if __name__ == "__main__":
    main()
