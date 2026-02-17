"""
app.py — API Flask para consulta de eventos de jogos do ge.globo.com

Execução:
    python app.py

Uso:
    GET /match?url=<URL_DO_JOGO>          → dados da partida (cria tracker se não existir)
    GET /trackers                          → lista todos os trackers ativos
    DELETE /match?url=<URL_DO_JOGO>        → para e remove o tracker da URL

Exemplo:
    http://localhost:5000/match?url=https://ge.globo.com/futebol/futebol-internacional/futebol-espanhol/jogo/16-02-2026/girona-barcelona.ghtml
"""

import logging
import atexit
import json
import time
from collections import OrderedDict

from flask import Flask, request, jsonify, Response
from tracker import MatchTrackerManager

# ─── Config / Logging ──────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%H:%M:%S",
)

app = Flask(__name__)
app.json.sort_keys = False  # preservar a ordem das chaves no JSON

manager = MatchTrackerManager()

# Garantir que todas as threads são paradas ao encerrar o app
atexit.register(manager.stop_all)


# ─── Validação ──────────────────────────────────────────────────────

def _validate_url(url: str):
    """Retorna (url_limpa, erro_response) — erro é None se URL for válida."""
    url = url.strip()
    if not url:
        return None, (jsonify({"error": "Parâmetro 'url' é obrigatório."}), 400)
    if "ge.globo.com" not in url or "/jogo/" not in url:
        return None, (jsonify({"error": "URL inválida. Informe uma URL de jogo do ge.globo.com."}), 400)
    return url, None


# ─── Rotas ──────────────────────────────────────────────────────────

@app.route("/match", methods=["GET"])
def get_match():
    """
    Retorna os dados da partida. Se ainda não há um tracker para essa URL,
    cria um (scraping inicial + conexão SSE). Nas próximas chamadas,
    retorna o JSON atualizado em tempo real via Server-Sent Events.
    """
    url, err = _validate_url(request.args.get("url", ""))
    if err:
        return err

    try:
        tracker = manager.get_or_create(url)
        data = tracker.get_data()
    except Exception as e:
        return jsonify({"error": f"Erro ao buscar dados: {str(e)}"}), 500

    result = data.to_dict()

    # Adicionar metadados do tracker
    status = tracker.status_dict()
    result["_tracker"] = OrderedDict([
        ("is_running", status["is_running"]),
        ("sse_connected", status["sse_connected"]),
        ("last_updated", status["last_updated"]),
        ("update_count", status["update_count"]),
    ])

    return jsonify(result), 200


@app.route("/match", methods=["DELETE"])
def remove_match():
    """Para o SSE e remove o tracker de uma URL."""
    url, err = _validate_url(request.args.get("url", ""))
    if err:
        return err

    removed = manager.remove(url)
    if removed:
        return jsonify({"message": f"Tracker removido: {url}"}), 200
    return jsonify({"error": "Nenhum tracker ativo para essa URL."}), 404


@app.route("/trackers", methods=["GET"])
def list_trackers():
    """Lista todos os trackers ativos com seus status."""
    return jsonify({
        "count": len(manager.list_all()),
        "trackers": manager.list_all(),
    }), 200


@app.route("/stream", methods=["GET"])
def stream_events():
    """
    Endpoint SSE que retransmite eventos em tempo real de uma partida.
    Conecta ao tracker (cria se não existir) e publica eventos conforme chegam.

    Uso: GET /stream?url=<URL_DO_JOGO>

    Eventos SSE emitidos:
      - connected:      Confirmação de conexão (inclui estado inicial completo)
      - play_new:       Novo lance (GOAL, CARD, SUBSTITUTION, NORMAL, IMPORTANT, etc.)
      - play_update:    Atualização de um lance existente
      - period_change:  Mudança de período (PRIMEIRO_TEMPO, INTERVALO, SEGUNDO_TEMPO, etc.)
      - score_change:   Mudança no placar
      - ping:           Keep-alive a cada 15s
    """
    url, err = _validate_url(request.args.get("url", ""))
    if err:
        return err

    try:
        tracker = manager.get_or_create(url)
    except Exception as e:
        return jsonify({"error": f"Erro ao criar tracker: {str(e)}"}), 500

    q = tracker.subscribe()

    def generate():
        try:
            # Enviar estado inicial completo como primeiro evento
            initial_data = tracker.get_data().to_dict()
            initial_data["_tracker"] = OrderedDict([
                ("is_running", tracker.is_running),
                ("sse_connected", tracker.sse_connected),
            ])
            yield f"event: connected\ndata: {json.dumps(initial_data, ensure_ascii=False)}\n\n"

            last_ping = time.time()
            while True:
                try:
                    msg = q.get(timeout=5)
                    event_type = msg.get("event", "message")
                    data = msg.get("data", {})
                    yield f"event: {event_type}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
                except Exception:
                    # Timeout na queue — enviar ping keep-alive
                    now = time.time()
                    if now - last_ping >= 15:
                        last_ping = now
                        yield f"event: ping\ndata: {json.dumps({'time': int(now)})}\n\n"
        except GeneratorExit:
            pass
        finally:
            tracker.unsubscribe(q)

    return Response(
        generate(),
        mimetype="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@app.route("/", methods=["GET"])
def index():
    return jsonify(OrderedDict([
        ("app", "GE Football Scraper API"),
        ("version", "4.0.0"),
        ("description", "API com monitoramento em tempo real via SSE (PushStream) de partidas do ge.globo.com"),
        ("endpoints", OrderedDict([
            ("GET /", "Informações da API"),
            ("GET /match?url=<URL>",
             "Dados da partida (cria tracker automático com SSE se não existir)"),
            ("DELETE /match?url=<URL>",
             "Para e remove o tracker de uma partida"),
            ("GET /trackers",
             "Lista todos os trackers ativos"),
            ("GET /stream?url=<URL>",
             "SSE stream em tempo real — retransmite eventos da partida"),
        ])),
        ("exemplo", "GET /match?url=https://ge.globo.com/futebol/futebol-internacional/futebol-espanhol/jogo/16-02-2026/girona-barcelona.ghtml"),
    ]))


if __name__ == "__main__":
    print("=" * 60)
    print("  GE Football Scraper API  v4.0 (SSE retransmission)")
    print("  Rodando em http://localhost:5000")
    print("=" * 60)
    print()
    print("  Endpoints:")
    print("    GET    /              → informações da API")
    print("    GET    /match?url=…   → dados do jogo (inicia tracker)")
    print("    DELETE /match?url=…   → para o tracker")
    print("    GET    /trackers      → lista trackers ativos")
    print("    GET    /stream?url=…  → SSE stream em tempo real")
    print()
    app.run(host="0.0.0.0", port=5000, debug=False)
