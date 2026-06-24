"""
app.py — API Flask para consulta de eventos de jogos (ge.globo.com + Sofascore)

Execução:
    python app.py

Uso:
    GET /match?url=<URL_DO_JOGO>          → dados da partida (cria tracker se não existir)
    GET /trackers                          → lista todos os trackers ativos
    DELETE /match?url=<URL_DO_JOGO>        → para e remove o tracker da URL

Exemplo (GE):
    http://localhost:5000/match?url=https://ge.globo.com/futebol/futebol-internacional/futebol-espanhol/jogo/16-02-2026/girona-barcelona.ghtml
Exemplo (Sofascore):
    http://localhost:5000/match?url=https://www.sofascore.com/pt/football/match/al-duhail-al-ahli/uOnsQNr#id:15884736
"""

import logging
import atexit
import json
import time
from collections import OrderedDict

from flask import Flask, request, jsonify, Response
from tracker import MatchTrackerManager
from scraper import fetch_ge_agenda
from datetime import datetime

from sofascore_scraper import is_sofascore_url, extract_event_id, fetch_scheduled_events, BR_TZ
from sofascore_tracker import SofascoreTracker

# ─── Config / Logging ──────────────────────────────────────────────

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%H:%M:%S",
)

app = Flask(__name__)
app.json.sort_keys = False  # preservar a ordem das chaves no JSON

manager = MatchTrackerManager()
sofascore_trackers: dict[str, SofascoreTracker] = {}
_sofascore_lock = __import__("threading").Lock()

# Garantir que todas as threads são paradas ao encerrar o app
def _stop_all():
    manager.stop_all()
    with _sofascore_lock:
        for t in sofascore_trackers.values():
            t.stop()
        sofascore_trackers.clear()

atexit.register(_stop_all)


# ─── Validação ──────────────────────────────────────────────────────

def _validate_url(url: str):
    """Retorna (url_limpa, erro_response) — erro é None se URL for válida."""
    url = url.strip()
    if not url:
        return None, (jsonify({"error": "Parâmetro 'url' é obrigatório."}), 400)

    # Aceitar URLs do ge.globo.com OU do Sofascore
    if is_sofascore_url(url):
        event_id = extract_event_id(url)
        if not event_id:
            return None, (jsonify({"error": "URL do Sofascore inválida. Não foi possível extrair o event_id."}), 400)
        return url, None

    if "ge.globo.com" not in url or "/jogo/" not in url:
        return None, (jsonify({"error": "URL inválida. Informe uma URL de jogo do ge.globo.com ou sofascore.com."}), 400)

    return url, None


def _get_or_create_tracker(url: str):
    """
    Retorna o tracker adequado para a URL (GE ou Sofascore).
    Cria e inicia um novo se não existir.
    """
    if is_sofascore_url(url):
        key = url.split("?")[0].rstrip("/")
        with _sofascore_lock:
            if key not in sofascore_trackers:
                tracker = SofascoreTracker(url=url)
                tracker.start()
                sofascore_trackers[key] = tracker
                logging.getLogger(__name__).info("Novo tracker Sofascore criado: %s", key)
            return sofascore_trackers[key]
    else:
        return manager.get_or_create(url)


def _remove_tracker(url: str) -> bool:
    """Remove o tracker adequado para a URL."""
    if is_sofascore_url(url):
        key = url.split("?")[0].rstrip("/")
        with _sofascore_lock:
            tracker = sofascore_trackers.pop(key, None)
        if tracker:
            tracker.stop()
            return True
        return False
    else:
        return manager.remove(url)


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
        tracker = _get_or_create_tracker(url)
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

    removed = _remove_tracker(url)
    if removed:
        return jsonify({"message": f"Tracker removido: {url}"}), 200
    return jsonify({"error": "Nenhum tracker ativo para essa URL."}), 404


