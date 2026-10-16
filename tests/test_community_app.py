from __future__ import annotations

import pytest

pytest.importorskip("fastapi")
pytest.importorskip("httpx")

from fastapi.testclient import TestClient  # noqa: E402

from mapassist.community import CommunityStore, create_app  # noqa: E402
from mapassist.community.store import (  # noqa: E402
    AUTHOR_MAX,
    BODY_MAX,
    SECTION_LABELS,
    SECTIONS,
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


def test_root_page_is_a_usable_ui_not_just_a_pointer(client):
    # 根路径以前没有路由，打开域名只看到 404，会被当成服务挂了。
    response = client.get("/")
    assert response.status_code == 200
    assert "text/html" in response.headers["content-type"]
    body = response.text
    # 能看：板块筛选与帖子列表；能发：标题/正文/署名三个输入项与提交按钮。
    for marker in ("按板块筛选", "发一条", "f-section", "f-title", "f-body", "f-author", "send"):
        assert marker in body, marker
    # 无障碍与「不替用户做决定」：错误就地说明，且明确不清空已写内容。
    # 文案后来补上了「选好的图」，所以这里只断言共同的那部分。
    for marker in ("aria-invalid", "role=\"alert\"", "都还在", "aria-label"):
        assert marker in body, marker
    for path in ("/posts", "/sections", "/health", "/docs"):
        assert path in body


def test_sections_endpoint_lists_all_seven_in_order(client):
    response = client.get("/sections")
    assert response.status_code == 200
    listed = response.json()
    assert [item["key"] for item in listed] == list(SECTIONS)
    assert [item["label"] for item in listed] == list(SECTION_LABELS)
    assert len(listed) == 7


def test_health_reports_ok(client):
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


# ---------- 图片 ----------

PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 32


def test_upload_image_then_attach_it_to_a_post(client):
    uploaded = client.post("/images", content=PNG, headers={"Content-Type": "image/png"})
    assert uploaded.status_code == 201
    record = uploaded.json()
    assert record["content_type"] == "image/png"
    assert record["url"] == "/images/" + record["id"]

    created = client.post("/posts", json={
        "section": "discovery", "title": "带图帖", "body": "看图", "author": "我",
        "image_ids": [record["id"]],
    })
    assert created.status_code == 201
    assert created.json()["images"] == [{"id": record["id"], "url": record["url"]}]
    assert client.get("/posts").json()[0]["images"][0]["url"] == record["url"]


def test_uploaded_image_is_served_back_verbatim(client):
    record = client.post("/images", content=PNG, headers={"Content-Type": "image/png"}).json()
    served = client.get("/images/" + record["id"])
    assert served.status_code == 200
    assert served.headers["content-type"] == "image/png"
    assert served.content == PNG


def test_upload_refuses_non_images_empty_and_oversized(client):
    assert client.post("/images", content=b"just text").status_code == 422
    assert client.post("/images", content=PNG[:4]).status_code == 422
    oversized = PNG[:8] + b"\x00" * (5 * 1024 * 1024)
    assert client.post("/images", content=oversized).status_code == 422


def test_unknown_or_hostile_image_ids_are_refused(client):
    assert client.get("/images/deadbeefdeadbeef").status_code == 404
    assert client.get("/images/..%2F..%2FWindows%2Fwin.ini").status_code == 404
    response = client.post("/posts", json={
        "section": "help", "title": "标题", "body": "正文", "author": "我",
        "image_ids": ["deadbeefdeadbeef"],
    })
    assert response.status_code == 422


def test_page_offers_paste_and_file_picker_for_images(client):
    body = client.get("/").text
    for marker in ("paste", "f-file", 'type="file"', "URL.createObjectURL",
                   "选好的图都还在", 'accept="image/png,image/jpeg,image/webp"'):
        assert marker in body, marker
