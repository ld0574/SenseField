from __future__ import annotations

import pytest

from mapassist.community.store import (
    AUTHOR_MAX,
    BODY_MAX,
    IMAGE_MAX_BYTES,
    SECTIONS,
    TITLE_MAX,
    CommunityStore,
    sniff_image,
)


@pytest.fixture()
def store(tmp_path):
    """库路径必须能注入：这里用 tmp_path，绝不用写死的路径或 :memory:。"""
    opened = CommunityStore(str(tmp_path / "community.db"))
    try:
        yield opened
    finally:
        opened.close()


def test_create_post_returns_every_field(store):
    created = store.create_post("help", " 如何校准小地图 ", "正文一行", " 小明 ", 1700000000000)
    assert created["id"] == 1
    assert created["section"] == "help"
    assert created["title"] == "如何校准小地图"  # 标题去首尾空白
    assert created["body"] == "正文一行"  # 正文按原文保存
    assert created["author"] == "小明"
    assert created["created_ms"] == 1700000000000  # 用传入值，不是系统时间


def test_two_posts_with_the_same_clock_keep_that_clock(store):
    store.create_post("help", "甲", "甲正文", "小明", 500)
    store.create_post("help", "乙", "乙正文", "小红", 500)
    assert [post["created_ms"] for post in store.list_posts()] == [500, 500]


def test_title_rejects_blank_and_overlong(store):
    for bad in ["", "   ", "\t\n"]:
        with pytest.raises(ValueError):
            store.create_post("help", bad, "正文", "小明", 1)
    with pytest.raises(ValueError):
        store.create_post("help", "字" * (TITLE_MAX + 1), "正文", "小明", 1)


def test_title_at_the_limit_is_kept_whole(store):
    title = "字" * TITLE_MAX
    created = store.create_post("help", title, "正文", "小明", 1)
    assert created["title"] == title  # 不截断


def test_body_rejects_blank_and_overlong(store):
    for bad in ["", "   "]:
        with pytest.raises(ValueError):
            store.create_post("help", "标题", bad, "小明", 1)
    with pytest.raises(ValueError):
        store.create_post("help", "标题", "字" * (BODY_MAX + 1), "小明", 1)


def test_author_rejects_blank_and_overlong(store):
    for bad in ["", "  "]:
        with pytest.raises(ValueError):
            store.create_post("help", "标题", "正文", bad, 1)
    with pytest.raises(ValueError):
        store.create_post("help", "标题", "正文", "字" * (AUTHOR_MAX + 1), 1)


def test_unknown_section_is_refused_not_redirected(store):
    for bad in ["", "nope", "ACTIVITY", "活动", None]:
        with pytest.raises(ValueError):
            store.create_post(bad, "标题", "正文", "小明", 1)
    assert store.list_posts() == []  # 一条都没落库，更没被塞进第一个板块


def test_every_declared_section_round_trips(store):
    for index, key in enumerate(SECTIONS):
        created = store.create_post(key, f"标题{index}", "正文", "小明", index)
        assert created["section"] == key


def test_list_posts_is_newest_first(store):
    for ms in (10, 30, 20):
        store.create_post("help", f"标题{ms}", "正文", "小明", ms)
    assert [post["created_ms"] for post in store.list_posts()] == [30, 20, 10]


def test_list_posts_filters_by_section(store):
    store.create_post("help", "求助", "正文", "小明", 1)
    store.create_post("sharing", "分享", "正文", "小红", 2)
    store.create_post("help", "再求助", "正文", "小刚", 3)

    assert [post["title"] for post in store.list_posts(section="help")] == ["再求助", "求助"]
    assert [post["title"] for post in store.list_posts(section="sharing")] == ["分享"]
    assert len(store.list_posts()) == 3  # 不过滤时全给


