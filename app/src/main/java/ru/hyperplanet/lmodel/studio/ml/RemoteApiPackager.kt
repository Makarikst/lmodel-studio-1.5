package ru.hyperplanet.lmodel.studio.ml

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object RemoteApiPackager {

    data class Result(val zipFile: File?, val error: String? = null)

    fun packageServer(
        outDir: File,
        modelId: Long,
        modelName: String,
        apiKey: String,
        trainedDataJson: String,
        parameters: Map<String, String>
    ): Result {
        if (apiKey.isBlank()) return Result(null, "Нужен API-ключ")
        if (trainedDataJson.isBlank()) return Result(null, "Модель не обучена")
        return try {
            outDir.mkdirs()
            val zip = File(outDir, "lmodel_remote_api_${modelId}.zip")
            ZipOutputStream(FileOutputStream(zip)).use { zos ->
                fun put(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                put("model.json", trainedDataJson)
                put(
                    "config.json",
                    """{"model_id":${modelId},"model_name":${jsonStr(modelName)},"api_key":${jsonStr(apiKey)},"port":8765,"parameters":${mapToJson(parameters)}}"""
                )
                put("server.py", SERVER_PY)
                put("wsgi.py", WSGI_PY)
                put("requirements.txt", REQUIREMENTS)
                put("Dockerfile", DOCKERFILE)
                put("docker-compose.yml", DOCKER_COMPOSE)
                put("lmodel-api.service", SYSTEMD_UNIT)
                put("README.md", README)
                put("README_PAW.md", README_PAW)
                put("start.sh", START_SH)
            }
            Result(zip)
        } catch (e: Exception) {
            Result(null, e.message)
        }
    }

    /** Распакованные файлы сервера в папку модели (без model/config — их пишет ModelRemoteSync). */
    fun writeUnpackedFiles(dir: File) {
        dir.mkdirs()
        File(dir, "server.py").writeText(SERVER_PY, Charsets.UTF_8)
        File(dir, "wsgi.py").writeText(WSGI_PY, Charsets.UTF_8)
        File(dir, "requirements.txt").writeText(REQUIREMENTS, Charsets.UTF_8)
        File(dir, "README_PAW.md").writeText(README_PAW, Charsets.UTF_8)
        File(dir, "README.md").writeText(README, Charsets.UTF_8)
        File(dir, "start.sh").writeText(START_SH, Charsets.UTF_8)
        File(dir, "Dockerfile").writeText(DOCKERFILE, Charsets.UTF_8)
        File(dir, "docker-compose.yml").writeText(DOCKER_COMPOSE, Charsets.UTF_8)
    }

    private fun jsonStr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    private fun mapToJson(m: Map<String, String>): String =
        m.entries.joinToString(",", "{", "}") { (k, v) -> "${jsonStr(k)}:${jsonStr(v)}" }

    private const val START_SH = "#!/bin/bash\npython3 server.py\n"
    private const val REQUIREMENTS = "flask>=3.0.0\n"
    private const val DOCKERFILE = "FROM python:3.12-slim\nWORKDIR /app\nRUN pip install flask\nCOPY server.py model.json config.json wsgi.py ./\nEXPOSE 8765\nCMD [\"python3\", \"server.py\"]\n"
    private const val DOCKER_COMPOSE = "version: \"3.8\"\nservices:\n  lmodel-api:\n    build: .\n    ports:\n      - \"8765:8765\"\n    restart: always\n"
    private const val SYSTEMD_UNIT = "[Unit]\nDescription=LModel Studio Remote API\nAfter=network.target\n\n[Service]\nType=simple\nWorkingDirectory=/opt/lmodel-api\nExecStart=/usr/bin/python3 /opt/lmodel-api/server.py\nRestart=always\nRestartSec=3\n\n[Install]\nWantedBy=multi-user.target\n"

    private val README = """# LModel Studio Remote API

## VPS
python3 server.py

## Docker
docker compose up -d --build

## PythonAnywhere
См. README_PAW.md
"""

    private val README_PAW = """# LModel API на PythonAnywhere

## 1. Аккаунт
Зарегистрируйтесь на https://www.pythonanywhere.com (хватит Free).

## 2. Загрузка файлов
Files → Upload:
- server.py
- wsgi.py
- config.json
- model.json
- requirements.txt

Лучше в папку `/home/ВАШ_ЮЗЕР/lmodel_api/`

Или в Bash:
```bash
mkdir -p ~/lmodel_api
# загрузите zip и:
cd ~/lmodel_api && unzip lmodel_remote_api_*.zip
```

## 3. Flask
```bash
pip3.10 install --user flask
# или версия python вашего web app
```

## 4. Web App
Web → Add a new web app → Manual configuration → Python 3.10 (или новее).

**Source code:** `/home/ВАШ_ЮЗЕР/lmodel_api`  
**WSGI file:** откройте ссылку WSGI и замените содержимое на:

```python
import sys
path = "/home/ВАШ_ЮЗЕР/lmodel_api"
if path not in sys.path:
    sys.path.append(path)
from server import create_app
application = create_app()
```

Либо укажите загруженный `wsgi.py` (поправьте путь).

## 5. config.json
Поставьте свой `api_key` (тот же, что в LModel Studio).

## 6. model.json
В приложении: Dev Mode → **Собрать удалённый API** → возьмите `model.json` из ZIP и замените demo.

## 7. Reload
Web → Reload.

## 8. Проверка
```bash
curl https://ВАШ_ЮЗЕР.pythonanywhere.com/v1/health

curl -X POST https://ВАШ_ЮЗЕР.pythonanywhere.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer lms_ваш_ключ" \
  -d '{"messages":[{"role":"user","content":"Привет"}]}'
```

## Важно (Free)
- Сайт «засыпает» без визитов ~3 месяца без входа в аккаунт — зайдите иногда.
- HTTPS уже есть у PAW.
- Свой домен — на платных планах.
"""

    private val WSGI_PY = """# PythonAnywhere WSGI entry
# В Web tab укажите этот файл как WSGI configuration file
# и путь project: /home/ВАШ_ЮЗЕР/lmodel_api

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from server import create_app

application = create_app()
"""

    private val SERVER_PY = """#!/usr/bin/env python3
from __future__ import annotations

import json
import math
import os
import re
import time
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parent

def load_json(name: str) -> dict:
    path = ROOT / name
    if not path.exists():
        return {}
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)

CONFIG = load_json("config.json")
MODEL = load_json("model.json")

API_KEY = (CONFIG.get("api_key") or os.environ.get("LMODEL_API_KEY") or "").strip()
PORT = int(os.environ.get("LMODEL_PORT", CONFIG.get("port", 8765)))
PARAMS = CONFIG.get("parameters") or {}
MODEL_ID = CONFIG.get("model_id", 0)
MODEL_NAME = CONFIG.get("model_name") or f"lmodel-{MODEL_ID}"

SENTENCES = MODEL.get("sentences") or []
INV = {str(k): v for k, v in (MODEL.get("invertedIndex") or {}).items()}
STOP = set(
    "и в во на с со по о об от до за из у к ко а но или да нет "
    "the a an of to in on for is are was were be".split()
)


def tokenize(text: str) -> list[str]:
    text = text.lower()
    parts = re.split(r"\s+", text)
    out: list[str] = []
    for p in parts:
        p = re.sub(r"^[^\w]+|[^\w]+${'$'}", "", p, flags=re.UNICODE)
        p = "".join(ch for ch in p if ch.isalnum() or ch in "-'")
        if len(p) >= 2 and p not in STOP:
            out.append(p)
    return out


def retrieve(query: str, top_k: int = 5) -> list[tuple[str, float]]:
    terms = tokenize(query)
    if not terms or not SENTENCES:
        return []
    scores: dict[int, float] = {}
    n = float(len(SENTENCES))
    for term in terms:
        idxs = INV.get(term) or []
        idf = 1.0 + math.log((n + 1) / (len(idxs) + 1))
        for idx in idxs:
            scores[idx] = scores.get(idx, 0.0) + idf
    ranked = sorted(scores.items(), key=lambda x: -x[1])[:top_k]
    return [(SENTENCES[i], sc) for i, sc in ranked if 0 <= i < len(SENTENCES)]


def generate(user_msg: str, system_prompt: str = "") -> tuple[str, str]:
    top_k = int(PARAMS.get("top_k") or 5)
    max_len = int(PARAMS.get("max_length") or 80)
    hits = retrieve(user_msg, top_k=top_k)
    if not hits:
        return (
            "Модель не нашла релевантных фрагментов в обученном корпусе.",
            "Поиск: совпадений нет",
        )
    words = 0
    parts: list[str] = []
    for text, _ in hits:
        w = len(text.split())
        if words + w > max_len and parts:
            break
        parts.append(text.strip())
        words += w
    answer = "\n\n".join(parts)
    return answer, f"Фрагментов: {len(parts)}, слов: {words}"


def check_auth(header: str | None) -> bool:
    if not API_KEY:
        return True
    if not header:
        return False
    if header.lower().startswith("bearer "):
        token = header[7:].strip()
    else:
        token = header.strip()
    return token == API_KEY


def handle_chat_payload(payload: dict[str, Any]) -> tuple[int, dict]:
    messages = payload.get("messages") or []
    user_msg = ""
    system_prompt = ""
    for m in messages:
        role = m.get("role")
        if role == "system":
            system_prompt = m.get("content") or ""
        elif role == "user":
            user_msg = m.get("content") or ""
    if not str(user_msg).strip():
        return 400, {"error": "empty user message"}
    text, reasoning = generate(user_msg, system_prompt)
    pt = len(str(user_msg).split())
    ct = len(text.split())
    return 200, {
        "id": f"chatcmpl-paw-{MODEL_ID}",
        "object": "chat.completion",
        "created": int(time.time()),
        "model": f"lmodel-{MODEL_ID}",
        "choices": [
            {
                "index": 0,
                "message": {
                    "role": "assistant",
                    "content": text,
                    "reasoning": reasoning,
                },
                "finish_reason": "stop",
            }
        ],
        "usage": {
            "prompt_tokens": pt,
            "completion_tokens": ct,
            "total_tokens": pt + ct,
        },
    }


# ---------- Flask app (PythonAnywhere) ----------
try:
    from flask import Flask, request, jsonify, Response
except ImportError:  # pragma: no cover
    Flask = None  # type: ignore


def create_app() -> "Flask":
    if Flask is None:
        raise RuntimeError("Установите Flask: pip install flask")
    app = Flask(__name__)

    @app.after_request
    def cors(resp):
        resp.headers["Access-Control-Allow-Origin"] = "*"
        resp.headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type"
        resp.headers["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        return resp

    @app.route("/", methods=["GET"])
    def root():
        return "LModel Studio API (PythonAnywhere / VPS)\n", 200, {"Content-Type": "text/plain; charset=utf-8"}

    @app.route("/v1/health", methods=["GET"])
    def health():
        return jsonify({"status": "ok", "mode": "remote", "modelId": MODEL_ID, "name": MODEL_NAME})

    @app.route("/v1/models", methods=["GET"])
    @app.route("/v1/models/", methods=["GET"])
    def models():
        if not check_auth(request.headers.get("Authorization")):
            return jsonify({"error": "unauthorized"}), 401
        return jsonify(
            {
                "object": "list",
                "data": [
                    {
                        "id": f"lmodel-{MODEL_ID}",
                        "object": "model",
                        "name": MODEL_NAME,
                    }
                ],
            }
        )

    @app.route("/v1/chat/completions", methods=["POST", "OPTIONS"])
    def chat():
        if request.method == "OPTIONS":
            return Response(status=204)
        if not check_auth(request.headers.get("Authorization")):
            return jsonify({"error": "unauthorized"}), 401
        payload = request.get_json(silent=True) or {}
        code, body = handle_chat_payload(payload)
        return jsonify(body), code

    return app


app = create_app() if Flask is not None else None


# ---------- stdlib server (VPS) ----------
def run_stdlib():
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            print("[%s] %s" % (self.log_date_time_string(), fmt % args))

        def _cors(self):
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

        def _json(self, code, obj):
            data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self._cors()
            self.end_headers()
            self.wfile.write(data)

        def do_OPTIONS(self):
            self.send_response(204)
            self._cors()
            self.end_headers()

        def do_GET(self):
            path = urlparse(self.path).path
            if path == "/v1/health":
                return self._json(200, {"status": "ok", "mode": "remote", "modelId": MODEL_ID})
            if path == "/":
                body = b"LModel Studio Remote API\n"
                self.send_response(200)
                self.send_header("Content-Type", "text/plain")
                self.send_header("Content-Length", str(len(body)))
                self._cors()
                self.end_headers()
                self.wfile.write(body)
                return
            if not check_auth(self.headers.get("Authorization")):
                return self._json(401, {"error": "unauthorized"})
            if path in ("/v1/models", "/v1/models/"):
                return self._json(
                    200,
                    {
                        "object": "list",
                        "data": [{"id": f"lmodel-{MODEL_ID}", "object": "model", "name": MODEL_NAME}],
                    },
                )
            self._json(404, {"error": "not_found"})

        def do_POST(self):
            path = urlparse(self.path).path
            if not check_auth(self.headers.get("Authorization")):
                return self._json(401, {"error": "unauthorized"})
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length).decode("utf-8") if length else "{}"
            if path != "/v1/chat/completions":
                return self._json(404, {"error": "not_found"})
            try:
                payload = json.loads(raw or "{}")
                code, body = handle_chat_payload(payload)
                self._json(code, body)
            except Exception as e:
                self._json(500, {"error": str(e)})

    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"LModel API on 0.0.0.0:{PORT} model={MODEL_NAME}")
    server.serve_forever()


if __name__ == "__main__":
    run_stdlib()
"""
}