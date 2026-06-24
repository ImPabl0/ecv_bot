"""
scraper.py — Módulo de web scraping do ge.globo.com

Extrai dados de um jogo de futebol a partir da URL da página de tempo-real do GE:
  - Placar
  - Times (nome, abreviação, escudo)
  - Período / tempo atual
  - Lista completa de eventos (plays) com tipo, minuto, título, descrição, jogadores
"""

import re
import json
import time
from collections import OrderedDict
from dataclasses import dataclass, field, asdict
from urllib.parse import urlparse, urlencode, urlunparse, parse_qs

import requests
from requests.adapters import HTTPAdapter
from bs4 import BeautifulSoup

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/131.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
    "Cache-Control": "no-cache, no-store, must-revalidate, max-age=0",
    "Pragma": "no-cache",
    "Expires": "0",
    "If-None-Match": "",
    "If-Modified-Since": "",
}


class _NoCacheAdapter(HTTPAdapter):
    """Adapter que não utiliza cache algum."""

    def send(self, request, **kwargs):
        kwargs["stream"] = kwargs.get("stream", False)
        return super().send(request, **kwargs)


# ─── Helpers ────────────────────────────────────────────────────────


def _cache_bust_url(url: str) -> str:
    """Adiciona um parâmetro _t=<timestamp> na query string para invalidar cache de CDN."""
    parsed = urlparse(url)
    qs = parse_qs(parsed.query)
    qs["_t"] = [str(int(time.time() * 1000))]
    new_query = urlencode(qs, doseq=True)
    return urlunparse(parsed._replace(query=new_query))


def _fetch(url: str) -> str:
    """Faz GET sem qualquer cache para garantir dados atualizados."""
    busted_url = _cache_bust_url(url)
    session = requests.Session()
    session.mount("https://", _NoCacheAdapter())
    session.mount("http://", _NoCacheAdapter())
    session.headers.update(HEADERS)
    resp = session.get(busted_url, timeout=30)
    resp.raise_for_status()
    return resp.text


def _find_balanced(text: str, start: int) -> int | None:
    """Retorna o índice do fecha-chave/fecha-colchete que casa com text[start]."""
    opener = text[start]
    closer = "}" if opener == "{" else "]"
    depth = 0
    in_str = False
    esc = False
    for i in range(start, len(text)):
        c = text[i]
        if esc:
            esc = False
            continue
        if c == "\\" and in_str:
            esc = True
            continue
        if c == '"':
            in_str = not in_str
            continue
        if in_str:
            continue
        if c == opener:
            depth += 1
        elif c == closer:
            depth -= 1
            if depth == 0:
                return i
    return None


def _extract_json_block(text: str, key_pattern: str) -> dict | list | None:
    """Encontra `"key": {…}` ou `"key": […]` no texto e retorna o JSON parseado."""
    m = re.search(key_pattern, text)
    if not m:
        return None
    # posição do { ou [
    for i in range(m.end(), min(m.end() + 20, len(text))):
        if text[i] in ("{", "["):
            end = _find_balanced(text, i)
            if end is not None:
                try:
                    return json.loads(text[i: end + 1])
                except json.JSONDecodeError:
                    return None
            break
    return None


# ─── Modelos ────────────────────────────────────────────────────────


@dataclass
class Team:
    id: int = 0
    name: str = ""
    abbreviation: str = ""
    badge_png: str = ""
    badge_svg: str = ""

    @classmethod
    def from_raw(cls, raw: dict) -> "Team":
        return cls(
            id=raw.get("id", 0),
            name=raw.get("popularName") or raw.get("name", ""),
            abbreviation=raw.get("abbreviation", ""),
            badge_png=raw.get("badgePng", ""),
            badge_svg=raw.get("badgeSvg", ""),
        )


@dataclass
class Event:
    """Um evento / lance do jogo."""
    minute: str = ""
    period: str = ""
    period_id: str = ""
    type: str = ""           # GOAL, SUBSTITUTION, CARD, IMPORTANT, NORMAL, SUMMARY_AUTOMATIC …
    type_label: str = ""     # Gol, Substituição, Cartão amarelo …
    title: str = ""          # Título resumido do lance
    description: str = ""    # Texto narrativo do lance
    team: str = ""           # Abreviação do time (GIR / BAR)
    team_id: int = 0
    player: str = ""
    player_in: str = ""      # Substituição: quem entrou
    player_out: str = ""     # Substituição: quem saiu
    goal_kind: str = ""      # REGULAR_GOAL, PENALTY_GOAL, OWN_GOAL
    card_type: str = ""      # YELLOW, RED, SECOND_YELLOW


