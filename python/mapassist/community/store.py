"""社区发帖的最小持久化层。

只有 sqlite3 标准库，库路径由调用方注入（测试传 ``:memory:`` 或 tmp_path）。
刻意不在本模块内读系统时间：``created_ms`` 一律由调用方传入，这样同一份
输入永远得到同一份输出，回放和断言都站得住。
"""

from __future__ import annotations

import sqlite3
from typing import Any

TITLE_MIN = 1
TITLE_MAX = 80
BODY_MIN = 1
BODY_MAX = 2000
AUTHOR_MIN = 1
AUTHOR_MAX = 40

# 稳定 key，与端侧 CommunitySection 枚举一一对应；顺序即展示顺序。
SECTIONS: tuple[str, ...] = (
    "activity",
    "qualified_accounts",
    "hottest",
    "discovery",
    "help",
    "sharing",
    "partners",
)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS posts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    section TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    author TEXT NOT NULL,
    created_ms INTEGER NOT NULL
)
"""


def _require_text(value: Any, field: str, low: int, high: int) -> str:
    """校验字数并返回去掉首尾空白后的值。越界抛 ValueError，绝不静默截断。"""
    if not isinstance(value, str):
        raise ValueError(f"{field} 必须是字符串")
    trimmed = value.strip()
    if len(trimmed) < low:
        raise ValueError(f"{field} 不能为空")
    if len(trimmed) > high:
        raise ValueError(f"{field} 最多 {high} 字，当前 {len(trimmed)} 字")
    return trimmed


def validate_section(section: Any) -> str:
    """板块 key 必须是七板块之一；未知 key 抛 ValueError，不默认落到第一个。"""
    if not isinstance(section, str) or section not in SECTIONS:
        raise ValueError(f"未知板块：{section!r}")
    return section


class CommunityStore:
    """社区帖子的 sqlite 存储。库路径由构造参数注入。"""

    def __init__(self, path: str) -> None:
        self.path = path
        self.connection = sqlite3.connect(path, check_same_thread=False)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute(_SCHEMA)
        self.connection.commit()

    def close(self) -> None:
        self.connection.close()

    def create_post(
        self,
        section: str,
        title: str,
        body: str,
        author: str,
        created_ms: int,
    ) -> dict[str, Any]:
        """写入一条帖子并返回它。任一字段越界抛 ValueError。"""
        key = validate_section(section)
        clean_title = _require_text(title, "标题", TITLE_MIN, TITLE_MAX)
        clean_author = _require_text(author, "作者", AUTHOR_MIN, AUTHOR_MAX)
        # 正文按用户原文保存（换行是有意义的排版），只用去空白后的长度做字数校验。
        _require_text(body, "正文", BODY_MIN, BODY_MAX)
        if isinstance(created_ms, bool) or not isinstance(created_ms, int):
            raise ValueError("created_ms 必须是整数毫秒")

        cursor = self.connection.execute(
            "INSERT INTO posts (section, title, body, author, created_ms)"
            " VALUES (?, ?, ?, ?, ?)",
            (key, clean_title, body, clean_author, created_ms),
        )
        self.connection.commit()
        return {
            "id": cursor.lastrowid,
            "section": key,
            "title": clean_title,
            "body": body,
            "author": clean_author,
            "created_ms": created_ms,
        }

    def list_posts(
        self,
        section: str | None = None,
        limit: int = 50,
    ) -> list[dict[str, Any]]:
        """按 created_ms 倒序取帖子；section 为 None 时不过滤。"""
        if isinstance(limit, bool) or not isinstance(limit, int) or limit < 1:
            raise ValueError("limit 必须是不小于 1 的整数")
        if section is not None:
            validate_section(section)

        if section is None:
            rows = self.connection.execute(
                "SELECT id, section, title, body, author, created_ms FROM posts"
                " ORDER BY created_ms DESC, id DESC LIMIT ?",
                (limit,),
            ).fetchall()
        else:
            rows = self.connection.execute(
                "SELECT id, section, title, body, author, created_ms FROM posts"
                " WHERE section = ? ORDER BY created_ms DESC, id DESC LIMIT ?",
                (section, limit),
            ).fetchall()
        return [dict(row) for row in rows]
