"""社区发帖的最小持久化层。

只有 sqlite3 标准库，库路径由调用方注入（测试传 ``:memory:`` 或 tmp_path）。
刻意不在本模块内读系统时间：``created_ms`` 一律由调用方传入，这样同一份
输入永远得到同一份输出，回放和断言都站得住。

图片同样只用标准库：按文件头魔数判定格式，不引 Pillow 这类依赖。
"""

from __future__ import annotations

import json
import re
import secrets
import shutil
import sqlite3
import tempfile
from pathlib import Path
from typing import Any

TITLE_MIN = 1
TITLE_MAX = 80
BODY_MIN = 1
BODY_MAX = 2000
AUTHOR_MIN = 1
AUTHOR_MAX = 40

IMAGE_MAX_BYTES = 5 * 1024 * 1024
IMAGE_MIN_BYTES = 8
IMAGES_PER_POST_MAX = 6

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

# 中文标签，位置与 SECTIONS 一一对应；与 CommunitySection 的 label 保持一致。
SECTION_LABELS: tuple[str, ...] = (
    "活动",
    "优质影响力公众号推荐",
    "最热度",
    "探索发现",
    "问题求助",
    "分享交流",
    "合作伙伴",
)

if len(SECTION_LABELS) != len(SECTIONS):  # pragma: no cover - 常量错位时的自检
    raise AssertionError("SECTION_LABELS 与 SECTIONS 长度不一致，板块定义已经跑偏")

_IMAGE_ID = re.compile(r"\A[0-9a-f]{16}\Z")

_SCHEMA = """
CREATE TABLE IF NOT EXISTS posts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    section TEXT NOT NULL,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    author TEXT NOT NULL,
    created_ms INTEGER NOT NULL,
    image_ids TEXT NOT NULL DEFAULT '[]'
);
CREATE TABLE IF NOT EXISTS images (
    id TEXT PRIMARY KEY,
    content_type TEXT NOT NULL,
    ext TEXT NOT NULL,
    byte_size INTEGER NOT NULL,
    created_ms INTEGER NOT NULL
);
"""


