"""Load editable server-side vision instructions independently of provider code."""
from pathlib import Path

DEFAULT_PROMPT_PATH = Path(__file__).with_name("vision_prompt.txt")


def load_vision_prompt(path: Path | None = None) -> str:
    source = path or DEFAULT_PROMPT_PATH
    try:
        if source.stat().st_size > 32_768:
            raise ValueError("Vision prompt file exceeds 32768 bytes")
        text = source.read_text(encoding="utf-8-sig").strip()
    except (OSError, UnicodeError) as error:
        raise ValueError("Cannot read the configured UTF-8 vision prompt file") from error
    if not text or "\x00" in text:
        raise ValueError("Vision prompt file must contain nonempty text without NUL")
    return text
