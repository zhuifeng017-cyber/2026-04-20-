#!/usr/bin/env python3
"""
Cookie 更新工具
当 Cookie 过期后，将新的 Cookie 字符串粘贴到这里，
脚本会自动更新 config.py 中的 COOKIE_STRING。

用法: python cookie_updater.py
"""

import re

NEW_COOKIE = """
在这里粘贴新的 Cookie 字符串（从抓包工具复制）
""".strip()


def update_config(new_cookie: str):
    with open("config.py", "r", encoding="utf-8") as f:
        content = f.read()

    # 替换 COOKIE_STRING 块（从 ( 到下一个 ) 行）
    pattern = r'(COOKIE_STRING\s*=\s*\().*?(\))'
    lines = new_cookie.replace('"', '\\"').split("; ")
    joined = ';\n    "'.join(lines)
    replacement = f'COOKIE_STRING = (\n    "{joined}"\n)'

    new_content = re.sub(pattern, replacement, content, flags=re.DOTALL)
    with open("config.py", "w", encoding="utf-8") as f:
        f.write(new_content)

    print("config.py 中的 COOKIE_STRING 已更新")


if __name__ == "__main__":
    if "在这里粘贴" in NEW_COOKIE or not NEW_COOKIE:
        new_cookie = input("请粘贴新的 Cookie 字符串，回车结束：\n").strip()
    else:
        new_cookie = NEW_COOKIE

    if new_cookie:
        update_config(new_cookie)
    else:
        print("Cookie 为空，未更新")