@dataclass
class StreamInfo:
    """Metadados do PushStream para conexão SSE em tempo real."""
    transmission_id: str = ""
    stream_channel: str = ""
    push_stream_host: str = "stream.push.globo.com"
    status: str = ""            # REAL_TIME, ENDED, etc.
    timer_status: str = ""     # INICIADO, PARADO, etc.


@dataclass
class TeamStatistics:
    """Estatísticas de um time durante a partida."""
    wrong_finish: int = 0
    goal_finish: int = 0
    ball_out_finish: int = 0
    ball_on_the_post: int = 0
    blocked_finish: int = 0
    penalty_received: int = 0
    corner_kick: int = 0
    off_side: int = 0
    defense: int = 0
    tackle: int = 0
    foul_made: int = 0
    yellow_card_received: int = 0
    red_card_received: int = 0
    ball_possession: int = 0
    right_passes: int = 0
    total_passes: int = 0
    wrong_passes: int = 0

    @classmethod
    def from_raw(cls, raw: dict) -> "TeamStatistics":
        def _val(key: str) -> int:
            v = raw.get(key, {})
            if isinstance(v, dict):
                return v.get("total", 0)
            return int(v) if v else 0
        return cls(
            wrong_finish=_val("wrongFinish"),
            goal_finish=_val("goalFinish"),
            ball_out_finish=_val("ballOutFinish"),
            ball_on_the_post=_val("ballOnThePost"),
            blocked_finish=_val("blockedFinish"),
            penalty_received=_val("penaltyReceived"),
            corner_kick=_val("cornerKick"),
            off_side=_val("offSide"),
            defense=_val("defense"),
            tackle=_val("tackle"),
            foul_made=_val("foulMade"),
            yellow_card_received=_val("yellowCardReceived"),
            red_card_received=_val("redCardReceived"),
            ball_possession=_val("ballPossession"),
            right_passes=_val("rightPasses"),
            total_passes=_val("totalPasses"),
            wrong_passes=_val("wrongPasses"),
        )


@dataclass
class MatchStatistics:
    """Estatísticas completas da partida (ambos os times)."""
    home_team: TeamStatistics = field(default_factory=TeamStatistics)
    away_team: TeamStatistics = field(default_factory=TeamStatistics)
    last_updated: str = ""

    @classmethod
    def from_raw(cls, data: dict, timestamp: str = "") -> "MatchStatistics":
        home_raw = data.get("homeTeam", {})
        away_raw = data.get("awayTeam", {})
        return cls(
            home_team=TeamStatistics.from_raw(home_raw) if isinstance(
                home_raw, dict) else TeamStatistics(),
            away_team=TeamStatistics.from_raw(away_raw) if isinstance(
                away_raw, dict) else TeamStatistics(),
            last_updated=timestamp,
        )


