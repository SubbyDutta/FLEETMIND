"""Chunker + loader unit tests — fully offline.

The fake counter (words == tokens) exercises every code path without the
Gemini API: this is the payoff of injecting TokenCounter instead of
constructing it inside chunk().
"""
import pytest

from app.rag.chunker import chunk
from app.rag.loader import Document, load_runbooks


def fake_counter(text: str) -> int:
    return len(text.split())


def make_doc(text: str) -> Document:
    return Document(doc_id="doc", text=text, title="Test Title",
                    source="mem", content_hash="hash")


THREE_SECTIONS = """# Test Title

## Alpha

First section body here.

## Beta

Second section body here.

## Gamma

Third section body here.
"""


def test_three_sections_three_chunks():
    chunks = chunk(make_doc(THREE_SECTIONS), fake_counter)
    assert [c.chunk_no for c in chunks] == [0, 1, 2]
    assert [c.heading for c in chunks] == ["Alpha", "Beta", "Gamma"]
    # every chunk is self-describing: full contextual header present
    for c in chunks:
        assert c.content.startswith("Document: Test Title\nSection:")
    assert "First section body" in chunks[0].content


def test_heading_with_empty_body_is_skipped():
    text = "# Test Title\n\n## Empty\n\n## Full\n\nActual body."
    chunks = chunk(make_doc(text), fake_counter)
    assert [c.heading for c in chunks] == ["Full"]


def test_oversized_section_splits_within_budget():
    # 8 paragraphs x 4 words = 32 body words; header costs 5 fake tokens,
    # so budget is 10 -> must split into multiple pieces, all under the cap.
    body = "\n\n".join("alpha beta gamma delta" for _ in range(8))
    chunks = chunk(make_doc(f"# Test Title\n\n## Big\n\n{body}"),
                   fake_counter, max_tokens=15, overlap=4)
    assert len(chunks) > 1
    assert all(c.token_count <= 15 for c in chunks)
    assert all(c.content.startswith("Document: Test Title") for c in chunks)


def test_invalid_overlap_raises():
    with pytest.raises(ValueError):
        chunk(make_doc(THREE_SECTIONS), fake_counter, max_tokens=10, overlap=10)


def test_loader_rejects_doc_without_title(tmp_path):
    (tmp_path / "no-title.md").write_text("## only a subheading\n\nbody")
    with pytest.raises(ValueError):
        load_runbooks(tmp_path)


def test_loader_rejects_empty_directory(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_runbooks(tmp_path)