@app.route("/agenda", methods=["GET"])
def ge_agenda():
    """
    Lista os próximos jogos de futebol do ge.globo.com (com página de
    tempo-real), ordenados por data e horário.

    Parâmetros (query string):
      - limit:    quantidade máxima de jogos (padrão: 10)
      - upcoming: '1' (padrão) exclui jogos encerrados; '0' inclui todos

    Uso: GET /agenda  |  GET /agenda?limit=10
    """
    try:
        limit = int(request.args.get("limit", "10"))
    except ValueError:
        limit = 10
    limit = max(1, min(limit, 25))

    only_upcoming = request.args.get("upcoming", "1").strip() != "0"

    try:
        games = fetch_ge_agenda(only_upcoming=only_upcoming)
    except Exception as e:
        return jsonify({"error": f"Erro ao buscar a agenda do GE: {str(e)}"}), 500

    games = games[:limit]
    return jsonify(OrderedDict([
        ("count", len(games)),
        ("games", games),
    ])), 200


@app.route("/today", methods=["GET"])
def today_games():
    """
    Lista os jogos de futebol do dia (Sofascore), ordenados por horário.

    Parâmetros (query string):
      - date:    data no formato YYYY-MM-DD (padrão: hoje, fuso do Brasil)
      - q:       filtro por texto — busca em time/campeonato/país
      - country: filtra por país exato (ex: Brazil)

    Uso: GET /today  |  GET /today?q=vitória  |  GET /today?country=Brazil
    """
    target_date = request.args.get("date", "").strip() or None
    q = request.args.get("q", "").strip().lower()
    country = request.args.get("country", "").strip().lower()

    try:
        games = fetch_scheduled_events(target_date)
    except Exception as e:
        return jsonify({"error": f"Erro ao buscar jogos do dia: {str(e)}"}), 500

    if q:
        def _matches(g):
            blob = " ".join([
                str(g.get("home_team", "")),
                str(g.get("away_team", "")),
                str(g.get("tournament", "")),
                str(g.get("country", "")),
            ]).lower()
            return q in blob
        games = [g for g in games if _matches(g)]
    elif country:
        games = [g for g in games if str(g.get("country", "")).lower() == country]

    return jsonify(OrderedDict([
        ("date", target_date or datetime.now(BR_TZ).strftime("%Y-%m-%d")),
        ("count", len(games)),
        ("games", games),
    ])), 200


@app.route("/trackers", methods=["GET"])
def list_trackers():
    """Lista todos os trackers ativos com seus status."""
    ge_trackers = manager.list_all()
    with _sofascore_lock:
        sf_trackers = [t.status_dict() for t in sofascore_trackers.values()]
    all_trackers = ge_trackers + sf_trackers
    return jsonify({
        "count": len(all_trackers),
        "trackers": all_trackers,
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
        tracker = _get_or_create_tracker(url)
    except Exception as e:
        return jsonify({"error": f"Erro ao criar tracker: {str(e)}"}), 500

    q = tracker.subscribe()

    def generate():
        try:
            # Enviar estado inicial completo como primeiro evento
            initial_data = tracker.get_data().to_dict()
            initial_data["_tracker"] = OrderedDict([
                ("is_running", tracker.is_running),
                ("sse_connected", getattr(tracker, 'sse_connected', False)),
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
        ("app", "Football Scraper API"),
        ("version", "5.0.0"),
        ("description", "API com monitoramento em tempo real de partidas (ge.globo.com + Sofascore)"),
        ("endpoints", OrderedDict([
            ("GET /", "Informações da API"),
            ("GET /match?url=<URL>",
             "Dados da partida (cria tracker automático — suporta ge.globo.com e sofascore.com)"),
            ("DELETE /match?url=<URL>",
             "Para e remove o tracker de uma partida"),
            ("GET /agenda",
             "Lista os próximos jogos de futebol do GE com tempo-real (?limit=, ?upcoming=)"),
            ("GET /today",
             "Lista os jogos do dia ordenados por horário (filtros: ?q=, ?country=, ?date=)"),
            ("GET /trackers",
             "Lista todos os trackers ativos"),
            ("GET /stream?url=<URL>",
             "SSE stream em tempo real — retransmite eventos da partida"),
        ])),
        ("exemplos", OrderedDict([
            ("ge.globo.com", "GET /match?url=https://ge.globo.com/futebol/futebol-internacional/futebol-espanhol/jogo/16-02-2026/girona-barcelona.ghtml"),
            ("sofascore.com", "GET /match?url=https://www.sofascore.com/pt/football/match/team-a-team-b/abc#id:12345678"),
        ])),
    ]))


if __name__ == "__main__":
    print("=" * 60)
    print("  Football Scraper API  v5.0 (GE + Sofascore)")
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