def sniff_image(data: bytes) -> tuple[str, str] | None:
    """按文件头判定图片类型。认不出来就返回 None——不靠扩展名，也不靠调用方声明。"""
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return ".png", "image/png"
    if data.startswith(b"\xff\xd8\xff"):
        return ".jpg", "image/jpeg"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return ".webp", "image/webp"
    return None


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
    """社区帖子与图片的 sqlite 存储。库路径由构造参数注入。"""

    def __init__(self, path: str) -> None:
        self.path = path
        self.connection = sqlite3.connect(path, check_same_thread=False)
        self.connection.row_factory = sqlite3.Row
        self.connection.executescript(_SCHEMA)
        self._migrate_posts_image_ids()
        self.connection.commit()

        # 图片与库放在一起，方便整体备份与删除。:memory: 没有目录，落临时目录。
        self._temp_dir: str | None = None
        if path == ":memory:":
            self._temp_dir = tempfile.mkdtemp(prefix="sensefield-community-")
            self.image_dir = Path(self._temp_dir) / "images"
        else:
            self.image_dir = Path(path).parent / "images"
        self.image_dir.mkdir(parents=True, exist_ok=True)

    def _migrate_posts_image_ids(self) -> None:
        """老库没有 image_ids 列，补上。已有帖子一律视为不带图。"""
        columns = {row["name"] for row in self.connection.execute("PRAGMA table_info(posts)")}
        if "image_ids" not in columns:
            self.connection.execute(
                "ALTER TABLE posts ADD COLUMN image_ids TEXT NOT NULL DEFAULT '[]'"
            )

    def close(self) -> None:
        self.connection.close()
        if self._temp_dir is not None:
            shutil.rmtree(self._temp_dir, ignore_errors=True)
            self._temp_dir = None

    # ---------- 图片 ----------

    def save_image(self, data: bytes, created_ms: int) -> dict[str, Any]:
        """存一张图片并返回它的信息。类型认不出或超限都抛 ValueError。"""
        if not isinstance(data, (bytes, bytearray)):
            raise ValueError("图片内容必须是字节")
        if len(data) < IMAGE_MIN_BYTES:
            raise ValueError("图片内容为空")
        if len(data) > IMAGE_MAX_BYTES:
            raise ValueError(f"图片超过 {IMAGE_MAX_BYTES // (1024 * 1024)} MB")
        sniffed = sniff_image(bytes(data))
        if sniffed is None:
            raise ValueError("只认 PNG、JPEG、WebP 三种图片格式")
        ext, content_type = sniffed
        if isinstance(created_ms, bool) or not isinstance(created_ms, int):
            raise ValueError("created_ms 必须是整数毫秒")

        image_id = secrets.token_hex(8)
        (self.image_dir / (image_id + ext)).write_bytes(bytes(data))
        self.connection.execute(
            "INSERT INTO images (id, content_type, ext, byte_size, created_ms)"
            " VALUES (?, ?, ?, ?, ?)",
            (image_id, content_type, ext, len(data), created_ms),
        )
        self.connection.commit()
        return {
            "id": image_id,
            "content_type": content_type,
            "byte_size": len(data),
            "created_ms": created_ms,
        }

    def image_record(self, image_id: str) -> dict[str, Any] | None:
        if not isinstance(image_id, str) or not _IMAGE_ID.match(image_id):
            return None
        row = self.connection.execute(
            "SELECT id, content_type, ext, byte_size, created_ms FROM images WHERE id = ?",
            (image_id,),
        ).fetchone()
        return dict(row) if row is not None else None

    def image_bytes(self, image_id: str) -> tuple[bytes, str] | None:
        """取图片原始字节与类型。认不出的 id 一律当不存在，不去拼路径。"""
        record = self.image_record(image_id)
        if record is None:
            return None
        path = self.image_dir / (record["id"] + record["ext"])
        if not path.is_file():
            return None
        return path.read_bytes(), record["content_type"]

    # ---------- 帖子 ----------

    def create_post(
        self,
        section: str,
        title: str,
        body: str,
        author: str,
        created_ms: int,
        image_ids: list[str] | None = None,
    ) -> dict[str, Any]:
        """写入一条帖子并返回它。任一字段越界抛 ValueError。"""
        key = validate_section(section)
        clean_title = _require_text(title, "标题", TITLE_MIN, TITLE_MAX)
        clean_author = _require_text(author, "作者", AUTHOR_MIN, AUTHOR_MAX)
        # 正文按用户原文保存（换行是有意义的排版），只用去空白后的长度做字数校验。
        _require_text(body, "正文", BODY_MIN, BODY_MAX)
        if isinstance(created_ms, bool) or not isinstance(created_ms, int):
            raise ValueError("created_ms 必须是整数毫秒")
        clean_images = self._validate_images(image_ids)

        cursor = self.connection.execute(
            "INSERT INTO posts (section, title, body, author, created_ms, image_ids)"
            " VALUES (?, ?, ?, ?, ?, ?)",
            (key, clean_title, body, clean_author, created_ms, json.dumps(clean_images)),
        )
        self.connection.commit()
        return {
            "id": cursor.lastrowid,
            "section": key,
            "title": clean_title,
            "body": body,
            "author": clean_author,
            "created_ms": created_ms,
            "image_ids": clean_images,
        }

    def _validate_images(self, image_ids: list[str] | None) -> list[str]:
        if image_ids is None:
            return []
        if not isinstance(image_ids, list):
            raise ValueError("image_ids 必须是列表")
        if len(image_ids) > IMAGES_PER_POST_MAX:
            raise ValueError(f"一条帖子最多 {IMAGES_PER_POST_MAX} 张图")
        clean: list[str] = []
        for image_id in image_ids:
            if not isinstance(image_id, str) or not _IMAGE_ID.match(image_id):
                raise ValueError(f"图片标识不合法：{image_id!r}")
            if self.image_record(image_id) is None:
                raise ValueError(f"图片不存在：{image_id}")
            if image_id not in clean:
                clean.append(image_id)
        return clean

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

        query = (
            "SELECT id, section, title, body, author, created_ms, image_ids FROM posts"
        )
        params: tuple[Any, ...] = ()
        if section is not None:
            query += " WHERE section = ?"
            params = (section,)
        query += " ORDER BY created_ms DESC, id DESC LIMIT ?"
        rows = self.connection.execute(query, params + (limit,)).fetchall()

        posts: list[dict[str, Any]] = []
        for row in rows:
            post = dict(row)
            try:
                post["image_ids"] = json.loads(post.get("image_ids") or "[]")
            except (TypeError, ValueError):  # pragma: no cover - 库里被手改过
                post["image_ids"] = []
            posts.append(post)
        return posts