def test_limit_is_respected_and_rejects_zero(store):
    for ms in range(5):
        store.create_post("help", f"标题{ms}", "正文", "小明", ms)
    assert len(store.list_posts(limit=2)) == 2
    assert len(store.list_posts(limit=1)) == 1
    with pytest.raises(ValueError):
        store.list_posts(limit=0)


def test_created_ms_must_be_an_integer(store):
    for bad in ["1700000000000", 1.0, None, True]:
        with pytest.raises(ValueError):
            store.create_post("help", "标题", "正文", "小明", bad)


# ---------- 图片 ----------

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 32
WEBP = b"RIFF" + b"\x00\x00\x00\x00" + b"WEBP" + b"\x00" * 24


def test_saves_png_jpeg_and_webp(store):
    assert sniff_image(PNG) == (".png", "image/png")
    assert sniff_image(JPEG) == (".jpg", "image/jpeg")
    assert sniff_image(WEBP) == (".webp", "image/webp")
    for payload in (PNG, JPEG, WEBP):
        record = store.save_image(payload, 1700000000000)
        assert record["byte_size"] == len(payload)
        assert len(record["id"]) == 16


def test_rejects_anything_that_is_not_an_image(store):
    with pytest.raises(ValueError):
        store.save_image(b"this is plain text, not an image", 1)
    with pytest.raises(ValueError):
        store.save_image(PNG[:4], 1)  # 只有文件头，截断的
    with pytest.raises(ValueError):
        store.save_image(PNG[:4] + b"\x00" * (IMAGE_MAX_BYTES + 1), 1)


def test_image_bytes_round_trip_and_unknown_is_none(store):
    record = store.save_image(PNG, 1)
    payload, content_type = store.image_bytes(record["id"])
    assert payload == PNG
    assert content_type == "image/png"
    assert store.image_bytes("0" * 16) is None
    assert store.image_bytes("../../../etc/passwd") is None


def test_post_carries_its_images_and_dedupes(store):
    first = store.save_image(PNG, 1)["id"]
    second = store.save_image(JPEG, 2)["id"]
    created = store.create_post("help", "带图", "正文", "我", 3, image_ids=[first, second, first])
    assert created["image_ids"] == [first, second]
    assert store.list_posts()[0]["image_ids"] == [first, second]


def test_post_refuses_unknown_or_too_many_images(store):
    record = store.save_image(PNG, 1)["id"]
    for bad in (["nope"], ["../etc/passwd"], [123], "notalist"):
        with pytest.raises(ValueError):
            store.create_post("help", "标题", "正文", "我", 2, image_ids=bad)
    with pytest.raises(ValueError):
        store.create_post("help", "标题", "正文", "我", 2, image_ids=[record] * 7)


def test_old_database_gains_the_image_column_without_losing_posts(tmp_path):
    # 上一轮的库没有 image_ids 列，升级时必须补上且老帖子照常读得出来。
    import sqlite3

    legacy = tmp_path / "legacy.db"
    connection = sqlite3.connect(legacy)
    connection.execute(
        "CREATE TABLE posts (id INTEGER PRIMARY KEY AUTOINCREMENT, section TEXT NOT NULL,"
        " title TEXT NOT NULL, body TEXT NOT NULL, author TEXT NOT NULL,"
        " created_ms INTEGER NOT NULL)"
    )
    connection.execute(
        "INSERT INTO posts (section, title, body, author, created_ms)"
        " VALUES ('help', '老帖子', '正文', '老用户', 5)"
    )
    connection.commit()
    connection.close()

    opened = CommunityStore(str(legacy))
    try:
        posts = opened.list_posts()
        assert len(posts) == 1
        assert posts[0]["title"] == "老帖子"
        assert posts[0]["image_ids"] == []
        created = opened.create_post("help", "新帖子", "正文", "我", 6,
                                     image_ids=[opened.save_image(PNG, 6)["id"]])
        assert len(created["image_ids"]) == 1
    finally:
        opened.close()