@dataclass
class MatchData:
    home_team: Team = field(default_factory=Team)
    away_team: Team = field(default_factory=Team)
    home_score: int = 0
    away_score: int = 0
    period: str = ""          # "1º tempo", "2º tempo", "Intervalo", "Encerrado" …
    period_id: str = ""       # PRIMEIRO_TEMPO, SEGUNDO_TEMPO, INTERVALO, POS_JOGO …
    current_time: str = ""    # "28:20"
    championship: str = ""
    stadium: str = ""
    stream_info: StreamInfo = field(default_factory=StreamInfo)
    events: list[Event] = field(default_factory=list)
    statistics: MatchStatistics | None = None

    def to_dict(self) -> dict:
        """Retorna dict ordenado: detalhes da partida primeiro, eventos por último."""
        raw = asdict(self)
        si = raw.get("stream_info", {})
        sse_url = ""
        if si.get("push_stream_host") and si.get("stream_channel"):
            sse_url = f"https://{si['push_stream_host']}/ev/{si['stream_channel']}"
        return OrderedDict([
            ("home_team", raw["home_team"]),
            ("away_team", raw["away_team"]),
            ("home_score", raw["home_score"]),
            ("away_score", raw["away_score"]),
            ("period", raw["period"]),
            ("period_id", raw["period_id"]),
            ("current_time", raw["current_time"]),
            ("championship", raw["championship"]),
            ("stadium", raw["stadium"]),
            ("sse_url", sse_url),
            ("events", raw["events"]),
            ("statistics", raw.get("statistics")),
        ])

    def update_from_play(self, play_data: dict) -> None:
        """Aplica uma atualização de play (create/update/delete) vinda do SSE."""
        play_id = play_data.get("id", "")
        if not play_id:
            return

        # Atualizar período se vier no play
        per = play_data.get("period", {})
        if isinstance(per, dict) and per:
            self.period = per.get("label", self.period)
            self.period_id = per.get("id", self.period_id)

        new_event = _parse_event(play_data)

        # Verificar se já existe (update) ou é novo (create)
        for i, ev in enumerate(self.events):
            # Comparar por minuto + título + tipo (play_id não é armazenado)
            if hasattr(ev, '_raw_id') and ev._raw_id == play_id:
                self.events[i] = new_event
                return

        # Novo evento — inserir na posição correta (cronológica)
        new_event._raw_id = play_id  # type: ignore
        self.events.append(new_event)

    def update_transmission(self, data: dict, modified_fields: list = None) -> None:
        """Aplica uma atualização de transmissão vinda do SSE."""
        if "period" in data:
            per = data["period"]
            if isinstance(per, dict):
                self.period = per.get("label", self.period)
                self.period_id = per.get("id", self.period_id)

        if "currentTime" in data:
            self.current_time = data["currentTime"]

        if "timerStatus" in data:
            self.stream_info.timer_status = data["timerStatus"]

        match_obj = data.get("match", {})
        if isinstance(match_obj, dict) and match_obj:
            sb = match_obj.get("scoreboard", {})
            if isinstance(sb, dict) and sb:
                if "home" in sb:
                    self.home_score = sb["home"]
                if "away" in sb:
                    self.away_score = sb["away"]


# ─── Parser principal ───────────────────────────────────────────────


def _parse_event(raw: dict) -> Event:
    """Converte um objeto de play cru em Event."""
    # Período
    per = raw.get("period", {}) or {}
    period_label = per.get("label", "") if isinstance(per, dict) else str(per)
    period_id = per.get("id", "") if isinstance(per, dict) else ""

    # Minuto
    moment = raw.get("moment", "")  # "16:05"
    minute = moment.split(":")[0] if moment else ""

    # Tipo
    pt = raw.get("playType", {}) or {}
    type_id = pt.get("id", "") if isinstance(pt, dict) else str(pt)
    type_label = pt.get("label", "") if isinstance(pt, dict) else str(pt)

    # Título
    title = raw.get("title", "") or ""

    # Corpo / descrição narrativa
    body = raw.get("body", {}) or {}
    blocks = body.get("blocks", []) if isinstance(body, dict) else []
    description = ""
    if blocks and isinstance(blocks, list):
        texts = []
        for block in blocks:
            if isinstance(block, dict):
                t = block.get("text", "")
                if t:
                    texts.append(t)
        description = "\n".join(texts)

    # Detalhes
    details = raw.get("details", {}) or {}

    # Time
    team_info = details.get("team", {}) or {}
    team_abbr = team_info.get("abbreviation", "") if isinstance(
        team_info, dict) else ""
    team_id = team_info.get("id", 0) if isinstance(team_info, dict) else 0

    # Jogador (gol / cartão)
    athlete = details.get("athlete", {}) or {}
    player_name = athlete.get(
        "popularName", "") if isinstance(athlete, dict) else ""

    # Substituição
    coming = details.get("comingIn", {}) or {}
    leaving = details.get("leaving", {}) or {}
    player_in = coming.get("popularName", "") if isinstance(
        coming, dict) else ""
    player_out = leaving.get("popularName", "") if isinstance(
        leaving, dict) else ""

    # Tipo de gol
    goal_kind = details.get("kind", "") or ""

    # Tipo de cartão
    card_type = details.get("cardType", "") or details.get("card", "") or ""

    return Event(
        minute=minute,
        period=period_label,
        period_id=period_id,
        type=type_id,
        type_label=type_label,
        title=title,
        description=description,
        team=team_abbr,
        team_id=team_id,
        player=player_name,
        player_in=player_in,
        player_out=player_out,
        goal_kind=goal_kind,
        card_type=card_type,
    )


