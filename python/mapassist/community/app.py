"""社区发帖的最小 HTTP 接口层。

只做三件事：把 HTTP 入参交给 pydantic 校验（不合规走 422）、把合法入参交给
store、把结果原样返回。业务边界不在这一层重复写第二遍。
"""

from __future__ import annotations

import time
from typing import Any, Callable, Literal, get_args

from fastapi import FastAPI, HTTPException, Query, Request, Response
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

from .store import (
    AUTHOR_MAX,
    BODY_MAX,
    IMAGE_MAX_BYTES,
    IMAGES_PER_POST_MAX,
    SECTION_LABELS,
    SECTIONS,
    TITLE_MAX,
    CommunityStore,
)
from .web import PAGE

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
    image_ids: list[str] = Field(default_factory=list, max_length=IMAGES_PER_POST_MAX)


def _with_images(post: dict[str, Any]) -> dict[str, Any]:
    """给帖子补上可直接取用的图片地址，前端不用自己拼。"""
    enriched = dict(post)
    enriched["images"] = [
        {"id": image_id, "url": f"/images/{image_id}"} for image_id in post.get("image_ids", [])
    ]
    return enriched


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
            created = store.create_post(
                payload.section,
                payload.title,
                payload.body,
                payload.author,
                created_ms,
                image_ids=list(payload.image_ids),
            )
        except ValueError as exc:
            # 纯空白标题／正文之类过得了长度校验、过不了业务边界，同样算入参不合规。
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return _with_images(created)

    @app.get("/posts")
    def list_posts(
        section: SectionKey | None = Query(default=None),
        limit: int = Query(default=50, ge=1, le=100),
    ) -> list[dict[str, Any]]:
        return [_with_images(post) for post in store.list_posts(section=section, limit=limit)]

    @app.post("/images", status_code=201)
    async def upload_image(request: Request) -> dict[str, Any]:
        """上传一张图片。请求体就是图片原始字节——不用 multipart，也就不用新依赖。

        格式按文件头判定，不看调用方声明的 Content-Type；超限或认不出格式一律 422。
        """
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            if len(data) > IMAGE_MAX_BYTES:
                # 超限就别再收，避免被人拿大包把内存吃满。
                raise HTTPException(
                    status_code=422,
                    detail=f"图片超过 {IMAGE_MAX_BYTES // (1024 * 1024)} MB",
                )
        try:
            record = store.save_image(bytes(data), int(now() * 1000))
        except ValueError as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return {**record, "url": f"/images/{record['id']}"}

    @app.get("/images/{image_id}")
    def read_image(image_id: str) -> Response:
        found = store.image_bytes(image_id)
        if found is None:
            raise HTTPException(status_code=404, detail="图片不存在")
        payload, content_type = found
        return Response(
            content=payload,
            media_type=content_type,
            headers={"Cache-Control": "public, max-age=86400"},
        )

    @app.get("/health")
    def health() -> dict[str, str]:
        """存活探针：发布平台与手机端都可以先打这个确认服务活着。"""
        return {"status": "ok"}

    @app.get("/", include_in_schema=False)
    def root() -> HTMLResponse:
        # 根路径必须自己有内容：只给 /docs 的话，别人打开域名看到的是 404，
        # 会以为服务挂了。这里直接给出能看能发帖的页面。
        return HTMLResponse(PAGE)

    @app.get("/sections")
    def sections() -> list[dict[str, str]]:
        """七个板块。网页界面从这里取板块，不在自己代码里再写一份。"""
        return [{"key": key, "label": label} for key, label in zip(SECTIONS, SECTION_LABELS)]

    return app
