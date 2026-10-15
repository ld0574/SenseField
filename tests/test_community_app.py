from __future__ import annotations

import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")

from fastapi.testclient import TestClient  # noqa: E402

from mapassist.community import CommunityStore, create_app  # noqa: E402
from mapassist.community.store import (  # noqa: E402
    AUTHOR_MAX,
    BODY_MAX,
    TITLE_MAX,
)

FIXED_CLOCK = lambda: 1700000000.0  # noqa: E731


@pytest.fixture()
def client(tmp_path):
    store = CommunityStore(str(tmp_path / "community.db"))
    app = create_app(store, clock=FIXED_CLOCK)
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        store.close()


def test_post_returns_201_and_the_stored_post(client):
    response = client.post("/posts", json={
        "section": "help", "title": "求助", "body": "正文", "author": "小明",
    })
    assert response.status_code == 201
    body = response.json()
    assert body["id"] == 1
    assert body["section"] == "help"
    assert body["created_ms"] == 1700000000000  # 时钟是注入的，读数可预期


def test_posted_post_shows_up_in_the_list(client):
    client.post("/posts", json={
        "section": "sharing", "title": "分享", "body": "正文", "author": "小红",
    })
    listed = client.get("/posts")
    assert listed.status_code == 200
    assert [post["title"] for post in listed.json()] == ["分享"]


@pytest.mark.parametrize("payload", [
    {"section": "nope", "title": "标题", "body": "正文", "author": "小明"},  # 未知板块
    {"section": "help", "title": "", "body": "正文", "author": "小明"},  # 空标题
    {"section": "help", "title": "   ", "body": "正文", "author": "小明"},  # 纯空白标题
    {"section": "help", "title": "字" * (TITLE_MAX + 1), "body": "正文", "author": "小明"},
    {"section": "help", "title": "标题", "body": "", "author": "小明"},  # 空正文
    {"section": "help", "title": "标题", "body": "字" * (BODY_MAX + 1), "author": "小明"},
    {"section": "help", "title": "标题", "body": "正文", "author": ""},  # 空署名
    {"section": "help", "title": "标题", "body": "正文", "author": "字" * (AUTHOR_MAX + 1)},
    {"section": "help", "title": "标题", "body": "正文"},  # 缺署名
])
def test_invalid_payload_is_422_not_400(client, payload):
    response = client.post("/posts", json=payload)
    assert response.status_code == 422  # 走校验层，不是手写 if 返 400


def test_get_without_section_returns_everything(client):
    for key in ("help", "sharing"):
        client.post("/posts", json={
            "section": key, "title": key, "body": "正文", "author": "小明",
        })
    assert len(client.get("/posts").json()) == 2


def test_get_filters_by_section(client):
    for key in ("help", "sharing", "help"):
        client.post("/posts", json={
            "section": key, "title": key, "body": "正文", "author": "小明",
        })
    listed = client.get("/posts?section=help")
    assert listed.status_code == 200
    assert [post["section"] for post in listed.json()] == ["help", "help"]


def test_get_limit_is_clamped_to_1_100(client):
    for index in range(3):
        client.post("/posts", json={
            "section": "help", "title": f"标题{index}", "body": "正文", "author": "小明",
        })
    assert len(client.get("/posts?limit=1").json()) == 1
    assert len(client.get("/posts?limit=2").json()) == 2
    assert client.get("/posts?limit=0").status_code == 422
    assert client.get("/posts?limit=101").status_code == 422
    assert client.get("/posts?limit=-1").status_code == 422


def test_unknown_section_on_get_is_422(client):
    assert client.get("/posts?section=nope").status_code == 422