def _extract_match_obj(big_script: str) -> dict | None:
    """
    Extrai o objeto `match` de dentro de `window.trv2 = { transmission: { ... match: {...} ... } }`.
    Esse objeto contém homeTeam, awayTeam, scoreboard, location, etc.
    """
    # O objeto match está logo após '"match":' e é o primeiro {…} grande
    m = re.search(r'transmission\s*:\s*\{', big_script)
    if not m:
        return None

    # Dentro de transmission, procurar "match":
    match_m = re.search(r'"match"\s*:\s*\{', big_script[m.start():])
    if not match_m:
        return None

    abs_pos = m.start() + match_m.start()
    brace = big_script.index(
        "{", abs_pos + match_m.end() - match_m.start() - 1)
    end = _find_balanced(big_script, brace)
    if end is None:
        return None
    try:
        return json.loads(big_script[brace: end + 1])
    except json.JSONDecodeError:
        return None


def _enclosing_object(text: str, pos: int, maxspan: int = 40000) -> str | None:
    """
    Retorna o objeto JSON `{...}` que contém a posição ``pos``, procurando
    o `{` de abertura mais próximo para trás cujo fechamento balanceado
    englobe ``pos``.
    """
    for s in range(pos, max(0, pos - maxspan), -1):
        if text[s] == "{":
            end = _find_balanced(text, s)
            if end is not None and s <= pos <= end:
                return text[s: end + 1]
    return None


def fetch_ge_agenda(only_upcoming: bool = True) -> list[dict]:
    """
    Lê a agenda do ge.globo.com (https://ge.globo.com/agenda/) e retorna a
    lista de jogos de futebol que possuem página de tempo-real, ordenados
    por data e horário.

    Cada item contém time mandante/visitante, campeonato, data, horário,
    situação e a ``url`` da página tempo-real (pronta para o /monitorar).

    Se ``only_upcoming`` for True, exclui jogos já encerrados (moment PAST).
    """
    html = _fetch("https://ge.globo.com/agenda/")

    games: list[dict] = []
    seen: set = set()

    for m in re.finditer(r'"transmission":\s*\{\s*"__typename":\s*"TRTransmission"', html):
        obj_text = _enclosing_object(html, m.start())
        if not obj_text:
            continue
        try:
            obj = json.loads(obj_text)
        except json.JSONDecodeError:
            continue

        if "firstContestant" not in obj or "secondContestant" not in obj:
            continue

        tr = obj.get("transmission") or {}
        url = tr.get("url", "") or ""
        # Apenas jogos de futebol com página de tempo-real
        if not url or "/jogo/" not in url or "/futebol/" not in url:
            continue

        game_id = obj.get("id")
        if game_id in seen:
            continue
        seen.add(game_id)

        moment = obj.get("moment", "")
        if only_upcoming and moment == "PAST":
            continue

        home = obj.get("firstContestant") or {}
        away = obj.get("secondContestant") or {}
        phase = obj.get("phase") or {}
        ce = phase.get("championshipEdition") or {}
        championship = (ce.get("championship") or {}).get("name", "") or phase.get("name", "")
        scoreboard = obj.get("scoreboard") or {}
        bstatus = tr.get("broadcastStatus") or {}

        start_date = obj.get("startDate", "") or ""        # YYYY-MM-DD
        start_hour = (obj.get("startHour", "") or "")[:5]   # HH:MM

        games.append(OrderedDict([
            ("url", url),
            ("home_team", home.get("popularName") or home.get("name", "")),
            ("away_team", away.get("popularName") or away.get("name", "")),
            ("home_badge", home.get("badgePng", "")),
            ("away_badge", away.get("badgePng", "")),
            ("championship", championship),
            ("date", start_date),
            ("start_time", start_hour),
            ("moment", moment),                                  # PAST, NOW, FUTURE
            ("status", bstatus.get("id", "")),
            ("status_label", bstatus.get("label", "")),
            ("home_score", scoreboard.get("home")),
            ("away_score", scoreboard.get("away")),
        ]))

    games.sort(key=lambda g: (g["date"] or "", g["start_time"] or ""))
    return games


