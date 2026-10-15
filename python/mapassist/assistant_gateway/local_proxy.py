"""Restrict an explicitly configured HTTP backend to its local proxy peer."""

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send


class LocalProxyPeerMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] not in {"http", "websocket"}:
            await self.app(scope, receive, send)
            return
        client = scope.get("client")
        # Use the socket peer, never forwarded headers. The CLI also binds only
        # to 127.0.0.1 and disables Uvicorn proxy header interpretation.
        if not client or client[0] != "127.0.0.1":
            if scope["type"] == "websocket":
                await send({"type": "websocket.close", "code": 4403,
                            "reason": "Local HTTPS proxy required"})
            else:
                await JSONResponse(
                    {"error": {"code": "local_proxy_required",
                               "message": "Use the local HTTPS reverse proxy."}},
                    status_code=403,
                )(scope, receive, send)
            return
        await self.app(scope, receive, send)
