#!/usr/bin/env python3
"""Load owner-only mounted configuration before starting the TLS gateway."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import stat


def load_configuration(path: Path) -> None:
    if stat.S_IMODE(path.stat().st_mode) & 0o077:
        raise ValueError("Gateway configuration must be readable only by its owner")
    values = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(values, dict) or not values:
        raise ValueError("Gateway configuration must be a nonempty JSON object")
    for key, value in values.items():
        if (not isinstance(key, str)
                or re.fullmatch(r"(?:ASSISTANT_GATEWAY_[A-Z0-9_]+|ZHIPU_API_KEY)", key) is None
                or not isinstance(value, str) or "\x00" in key or "\x00" in value):
            raise ValueError("Gateway configuration contains an unsupported entry")
    os.environ.update(values)


def main() -> None:
    path = Path(os.environ.get("ASSISTANT_GATEWAY_ENV_FILE", "/run/secrets/assistant-gateway.json"))
    try:
        load_configuration(path)
    except (OSError, ValueError):
        raise SystemExit("Cannot read a valid owner-only gateway configuration") from None
    from mapassist.assistant_gateway.cli import main as serve
    serve()


if __name__ == "__main__":
    main()
