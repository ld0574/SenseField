"""社区发帖的最小 HTTP 接口层。

只做三件事：把 HTTP 入参交给 pydantic 校验（不合规走 422）、把合法入参交给
store、把结果原样返回。业务边界不在这一层重复写第二遍。
"""

from __future__ import annotations

import time
from typing import Any, Callable, Literal, get_args

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

from .store import (
    AUTHOR_MAX,
    BODY_MAX,
    SECTION_LABELS,
    SECTIONS,
    TITLE_MAX,
    CommunityStore,
)

SectionKey = Literal[
    "activity",
    "qualified_accounts",
    "hottest",
    "discovery",
    "help",
    "sharing",
    "partners",
]

# 端侧枚举、store 的 SECTIONS、这里的 Literal 是三处独立声明，跑偏了会静默放错板块。
if set(get_args(SectionKey)) != set(SECTIONS):
    raise AssertionError("SectionKey 与 SECTIONS 不一致，板块 key 已经跑偏")


class PostIn(BaseModel):
    """发帖入参。长度边界交给 pydantic，越界直接 422。"""

    section: SectionKey
    title: str = Field(min_length=1, max_length=TITLE_MAX)
    body: str = Field(min_length=1, max_length=BODY_MAX)
    author: str = Field(min_length=1, max_length=AUTHOR_MAX)


def create_app(
    store: CommunityStore,
    clock: Callable[[], float] | None = None,
) -> FastAPI:
    """工厂：返回社区 API 的 FastAPI 实例。clock 可注入以便回放。"""
    app = FastAPI(title="SenseField Community", version="0.1.0")
    now = clock if clock is not None else time.time

    @app.post("/posts", status_code=201)
    def create_post(payload: PostIn) -> dict[str, Any]:
        created_ms = int(now() * 1000)
        try:
            return store.create_post(
                payload.section,
                payload.title,
                payload.body,
                payload.author,
                created_ms,
            )
        except ValueError as exc:
            # 纯空白标题／正文之类过得了长度校验、过不了业务边界，同样算入参不合规。
            raise HTTPException(status_code=422, detail=str(exc)) from exc

    @app.get("/posts")
    def list_posts(
        section: SectionKey | None = Query(default=None),
        limit: int = Query(default=50, ge=1, le=100),
    ) -> list[dict[str, Any]]:
        return store.list_posts(section=section, limit=limit)

    @app.get("/health")
    def health() -> dict[str, str]:
        """存活探针：发布平台与手机端都可以先打这个确认服务活着。"""
        return {"status": "ok"}

    @app.get("/", include_in_schema=False)
    def root() -> HTMLResponse:
        # 根路径必须自己有内容：只给 /docs 的话，别人打开域名看到的是 404，
        # 会以为服务挂了。这里直接列出能点的地方。
        rows = "".join(
            f"<li><code>{section}</code> — {label}</li>"
            for section, label in zip(SECTIONS, SECTION_LABELS)
        )
        return HTMLResponse(
            "<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\">"
            "<title>听野社区服务</title>"
            "<style>body{font-family:system-ui,sans-serif;max-width:640px;margin:40px auto;"
            "line-height:1.7;padding:0 16px}a{color:#236874}code{background:#e7f2f2;"
            "padding:2px 6px;border-radius:4px}</style>"
            "<h1>听野社区服务</h1>"
            "<p>接口可用：</p><ul>"
            "<li><a href=\"/docs\">/docs</a> — 接口文档，可直接发请求</li>"
            "<li><a href=\"/posts\">/posts</a> — 帖子列表（JSON）</li>"
            "<li><a href=\"/health\">/health</a> — 存活探针</li>"
            "</ul><p>七个板块：</p><ul>" + rows + "</ul></html>"
        )

    return app
