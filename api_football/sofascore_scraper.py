"""
sofascore_scraper.py — Módulo de acesso à API do Sofascore.

Extrai dados de um jogo de futebol a partir do event_id do Sofascore:
  - Placar e status
  - Times (nome, abreviação, escudo)
  - Período / tempo atual
  - Lista completa de incidentes (gols, cartões, substituições)
  - Estatísticas da partida

API pública (sem autenticação):
  - GET /api/v1/event/{id}           → dados gerais da partida
  - GET /api/v1/event/{id}/incidents → incidentes (gols, cartões, substituições)
  - GET /api/v1/event/{id}/statistics → estatísticas por período
"""

import re
import logging
from dataclasses import dataclass, field, asdict
from collections import OrderedDict
from datetime import datetime, timezone, timedelta

import requests

# Fuso horário do Brasil (Bahia / Vitória) — com fallback caso tzdata não exista
try:
    from zoneinfo import ZoneInfo
    BR_TZ = ZoneInfo("America/Bahia")
except Exception:  # pragma: no cover - ambiente sem tzdata
    BR_TZ = timezone(timedelta(hours=-3))

from scraper import (
    Event,
    Team,
    StreamInfo,
    MatchData,
    MatchStatistics as GEMatchStatistics,
    TeamStatistics as GETeamStatistics,
)

logger = logging.getLogger(__name__)

SOFASCORE_API = "https://api.sofascore.com/api/v1"

SOFASCORE_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/131.0.0.0 Safari/537.36"
    ),
    "Accept": "application/json",
    "Accept-Language": "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
    "Referer": "https://www.sofascore.com/",
    "Origin": "https://www.sofascore.com",
}

# ─── Mapeamento de status Sofascore → period_id compatível com GE ───

STATUS_TO_PERIOD_ID = {
    "notstarted": "PRE_JOGO",
    "inprogress": None,  # depende do código específico
    "finished": "FIM_DE_JOGO",
    "canceled": "CANCELADO",
    "postponed": "ADIADO",
}

STATUS_CODE_TO_PERIOD = {
    0: ("Não iniciado", "PRE_JOGO"),
    6: ("1º tempo", "PRIMEIRO_TEMPO"),
    7: ("2º tempo", "SEGUNDO_TEMPO"),
    31: ("Intervalo", "INTERVALO"),
    100: ("Encerrado", "FIM_DE_JOGO"),
    110: ("Após prorrogação", "FIM_DE_JOGO"),
    120: ("Após pênaltis", "FIM_DE_JOGO"),
}


# ─── Helpers ────────────────────────────────────────────────────────


def extract_event_id(url: str) -> int | None:
    """
    Extrai o event_id de uma URL do Sofascore.
    Formatos suportados:
      - https://www.sofascore.com/.../match/.../uOnsQNr#id:15884736
      - https://www.sofascore.com/.../match/...#id:15884736
      - Direto por ID numérico
    """
    # Tentar extrair do fragmento #id:NNNN
    m = re.search(r'#id[:\-](\d+)', url)
    if m:
        return int(m.group(1))

    # Tentar extrair ID numérico no final do path
    m = re.search(r'/(\d{6,})(?:[/#?]|$)', url)
    if m:
        return int(m.group(1))

    # Tentar como número direto
    if url.strip().isdigit():
        return int(url.strip())

    return None


def is_sofascore_url(url: str) -> bool:
    """Verifica se a URL é do Sofascore."""
    return "sofascore.com" in url.lower() or "sofascore" in url.lower()


def _api_get(endpoint: str) -> dict:
    """Faz GET na API do Sofascore e retorna o JSON."""
    url = f"{SOFASCORE_API}{endpoint}"
    resp = requests.get(url, headers=SOFASCORE_HEADERS, timeout=15)
    resp.raise_for_status()
    return resp.json()


# ─── Conversão de incidentes Sofascore → formato Event (GE) ────────