def scrape_match(url: str) -> MatchData:
    """
    Faz o scraping completo de uma página de jogo do ge.globo.com.
    Retorna um MatchData com todos os dados estruturados.
    """
    html = _fetch(url)
    soup = BeautifulSoup(html, "html.parser")

    match = MatchData()

    # ── Encontrar o script principal (window.trv2) ──────────────────
    big_script = ""
    for script in soup.find_all("script"):
        text = script.string or ""
        if "window.trv2" in text or ("plays" in text and len(text) > 20000):
            big_script = text
            break

    if not big_script:
        return match

    # ── Extrair metadados de streaming ────────────────────────────────
    stream = StreamInfo()
    tx_id_m = re.search(r'"id"\s*:\s*"([0-9a-f-]{36})"', big_script)
    if tx_id_m:
        stream.transmission_id = tx_id_m.group(1)
    ch_m = re.search(r'"streamChannelName"\s*:\s*"([^"]+)"', big_script)
    if ch_m:
        stream.stream_channel = ch_m.group(1)
    status_m = re.search(r'"status"\s*:\s*"([^"]+)"', big_script)
    if status_m:
        stream.status = status_m.group(1)
    timer_m = re.search(r'"timerStatus"\s*:\s*"([^"]+)"', big_script)
    if timer_m:
        stream.timer_status = timer_m.group(1)

    # Host do PushStream (pode variar por env)
    push_host_m = re.search(r'pushStreamHost\s*=\s*["\']([^"\']+)', html)
    if push_host_m:
        stream.push_stream_host = push_host_m.group(1)

    match.stream_info = stream

    # ── Extrair o objeto match de transmission ──────────────────────
    match_obj = _extract_match_obj(big_script)

    if match_obj:
        # Times
        home_raw = match_obj.get("homeTeam", {})
        away_raw = match_obj.get("awayTeam", {})
        if isinstance(home_raw, dict) and home_raw:
            match.home_team = Team.from_raw(home_raw)
        if isinstance(away_raw, dict) and away_raw:
            match.away_team = Team.from_raw(away_raw)

        # Placar
        scoreboard = match_obj.get("scoreboard", {})
        if isinstance(scoreboard, dict):
            match.home_score = scoreboard.get("home", 0)
            match.away_score = scoreboard.get("away", 0)

        # Fallback: detailedScoreboard
        if match.home_score == 0 and match.away_score == 0:
            ds = match_obj.get("detailedScoreboard", {})
            if isinstance(ds, dict):
                match.home_score = ds.get("firstParticipantScore", 0)
                match.away_score = ds.get("secondParticipantScore", 0)

        # Campeonato
        phase = match_obj.get("phase", {})
        if isinstance(phase, dict):
            ce = phase.get("championshipEdition", {})
            if isinstance(ce, dict):
                ch = ce.get("championship", {})
                match.championship = ce.get("name", "") or (
                    ch.get("name", "") if isinstance(ch, dict) else "")

        # Estádio
        loc = match_obj.get("location", {})
        if isinstance(loc, dict):
            match.stadium = loc.get("popularName") or loc.get("name", "")

    # ── Período / tempo atual ───────────────────────────────────────
    ct_match = re.search(r'"currentTime"\s*:\s*"([^"]*)"', big_script)
    if ct_match:
        match.current_time = ct_match.group(1)

    # Período: pegar o primeiro "period" que aparece antes dos plays
    plays_pos = re.search(r"plays\s*:", big_script)
    if plays_pos:
        before = big_script[: plays_pos.start()]
        period_m = re.search(
            r'"period"\s*:\s*\{[^}]*"id"\s*:\s*"([^"]+)"[^}]*"label"\s*:\s*"([^"]+)"',
            before,
        )
        if not period_m:
            period_m = re.search(
                r'"period"\s*:\s*\{[^}]*"label"\s*:\s*"([^"]+)"[^}]*"id"\s*:\s*"([^"]+)"',
                before,
            )
            if period_m:
                match.period = period_m.group(1)
                match.period_id = period_m.group(2)
        else:
            match.period_id = period_m.group(1)
            match.period = period_m.group(2)

    # ── Eventos (plays) ─────────────────────────────────────────────
    m = re.search(r"plays\s*:\s*Array\.from\(\s*\[", big_script)
    if m:
        arr_start = big_script.index("[", m.start())
        arr_end = _find_balanced(big_script, arr_start)
        if arr_end:
            try:
                raw_plays = json.loads(big_script[arr_start: arr_end + 1])
                # Vem do mais recente → mais antigo; inverter para ordem cronológica
                raw_plays.reverse()
                match.events = [_parse_event(p) for p in raw_plays]
            except json.JSONDecodeError:
                pass

    return match
