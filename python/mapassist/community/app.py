"""社区发帖的最小 HTTP 接口层。

只做三件事：把 HTTP 入参交给 pydantic 校验（不合规走 422）、把合法入参交给
store、把结果原样返回。业务边界不在这一层重复写第二遍。
"""

from __future__ import annotations

import time
from typing import Any, Callable, Literal, get_args

from fastapi import FastAPI, HTTPException, Query
from pydantic import BaseModel, Field

from .store import (
    AUTHOR_MAX,
    BODY_MAX,
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

    return app
