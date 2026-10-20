import re

from app.ids import new_conversation_id, new_request_id, new_tool_call_id

_PATTERNS = {
    new_request_id: r"^req-[0-9a-f]{12}$",
    new_conversation_id: r"^conv-[0-9a-f]{12}$",
    new_tool_call_id: r"^tc-[0-9a-f]{8}$",
}


def test_id_prefix_and_length():
    for factory, pattern in _PATTERNS.items():
        for _ in range(20):
            assert re.fullmatch(pattern, factory()), factory.__name__


def test_ids_are_unique():
    first = new_request_id()
    assert len({new_request_id() for _ in range(100)}) == 100
    assert new_tool_call_id() != new_tool_call_id()
    assert first == first
