"""
Meituan Scraper Configuration
- STORE_NAME: set via CLI or edit here
- LIMIT: max number of products to fetch (default 100)
"""

# ==================== 用户配置区 ====================

STORE_NAME = ""  # 可在命令行传入，或直接改这里

LIMIT = 100  # 最大采集商品数，可随时修改

CITY_ID = "217"  # 从 Cookie 中提取，按需修改

# Cookie 字符串（直接从抓包复制粘贴）
COOKIE_STRING = (
    "_utm_content=0000000000000B2BEB288627D46AD8C568354D6721A17A173000657180281238; "
    "mt_c_token=AgH6HeVQ5qgnDW_RzOF_W4Seq2pj6QyR3hSUozlccU8361-aoQPxCBYHeaQwGoh-FrNwFZfbNSWpvAAAAAAENAAAd4SfgtgBbHJ1-J1nsA3-J0kkpITTCKLh0-FNTQJgBKh-5RG0S4krIRU5YWKzWt8F; "
    "token=AgH6HeVQ5qgnDW_RzOF_W4Seq2pj6QyR3hSUozlccU8361-aoQPxCBYHeaQwGoh-FrNwFZfbNSWpvAAAAAAENAAAd4SfgtgBbHJ1-J1nsA3-J0kkpITTCKLh0-FNTQJgBKh-5RG0S4krIRU5YWKzWt8F; "
    "uuid=0000000000000B2BEB288627D46AD8C568354D6721A17A173000657180281238; "
    "WEBDFPID=67w8037vvy66506y11926z8wvzy963z180w09zz884v8795822x0z53z-1776830069107-1776243102823WOKEIKI868c0ee73ab28e1d0b03bc83148500063389; "
    "_lx_utm=utm_campaign%3DAgroupBgroupG%26utm_source%3DAppStore%26utm_term%3D12.56.202%26utm_content%3D0000000000000B2BEB288627D46AD8C568354D6721A17A173000657180281238%26utm_medium%3Diphone; "
    "_utm_campaign=AgroupBgroupD200GmineH0; "
    "_utm_medium=iphone; "
    "_utm_source=AppStore; "
    "_utm_term=12.56.202; "
    "cityid=217; "
    "dpid=; "
    "network=wifi; "
    "lt=AgH6HeVQ5qgnDW_RzOF_W4Seq2pj6QyR3hSUozlccU8361-aoQPxCBYHeaQwGoh-FrNwFZfbNSWpvAAAAAAENAAAd4SfgtgBbHJ1-J1nsA3-J0kkpITTCKLh0-FNTQJgBKh-5RG0S4krIRU5YWKzWt8F; "
    "n=AGS665833148; "
    "wink_strategy_key=%7B%2231824%22%3A%22k%22%2C%2233442%22%3A%22strategy_a%22%2C%2240403%22%3A%22strategy_a%22%2C%2246502%22%3A%22strategy_a%22%2C%2250999%22%3A%22defaultStrategyKey%22%2C%2238088%22%3A%225%22%2C%2236857%22%3A%22g%22%2C%2238443%22%3A%22a%22%2C%2225934%22%3A%22a%22%7D; "
    "utm_source_rg=AM%257coawam%25444%25EBxK2wBVVaEEj2EaDDs8E4KxV4asEw4DK2x2s44KKHVKBsjK88o24jw4; "
    "isUuidUnion=true; "
    "iuuid=0000000000000B2BEB288627D46AD8C568354D6721A17A173000657180281238; "
    "finzero_user_id=1144411824; "
    "userId=1144411824; "
    "_lxsdk=0000000000000B2BEB288627D46AD8C568354D6721A17A173000657180281238; "
    "_lxsdk_cuid=19d9056d599c8-009ad2a443b476-46686606-61d78-19d9056d599c8; "
    "_lxsdk_dpid=b2beb288627d46ad8c568354d6721a17a173000657180281238; "
    "_lxsdk_unoinid=4a0cf1c42b75438393f72ffc6f375bb9a173000657246529406"
)

# ==================== 请求频率控制 ====================

# 每次请求之间的随机等待区间（秒），避免被封
DELAY_MIN = 1.5
DELAY_MAX = 3.5

# 翻页请求比普通请求更快（模拟连续滑动行为）
PAGE_DELAY_MIN = 0.8
PAGE_DELAY_MAX = 1.8

# 最大重试次数
MAX_RETRIES = 3

# 重试间隔基数（指数退避）
RETRY_BACKOFF = 2.0

# ==================== 输出配置 ====================

OUTPUT_DIR = "output"