def _incident_to_event(incident: dict, home_team: Team, away_team: Team) -> Event | None:
    """Converte um incidente do Sofascore para o modelo Event compatível com o GE."""
    inc_type = incident.get("incidentType", "")
    time_val = incident.get("time", 0)
    added_time = incident.get("addedTime")
    is_home = incident.get("isHome", True)

    minute = str(time_val)
    if added_time:
        minute = f"{time_val}+{added_time}"

    team = home_team if is_home else away_team
    team_abbr = team.abbreviation
    team_id = team.id

    if inc_type == "goal":
        player_info = incident.get("player", {})
        player_name = player_info.get("shortName") or player_info.get("name", "")

        goal_type = incident.get("goalType", "regular")
        goal_kind = {
            "regular": "REGULAR_GOAL",
            "penalty": "PENALTY_GOAL",
            "owngoal": "OWN_GOAL",
        }.get(goal_type, "REGULAR_GOAL")

        # Se gol contra, o time é invertido (marca contra si mesmo)
        if goal_type == "owngoal":
            team = away_team if is_home else home_team
            team_abbr = team.abbreviation
            team_id = team.id

        assist_info = incident.get("assist1", {})
        assist_name = assist_info.get("shortName") or assist_info.get("name", "") if assist_info else ""
        description = f"Assistência: {assist_name}" if assist_name else ""

        return Event(
            minute=minute,
            type="GOAL",
            type_label="Gol",
            title=f"Gol de {player_name}",
            description=description,
            team=team_abbr,
            team_id=team_id,
            player=player_name,
            goal_kind=goal_kind,
        )

    elif inc_type == "card":
        player_info = incident.get("player", {})
        player_name = player_info.get("shortName") or player_info.get("name", "")
        inc_class = incident.get("incidentClass", "")

        card_type_map = {
            "yellow": "YELLOW",
            "red": "RED",
            "yellowred": "SECOND_YELLOW",
        }
        card_type = card_type_map.get(inc_class, "YELLOW")

        type_label_map = {
            "YELLOW": "Cartão amarelo",
            "RED": "Cartão vermelho",
            "SECOND_YELLOW": "Segundo amarelo",
        }

        return Event(
            minute=minute,
            type="CARD",
            type_label=type_label_map.get(card_type, "Cartão"),
            title=f"{type_label_map.get(card_type, 'Cartão')} para {player_name}",
            description="",
            team=team_abbr,
            team_id=team_id,
            player=player_name,
            card_type=card_type,
        )

    elif inc_type == "substitution":
        player_in_info = incident.get("playerIn", {})
        player_out_info = incident.get("playerOut", {})
        player_in = player_in_info.get("shortName") or player_in_info.get("name", "")
        player_out = player_out_info.get("shortName") or player_out_info.get("name", "")

        return Event(
            minute=minute,
            type="SUBSTITUTION",
            type_label="Substituição",
            title=f"Substituição: sai {player_out}, entra {player_in}",
            description="",
            team=team_abbr,
            team_id=team_id,
            player_in=player_in,
            player_out=player_out,
        )

    elif inc_type == "period":
        # Marcadores de período (início, intervalo, fim) — não gerar evento
        return None

    elif inc_type == "varDecision":
        description = incident.get("description", "")
        return Event(
            minute=minute,
            type="IMPORTANT",
            type_label="VAR",
            title=f"Decisão do VAR",
            description=description or "Revisão do VAR",
            team=team_abbr,
            team_id=team_id,
        )

    elif inc_type == "injuryTime":
        added = incident.get("length", 0)
        return Event(
            minute=minute,
            type="NORMAL",
            type_label="Acréscimos",
            title=f"+{added} minutos de acréscimo",
            description="",
        )

    return None


def _incident_unique_key(incident: dict) -> str:
    """Gera uma chave única para um incidente do Sofascore."""
    inc_type = incident.get("incidentType", "")
    time_val = incident.get("time", 0)
    added_time = incident.get("addedTime", 0)
    is_home = incident.get("isHome", "")

    player = incident.get("player", {})
    player_id = player.get("id", "") if isinstance(player, dict) else ""

    player_in = incident.get("playerIn", {})
    player_in_id = player_in.get("id", "") if isinstance(player_in, dict) else ""

    return f"{inc_type}|{time_val}|{added_time}|{is_home}|{player_id}|{player_in_id}"


# ─── Parsing de estatísticas ───────────────────────────────────────


