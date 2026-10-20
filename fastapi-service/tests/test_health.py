from fastapi.testclient import TestClient


def test_health_reports_java_up(app_env):
    app, _, _ = app_env()
    with TestClient(app) as client:
        response = client.get("/agent/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["java_up"] is True
    assert body["tools_count"] == 5
    assert body["schema_source"] == "java"


def test_health_reports_java_down_without_failing(app_env):
    app, _, _ = app_env(connect_down=True)
    with TestClient(app) as client:
        response = client.get("/agent/health")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["java_up"] is False
    assert body["tools_count"] is None
