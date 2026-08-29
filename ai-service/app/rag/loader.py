
from dataclasses import dataclass
from pathlib import Path
import hashlib
import logging

logger = logging.getLogger(__name__)



@dataclass(frozen=True)
class Document:
    doc_id: str
    text: str
    title: str
    source: str
    content_hash: str


def load_runbooks(directory: str | Path) -> list[Document]:

    directory = Path(directory)

    if not directory.is_dir():
        raise NotADirectoryError(f"Runbook directory not found: {directory}")

    docs = [_load_file(p) for p in sorted(directory.glob("*.md"))]

    if not docs:
        raise FileNotFoundError(f"No .md runbooks found in {directory}")
    logger.info("Loaded %d runbooks from %s", len(docs), directory)
    return docs


def _load_file(path: Path) -> Document:
    text = path.read_text(encoding="utf-8").strip()
    if not text:
        raise ValueError(f"Runbook is empty: {path}")
    return Document(
        doc_id=path.stem,
        text=text,
        title=_extract_title(text, path),
        source=str(path),
        content_hash=hashlib.sha256(text.encode()).hexdigest(),
    )


def _extract_title(text: str, path: Path) -> str:

    for line in text.splitlines():
        if line.strip().startswith("# "):
            return line.strip()[2:].strip()
    raise ValueError(f"No '# ' title heading in {path}")