def _parse_statistics(stats_data: dict) -> GEMatchStatistics | None:
    """Converte estatísticas do Sofascore para o formato GEMatchStatistics."""
    stats_list = stats_data.get("statistics", [])
    if not stats_list:
        return None

    # Usar "ALL" (estatísticas do jogo todo) — geralmente o primeiro item
    all_stats = stats_list[0] if stats_list else {}
    groups = all_stats.get("groups", [])

    home_stats = GETeamStatistics()
    away_stats = GETeamStatistics()

    for group in groups:
        items = group.get("statisticsItems", [])
        for item in items:
            name = item.get("name", "")
            home_val = item.get("home", "")
            away_val = item.get("away", "")

            try:
                h = int(str(home_val).replace("%", ""))
            except (ValueError, TypeError):
                h = 0
            try:
                a = int(str(away_val).replace("%", ""))
            except (ValueError, TypeError):
                a = 0

            if name == "Ball possession":
                home_stats.ball_possession = h
                away_stats.ball_possession = a
            elif name == "Corner kicks":
                home_stats.corner_kick = h
                away_stats.corner_kick = a
            elif name == "Fouls":
                home_stats.foul_made = h
                away_stats.foul_made = a
            elif name == "Offsides":
                home_stats.off_side = h
                away_stats.off_side = a
            elif name == "Yellow cards":
                home_stats.yellow_card_received = h
                away_stats.yellow_card_received = a
            elif name == "Red cards":
                home_stats.red_card_received = h
                away_stats.red_card_received = a
            elif name == "Total shots":
                home_stats.wrong_finish = max(0, h - home_stats.goal_finish)
                away_stats.wrong_finish = max(0, a - away_stats.goal_finish)
            elif name == "Shots on target":
                home_stats.goal_finish = h
                away_stats.goal_finish = a
            elif name == "Shots off target":
                home_stats.ball_out_finish = h
                away_stats.ball_out_finish = a
            elif name == "Blocked shots":
                home_stats.blocked_finish = h
                away_stats.blocked_finish = a
            elif name == "Goalkeeper saves":
                home_stats.defense = h
                away_stats.defense = a
            elif name == "Tackles":
                home_stats.tackle = h
                away_stats.tackle = a
            elif name == "Accurate passes":
                # Formato "X (Y%)" — extrair X
                try:
                    home_stats.right_passes = int(str(home_val).split("(")[0].strip()) if home_val else 0
                    away_stats.right_passes = int(str(away_val).split("(")[0].strip()) if away_val else 0
                except (ValueError, TypeError):
                    pass
            elif name == "Total passes":
                try:
                    home_stats.total_passes = int(str(home_val)) if home_val else 0
                    away_stats.total_passes = int(str(away_val)) if away_val else 0
                except (ValueError, TypeError):
                    pass

    return GEMatchStatistics(
        home_team=home_stats,
        away_team=away_stats,
    )


# ─── Função principal ──────────────────────────────────────────────


def fetch_sofascore_match(event_id: int) -> MatchData:
    """
    Busca todos os dados de uma partida no Sofascore e retorna
    um MatchData compatível com o formato do ge.globo.com.
    """
    # 1. Dados gerais da partida
    event_data = _api_get(f"/event/{event_id}")
    event = event_data.get("event", event_data)

    # Times
    home_raw = event.get("homeTeam", {})
    away_raw = event.get("awayTeam", {})

    home_team = Team(
        id=home_raw.get("id", 0),
        name=home_raw.get("name", ""),
        abbreviation=home_raw.get("nameCode", home_raw.get("shortName", "")[:3].upper()),
        badge_png=f"https://api.sofascore.com/api/v1/team/{home_raw.get('id', 0)}/image"
        if home_raw.get("id") else "",
    )
    away_team = Team(
        id=away_raw.get("id", 0),
        name=away_raw.get("name", ""),
        abbreviation=away_raw.get("nameCode", away_raw.get("shortName", "")[:3].upper()),
        badge_png=f"https://api.sofascore.com/api/v1/team/{away_raw.get('id', 0)}/image"
        if away_raw.get("id") else "",
    )

    # Placar
    home_score_data = event.get("homeScore", {})
    away_score_data = event.get("awayScore", {})
    home_score = home_score_data.get("current", 0) if isinstance(home_score_data, dict) else 0
    away_score = away_score_data.get("current", 0) if isinstance(away_score_data, dict) else 0

    # Status / período
    status = event.get("status", {})
    status_code = status.get("code", 0)
    status_desc = status.get("description", "")
    status_type = status.get("type", "")

    period_label, period_id = STATUS_CODE_TO_PERIOD.get(
        status_code, (status_desc, status_type.upper() if status_type else "")
    )

    # Tempo atual
    time_info = event.get("time", {})
    current_time = ""
    if status_type == "inprogress" and time_info:
        import time as _time
        current_ts = time_info.get("currentPeriodStartTimestamp", 0)
        initial = time_info.get("initial", 0)
        if current_ts > 0:
            elapsed = int(_time.time()) - current_ts + initial
            minutes = elapsed // 60
            seconds = elapsed % 60
            current_time = f"{minutes}:{seconds:02d}"

    # Campeonato
    tournament = event.get("tournament", {})
    unique_tournament = tournament.get("uniqueTournament", event.get("season", {}).get("name", ""))
    if isinstance(unique_tournament, dict):
        championship = unique_tournament.get("name", tournament.get("name", ""))
    else:
        championship = tournament.get("name", "")

    # Estádio
    venue = event.get("venue", {})
    stadium = ""
    if isinstance(venue, dict):
        stadium = venue.get("stadium", {}).get("name", "") if isinstance(venue.get("stadium"), dict) else venue.get("name", "")

    # 2. Incidentes
    events = []
    incidents_raw = []
    try:
        incidents_data = _api_get(f"/event/{event_id}/incidents")
        incidents_raw = incidents_data.get("incidents", [])
        for incident in incidents_raw:
            ev = _incident_to_event(incident, home_team, away_team)
            if ev is not None:
                ev._raw_id = _incident_unique_key(incident)  # type: ignore
                events.append(ev)
    except requests.exceptions.HTTPError as e:
        if e.response is not None and e.response.status_code == 404:
            logger.warning("Incidentes não disponíveis para event_id=%d", event_id)
        else:
            raise

    # 3. Estatísticas
    statistics = None
    try:
        stats_data = _api_get(f"/event/{event_id}/statistics")
        statistics = _parse_statistics(stats_data)
    except requests.exceptions.HTTPError:
        logger.debug("Estatísticas não disponíveis para event_id=%d", event_id)

    # Stream info (indicar que é Sofascore — sem SSE nativo)
    stream_info = StreamInfo(
        transmission_id=str(event_id),
        stream_channel="",  # sem SSE nativo
        push_stream_host="",
        status=status_type.upper() if status_type else "",
        timer_status="INICIADO" if status_type == "inprogress" else "PARADO",
    )

    # Montar MatchData
    match = MatchData(
        home_team=home_team,
        away_team=away_team,
        home_score=home_score,
        away_score=away_score,
        period=period_label,
        period_id=period_id,
        current_time=current_time,
        championship=championship,
        stadium=stadium,
        stream_info=stream_info,
        events=events,
        statistics=statistics,
    )

    return match, incidents_raw


