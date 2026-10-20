"""Local synthetic demo lifecycle. All services bind loopback. No production deployment."""
from pathlib import Path
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / "runtime"
PROCESSES = RUNTIME / "processes.json"
HIDDEN = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0


def environment():
    env = dict(os.environ)
    path = ROOT / ".env"
    if path.exists():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            if "=" not in line or line.lstrip().startswith("#"):
                continue
            key, value = line.split("=", 1)
            key, value = key.strip(), value.strip().strip('"').strip("'")
            if re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
                env.setdefault(key, value)
    host, port = env.get("SUPPLY_DB_HOST", "127.0.0.1"), env.get("SUPPLY_DB_PORT", "3306")
    db = env.get("SUPPLY_DB_NAME", "supply_agent")
    if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_]{0,50}", db):
        raise ValueError("Invalid demo database name")
    env.setdefault("SUPPLY_DB_USER", "supply_agent")
    env.setdefault("SUPPLY_DB_PASSWORD", "demo-only-db")
    suffix = "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
    env.setdefault("SUPPLY_DB_URL", f"jdbc:mysql://{host}:{port}/{db}{suffix}")
    env.setdefault("SUPPLY_TEST_DB_URL", f"jdbc:mysql://{host}:{port}/supply_agent_test{suffix}")
    env.setdefault("AGENT_PROVIDER", "demo")
    env["JAVA_BASE_URL"] = "http://127.0.0.1:18180"
    env["JAVA_API_URL"] = env["JAVA_BASE_URL"]
    env["AGENT_GATEWAY_BASE_URL"] = "http://127.0.0.1:18181"
    env["FLOW_SERVICE_BASE_URL"] = "http://127.0.0.1:18183"
    env["FLOW_READ_TOKEN"] = "token-reader-1"
    env["PYTHONUTF8"] = "1"
    return env


def executable(name):
    return shutil.which(name + (".cmd" if os.name == "nt" and name in {"mvn", "npm"} else "")) or name


def run(command, cwd, env=None):
    print("RUN", " ".join(map(str, command)), flush=True)
    subprocess.run(list(map(str, command)), cwd=cwd, env=env or environment(), check=True)


def prepare():
    env = environment()
    for folder in ["java-app", "form-workflow/java-flow-service"]:
        run([executable("mvn"), "-q", "-DskipTests", "package"], ROOT / folder, env)
    for folder in ["web-app", "form-workflow/pi-agent-runtime"]:
        run([executable("npm"), "ci"], ROOT / folder, env)
    run([executable("npm"), "run", "build"], ROOT / "web-app", env)


def init_db():
    env = environment()
    mysql = shutil.which("mysql")
    if not mysql:
        raise RuntimeError("mysql client is required for init-db; see docs/02-run-demo.md")
    env["MYSQL_PWD"] = env["SUPPLY_DB_PASSWORD"]
    for name in ["schema.sql", "seed.sql", "05_w4_pending_po_lookup.sql", "demo_case.sql"]:
        text = (ROOT / "db" / name).read_text(encoding="utf-8")
        text = text.replace("USE supply_agent;", "USE " + env.get("SUPPLY_DB_NAME", "supply_agent") + ";")
        if not text.lstrip().startswith("USE "):
            text = "USE " + env.get("SUPPLY_DB_NAME", "supply_agent") + ";\n" + text
        subprocess.run([mysql, "--protocol=tcp", "--default-character-set=utf8mb4",
                        "-h", env.get("SUPPLY_DB_HOST", "127.0.0.1"), "-P", env.get("SUPPLY_DB_PORT", "3306"),
                        "-u", env["SUPPLY_DB_USER"]], input=text.encode("utf-8"), env=env, check=True)
    print("Initialized synthetic schema and fixtures (seed rows may be updated). Use only an isolated demo database.")


