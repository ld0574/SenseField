"""社区发帖的服务端：存储与 HTTP 接口。"""

from .app import create_app
from .store import CommunityStore

__all__ = ["create_app", "CommunityStore"]