# ─── Listagem de jogos do dia ──────────────────────────────────────


def _scheduled_event_to_dict(ev: dict) -> dict:
    """Converte um evento do endpoint scheduled-events num resumo enxuto."""
    home = ev.get("homeTeam", {}) or {}
    away = ev.get("awayTeam", {}) or {}
    tournament = ev.get("tournament", {}) or {}
    category = tournament.get("category", {}) or {}
    unique = tournament.get("uniqueTournament", {}) or {}
    status = ev.get("status", {}) or {}

    ts = ev.get("startTimestamp", 0) or 0
    start_dt = datetime.fromtimestamp(ts, BR_TZ) if ts else None

    event_id = ev.get("id", 0)

    tournament_name = ""
    if isinstance(unique, dict) and unique.get("name"):
        tournament_name = unique.get("name", "")
    else:
        tournament_name = tournament.get("name", "")

    home_score = ev.get("homeScore", {}) or {}
    away_score = ev.get("awayScore", {}) or {}

    return OrderedDict([
        ("event_id", event_id),
        # URL pronta para ser passada ao /monitorar (o extrator usa o #id:)
        ("url", f"https://www.sofascore.com/match/{event_id}#id:{event_id}"),
        ("start_timestamp", ts),
        ("start_time", start_dt.strftime("%H:%M") if start_dt else ""),
        ("date", start_dt.strftime("%Y-%m-%d") if start_dt else ""),
        ("home_team", home.get("name", "")),
        ("away_team", away.get("name", "")),
        ("home_id", home.get("id", 0)),
        ("away_id", away.get("id", 0)),
        ("tournament", tournament_name),
        ("country", category.get("name", "")),
        ("status", status.get("type", "")),
        ("status_description", status.get("description", "")),
        ("home_score", home_score.get("current") if isinstance(home_score, dict) else None),
        ("away_score", away_score.get("current") if isinstance(away_score, dict) else None),
    ])


def fetch_scheduled_events(target_date: str | None = None) -> list[dict]:
    """
    Busca todos os jogos de futebol agendados para uma data (YYYY-MM-DD),
    ordenados por horário de início. Se ``target_date`` for None, usa hoje
    no fuso horário do Brasil.
    """
    if not target_date:
        target_date = datetime.now(BR_TZ).strftime("%Y-%m-%d")

    data = _api_get(f"/sport/football/scheduled-events/{target_date}")
    raw_events = data.get("events", []) or []

    games: list[dict] = []
    for ev in raw_events:
        try:
            games.append(_scheduled_event_to_dict(ev))
        except Exception:
            logger.debug("Falha ao converter evento agendado", exc_info=True)

    games.sort(key=lambda g: g["start_timestamp"])
    return games


def fetch_sofascore_incidents(event_id: int) -> list[dict]:
    """Busca apenas os incidentes de uma partida."""
    data = _api_get(f"/event/{event_id}/incidents")
    return data.get("incidents", [])


def fetch_sofascore_event(event_id: int) -> dict:
    """Busca apenas os dados gerais de uma partida."""
    data = _api_get(f"/event/{event_id}")
    return data.get("event", data)
