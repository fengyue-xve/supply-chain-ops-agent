import pytest
from app.main import create_app
from app.models.fake import FakeModelProvider
from app.models.deepseek import DeepSeekProvider

def test_demo_mode_is_explicit_and_uses_scripted_rules(monkeypatch):
    monkeypatch.setenv("AGENT_PROVIDER", "demo")
    app = create_app()
    assert type(app.state.provider) is FakeModelProvider

def test_default_remains_real_model_adapter(monkeypatch):
    monkeypatch.delenv("AGENT_PROVIDER", raising=False)
    app = create_app()
    assert type(app.state.provider) is DeepSeekProvider

def test_unknown_provider_does_not_silently_become_fake(monkeypatch):
    monkeypatch.setenv("AGENT_PROVIDER", "typo")
    with pytest.raises(ValueError, match="AGENT_PROVIDER"):
        create_app()