def fingerprint(pid):
    try:
        if os.name == "nt":
            command = f"(Get-Process -Id {int(pid)} -ErrorAction Stop).StartTime.ToUniversalTime().Ticks"
            return subprocess.check_output(["powershell", "-NoProfile", "-Command", command],
                                           stderr=subprocess.DEVNULL, creationflags=HIDDEN, text=True).strip()
        proc = Path(f"/proc/{pid}/stat")
        if proc.exists():
            return proc.read_text().split(")", 1)[1].split()[19]
        return subprocess.check_output(["ps", "-p", str(pid), "-o", "lstart="], text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def stop():
    if not PROCESSES.exists():
        print("No recorded demo processes.")
        return
    records = json.loads(PROCESSES.read_text(encoding="utf-8"))
    unresolved = []
    for record in reversed(records):
        current = fingerprint(record["pid"])
        if not current:
            continue
        if current != record["fingerprint"]:
            print("Skipped reused process id:", record["name"])
            continue
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(record["pid"]), "/T", "/F"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               creationflags=HIDDEN, check=True)
            else:
                os.kill(record["pid"], signal.SIGTERM)
            print("Stopped", record["name"])
        except (OSError, subprocess.CalledProcessError):
            unresolved.append(record)
    PROCESSES.write_text(json.dumps(unresolved), encoding="utf-8")
    if unresolved:
        raise RuntimeError("Some recorded demo processes could not be stopped")


def start():
    env = environment()
    for port in [18180, 18181, 18183, 15173]:
        with socket.socket() as sock:
            if sock.connect_ex(("127.0.0.1", port)) == 0:
                raise RuntimeError(f"Port {port} is occupied; stop the previous demo first")
    RUNTIME.mkdir(exist_ok=True)
    commands = [
        ("forms", [executable("java"), "-Dfile.encoding=UTF-8", "-jar", "target/java-flow-service-0.1.0.jar", "--server.port=18183"], ROOT / "form-workflow/java-flow-service", "http://127.0.0.1:18183/api/flow/stats"),
        ("api", [executable("java"), "-Dfile.encoding=UTF-8", "-jar", "target/supply-agent-0.1.0.jar", "--spring.profiles.active=demo", "--server.port=18180"], ROOT / "java-app", "http://127.0.0.1:18180/health"),
        ("agent", [sys.executable, "-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "18181"], ROOT / "fastapi-service", "http://127.0.0.1:18181/agent/health"),
        ("web", [executable("node"), "node_modules/vite/bin/vite.js", "--host", "127.0.0.1", "--port", "15173", "--strictPort"], ROOT / "web-app", "http://127.0.0.1:15173"),
    ]
    records = []
    try:
        for name, argv, cwd, url in commands:
            with (RUNTIME / (name + ".log")).open("wb") as log:
                proc = subprocess.Popen(argv, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=HIDDEN)
            identity = fingerprint(proc.pid)
            if not identity:
                raise RuntimeError(f"Could not record {name} process identity")
            records.append({"name": name, "pid": proc.pid, "fingerprint": identity})
            PROCESSES.write_text(json.dumps(records), encoding="utf-8")
            ready = False
            for _ in range(240):
                if proc.poll() is not None:
                    raise RuntimeError(f"{name} exited; inspect runtime/{name}.log")
                try:
                    req = urllib.request.Request(url, headers={"X-Demo-Token": "token-reader-1"})
                    with urllib.request.urlopen(req, timeout=1) as response:
                        ready = response.status == 200
                except OSError:
                    pass
                if ready:
                    break
                time.sleep(0.25)
            if not ready:
                raise RuntimeError(f"{name} readiness timed out; inspect runtime/{name}.log")
        print("Ready: http://127.0.0.1:15173 ; admin/admin123 or viewer/viewer123")
        print("Assistant mode:", env["AGENT_PROVIDER"], "; form workflow provider: scripted Faux")
    except Exception:
        stop()
        raise


if __name__ == "__main__":
    command = sys.argv[1] if len(sys.argv) > 1 else "help"
    actions = {"prepare": prepare, "init-db": init_db, "start": start, "stop": stop}
    if command not in actions:
        raise SystemExit("Usage: python scripts/demo.py prepare|init-db|start|stop")
    actions[command]()
