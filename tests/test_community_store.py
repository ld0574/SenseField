from __future__ import annotations

import pytest

from mapassist.community.store import (
    AUTHOR_MAX,
    BODY_MAX,
    SECTIONS,
    TITLE_MAX,
    CommunityStore,
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
