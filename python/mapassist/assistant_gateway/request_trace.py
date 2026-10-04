"""Request identifiers inherited by provider tasks, without payloads or credentials."""

from contextvars import ContextVar

visual_request_context: ContextVar[tuple[str, int, str, str] | None] = ContextVar(
    "assistant_visual_request_context", default=None,
)
