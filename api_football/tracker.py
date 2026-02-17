"""
tracker.py — Gerenciador de partidas monitoradas em tempo real via SSE.

Conecta ao PushStream do ge.globo.com via Server-Sent Events para receber
atualizações instantâneas (novos lances, placar, período, etc.).

Fluxo:
  1. Faz scraping inicial da página para carregar todos os dados
  2. Extrai o streamChannelName do HTML
  3. Abre conexão SSE com stream.push.globo.com/ev/{channel}
  4. Processa mensagens em tempo real, atualizando o MatchData em memória
"""

import json
import threading
import logging
import queue
import time as _time
from dataclasses import dataclass, field, asdict
from datetime import datetime

import requests

from scraper import scrape_match, MatchData, Event, MatchStatistics, _parse_event

logger = logging.getLogger(__name__)

SSE_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/131.0.0.0 Safari/537.36"
    ),
    "Accept": "text/event-stream",
    "Referer": "https://ge.globo.com/",
    "Origin": "https://ge.globo.com",
    "Cache-Control": "no-cache",
}


# ─── SSE Client ─────────────────────────────────────────────────────


def _iter_sse_messages(response):
    """
    Itera sobre mensagens SSE de um response com stream=True.
    Cada yield retorna o conteúdo do campo 'data:' já parseado como dict.
    """
    buffer = b""
    for chunk in response.iter_content(chunk_size=2048):
        if not chunk:
            continue
        buffer += chunk

        while b"\n\n" in buffer:
            msg_raw, buffer = buffer.split(b"\n\n", 1)
            lines = msg_raw.decode("utf-8", errors="replace").split("\n")

            for line in lines:
                if line.startswith("data: "):
                    data_str = line[6:]
                    try:
                        yield json.loads(data_str)
                    except json.JSONDecodeError:
                        pass


# ─── MatchTracker ────────────────────────────────────────────────────


@dataclass
class MatchTracker:
    """Rastreia uma partida individual via SSE em tempo real."""
    url: str
    data: MatchData = field(default_factory=MatchData)
    last_updated: datetime | None = None
    last_error: str | None = None
    event_count: int = 0
    update_count: int = 0
    sse_connected: bool = False
    _play_ids: set = field(default_factory=set, repr=False)
    _thread: threading.Thread | None = field(default=None, repr=False)
    _stop_event: threading.Event = field(
        default_factory=threading.Event, repr=False)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)
    _started: bool = False
    _subscribers: list = field(default_factory=list, repr=False)
    _sub_lock: threading.Lock = field(
        default_factory=threading.Lock, repr=False)
    # Timer client-side (replica o comportamento do ge.globo.com)
    _timer_base_seconds: int = field(default=0, repr=False)
    _timer_start_realtime: float = field(default=0.0, repr=False)
    _timer_running: bool = field(default=False, repr=False)

    def subscribe(self) -> queue.Queue:
        """Registra um subscriber e retorna a queue de eventos SSE."""
        q = queue.Queue(maxsize=500)
        with self._sub_lock:
            self._subscribers.append(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        """Remove um subscriber."""
        with self._sub_lock:
            try:
                self._subscribers.remove(q)
            except ValueError:
                pass

    def _publish(self, event_type: str, data: dict) -> None:
        """Publica um evento SSE para todos os subscribers."""
        msg = {"event": event_type, "data": data}
        with self._sub_lock:
            dead = []
            for q in self._subscribers:
                try:
                    q.put_nowait(msg)
                except queue.Full:
                    dead.append(q)
            for q in dead:
                self._subscribers.remove(q)

    def start(self) -> None:
        """Faz o scraping inicial e conecta ao SSE."""
        if self._started:
            return
        self._stop_event.clear()
        self._started = True

        # Scraping inicial (síncrono)
        self._initial_scrape()

        # Indexar IDs dos plays iniciais
        with self._lock:
            for ev in self.data.events:
                raw_id = getattr(ev, '_raw_id', None)
                if raw_id:
                    self._play_ids.add(raw_id)

        # Thread de SSE
        self._thread = threading.Thread(
            target=self._sse_loop,
            name=f"sse-{self.url[:60]}",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        """Para o SSE e encerra a thread."""
        if not self._started:
            return
        self._stop_event.set()
        self._started = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=5)
        self._thread = None
        self.sse_connected = False
        logger.info("Tracker parado: %s", self.url)

    @property
    def is_running(self) -> bool:
        return self._started and not self._stop_event.is_set()

    def get_data(self) -> MatchData:
        """Retorna os dados atuais (thread-safe) com currentTime calculado."""
        with self._lock:
            # Atualizar currentTime dinamicamente se o timer estiver rodando
            if self._timer_running and self._timer_start_realtime > 0:
                elapsed = _time.time() - self._timer_start_realtime
                total_seconds = self._timer_base_seconds + int(elapsed)
                minutes = total_seconds // 60
                seconds = total_seconds % 60
                self.data.current_time = f"{minutes}:{seconds:02d}"
            return self.data

    def _sync_timer(self, current_time_str: str = None, timer_status: str = None) -> None:
        """Sincroniza o timer client-side com os dados do SSE."""
        if current_time_str:
            parts = current_time_str.split(":")
            if len(parts) >= 2:
                try:
                    self._timer_base_seconds = int(parts[0]) * 60 + int(parts[1])
                except ValueError:
                    pass
        if timer_status == "INICIADO":
            self._timer_running = True
            self._timer_start_realtime = _time.time()
        elif timer_status == "PARADO":
            # Congelar o tempo atual antes de parar
            if self._timer_running and self._timer_start_realtime > 0:
                elapsed = _time.time() - self._timer_start_realtime
                self._timer_base_seconds += int(elapsed)
            self._timer_running = False
            self._timer_start_realtime = 0.0

    def _initial_scrape(self) -> None:
        """Faz o scraping completo da página para obter o estado inicial."""
        try:
            data = scrape_match(self.url)
            with self._lock:
                self.data = data
                self.event_count = len(data.events)
                self.update_count = 1
                self.last_updated = datetime.now()
                self.last_error = None

            logger.info(
                "Scraping inicial — %s: %s %d x %d %s | %d eventos | %s | channel=%s",
                self._match_label(),
                data.home_team.abbreviation,
                data.home_score,
                data.away_score,
                data.away_team.abbreviation,
                len(data.events),
                data.period,
                data.stream_info.stream_channel or "N/A",
            )
            # Sincronizar timer com estado inicial
            self._sync_timer(
                current_time_str=data.current_time,
                timer_status=data.stream_info.timer_status,
            )
        except Exception as e:
            with self._lock:
                self.last_error = str(e)
            logger.error("Erro no scraping inicial: %s", e)

    def _sse_loop(self) -> None:
        """Loop principal de conexão SSE com reconexão automática."""
        while not self._stop_event.is_set():
            channel = ""
            host = ""
            with self._lock:
                si = self.data.stream_info
                channel = si.stream_channel
                host = si.push_stream_host

            if not channel:
                logger.warning("Sem streamChannelName — fallback para polling")
                self._fallback_poll()
                return

            sse_url = f"https://{host}/ev/{channel}"
            logger.info("Conectando SSE: %s", sse_url)

            try:
                resp = requests.get(
                    sse_url,
                    headers=SSE_HEADERS,
                    timeout=(10, None),  # 10s connect, sem read timeout
                    stream=True,
                )
                resp.raise_for_status()

                self.sse_connected = True
                logger.info("SSE conectado — %s", self._match_label())

                for msg in _iter_sse_messages(resp):
                    if self._stop_event.is_set():
                        break
                    self._process_sse_message(msg)

            except requests.exceptions.ConnectionError as e:
                logger.warning(
                    "SSE desconectou: %s — reconectando em 5s", str(e)[:100])
            except requests.exceptions.ReadTimeout:
                logger.info("SSE timeout — reconectando")
            except Exception as e:
                logger.error("Erro SSE: %s — reconectando em 5s", e)
            finally:
                self.sse_connected = False

            # Aguardar antes de reconectar
            if not self._stop_event.is_set():
                self._stop_event.wait(5)

    def _process_sse_message(self, msg: dict) -> None:
        """Processa uma mensagem SSE do PushStream."""
        text = msg.get("text")

        # Ping / keepalive
        if text == "ping" or text is None:
            return

        if not isinstance(text, dict):
            return

        data = text.get("data", {})
        if not isinstance(data, dict) or not data:
            return

        method = text.get("method", "create")
        modified_fields = text.get("modifiedFields", [])

        with self._lock:
            if "playType" in data:
                # Lance (play)
                self._handle_play(data, method)
            elif "period" in data or "currentTime" in data or "match" in data or "timerStatus" in data:
                # Transmissão (período/placar/tempo)
                old_period_id = self.data.period_id
                old_home = self.data.home_score
                old_away = self.data.away_score
                self.data.update_transmission(data, modified_fields)
                # Sincronizar timer
                timer_status = data.get("timerStatus", None)
                current_time = data.get("currentTime", None)
                if current_time or timer_status:
                    self._sync_timer(current_time, timer_status)
                self._log_update("transmission")
                # Publicar mudanças relevantes
                if self.data.period_id != old_period_id:
                    self._publish("period_change", {
                        "old_period_id": old_period_id,
                        "new_period_id": self.data.period_id,
                        "period": self.data.period,
                        "home_score": self.data.home_score,
                        "away_score": self.data.away_score,
                        "home_team": self.data.home_team.name,
                        "away_team": self.data.away_team.name,
                        "home_abbreviation": self.data.home_team.abbreviation,
                        "away_abbreviation": self.data.away_team.abbreviation,
                        "championship": self.data.championship,
                    })
                if self.data.home_score != old_home or self.data.away_score != old_away:
                    self._publish("score_change", {
                        "home_score": self.data.home_score,
                        "away_score": self.data.away_score,
                        "home_abbreviation": self.data.home_team.abbreviation,
                        "away_abbreviation": self.data.away_team.abbreviation,
                    })
            elif modified_fields:
                # Update parcial
                self.data.update_transmission(data, modified_fields)
                self._log_update("transmission-partial")

        data_type = text.get("dataType", "")
        if data_type == "statistics":
            with self._lock:
                timestamp = msg.get("time", "")
                stats = MatchStatistics.from_raw(data, timestamp)
                self.data.statistics = stats
                self._log_update("statistics")
                self._publish("statistics", {
                    "home_team_name": self.data.home_team.name,
                    "away_team_name": self.data.away_team.name,
                    "home_team": asdict(stats.home_team),
                    "away_team": asdict(stats.away_team),
                    "last_updated": timestamp,
                })

    def _handle_play(self, play_data: dict, method: str) -> None:
        """Processa um evento de play (create/update/delete)."""
        play_id = play_data.get("id", "")

        if method == "delete" and play_id:
            self.data.events = [
                ev for ev in self.data.events
                if getattr(ev, '_raw_id', None) != play_id
            ]
            self._play_ids.discard(play_id)
            self.event_count = len(self.data.events)
            self._log_update("play-delete")
            return

        # create ou update
        new_event = _parse_event(play_data)
        new_event._raw_id = play_id  # type: ignore

        # Atualizar período se vier no play
        per = play_data.get("period", {})
        if isinstance(per, dict) and per:
            self.data.period = per.get("label", self.data.period)
            self.data.period_id = per.get("id", self.data.period_id)

        if play_id and play_id in self._play_ids:
            # Update: substituir o play existente
            for i, ev in enumerate(self.data.events):
                if getattr(ev, '_raw_id', None) == play_id:
                    self.data.events[i] = new_event
                    break
            self._log_update(f"play-update [{new_event.type}]")
            # Publicar update para subscribers
            self._publish("play_update", asdict(new_event))
        else:
            # Create: novo play
            if play_id:
                self._play_ids.add(play_id)
            self.data.events.append(new_event)
            self.event_count = len(self.data.events)

            pt = play_data.get("playType", {})
            type_id = pt.get("id", "?") if isinstance(pt, dict) else "?"
            moment = play_data.get("moment", "")
            logger.info(
                "Novo lance — %s | %s' %s | Total: %d",
                self._match_label(),
                moment,
                type_id,
                len(self.data.events),
            )
            self.update_count += 1
            self.last_updated = datetime.now()
            # Publicar novo evento para subscribers
            self._publish("play_new", asdict(new_event))

    def _fallback_poll(self) -> None:
        """Fallback: polling HTTP caso SSE não esteja disponível."""
        logger.info("Fallback polling ativado para %s", self.url)
        while not self._stop_event.is_set():
            try:
                new_data = scrape_match(self.url)
                with self._lock:
                    old_count = len(self.data.events)
                    new_count = len(new_data.events)
                    if (new_data.home_score != self.data.home_score or
                        new_data.away_score != self.data.away_score or
                        new_data.period_id != self.data.period_id or
                            new_count != old_count):
                        self.data = new_data
                        self.event_count = new_count
                        self.update_count += 1
                        self.last_updated = datetime.now()
                        logger.info("Polling update — %s | %d eventos",
                                    self._match_label(), new_count)
                    self.last_error = None
            except Exception as e:
                with self._lock:
                    self.last_error = str(e)
                logger.error("Erro polling: %s", e)

            self._stop_event.wait(30)

    def _log_update(self, kind: str) -> None:
        """Log genérico de atualização."""
        self.update_count += 1
        self.last_updated = datetime.now()
        self.last_error = None
        logger.debug(
            "Update [%s] — %s: %s %d x %d %s | %s",
            kind,
            self._match_label(),
            self.data.home_team.abbreviation,
            self.data.home_score,
            self.data.away_score,
            self.data.away_team.abbreviation,
            self.data.period,
        )

    def _match_label(self) -> str:
        """Retorna label curto do jogo."""
        if self.data.home_team.name:
            return f"{self.data.home_team.abbreviation}x{self.data.away_team.abbreviation}"
        return self.url.split("/")[-1].replace(".ghtml", "")

    def status_dict(self) -> dict:
        """Retorna um resumo do status do tracker."""
        with self._lock:
            return {
                "url": self.url,
                "is_running": self.is_running,
                "sse_connected": self.sse_connected,
                "last_updated": self.last_updated.isoformat() if self.last_updated else None,
                "last_error": self.last_error,
                "update_count": self.update_count,
                "event_count": self.event_count,
                "period": self.data.period,
                "score": (f"{self.data.home_score} x {self.data.away_score}"
                          if self.data.home_team.name else None),
                "teams": (f"{self.data.home_team.abbreviation} x "
                          f"{self.data.away_team.abbreviation}"
                          if self.data.home_team.name else None),
            }


# ─── Manager ────────────────────────────────────────────────────────


class MatchTrackerManager:
    """
    Gerenciador global de partidas monitoradas.
    Mantém o mapa de URL → MatchTracker.
    """

    def __init__(self):
        self._trackers: dict[str, MatchTracker] = {}
        self._lock = threading.Lock()

    def _normalize_url(self, url: str) -> str:
        """Remove query params e trailing slashes para normalizar a chave."""
        return url.split("?")[0].rstrip("/")

    def get_or_create(self, url: str) -> MatchTracker:
        """Retorna o tracker da URL, criando e iniciando um novo se não existir."""
        key = self._normalize_url(url)
        with self._lock:
            if key not in self._trackers:
                tracker = MatchTracker(url=url)
                tracker.start()
                self._trackers[key] = tracker
                logger.info("Novo tracker criado: %s", key)
            return self._trackers[key]

    def get(self, url: str) -> MatchTracker | None:
        """Retorna o tracker da URL se existir, ou None."""
        key = self._normalize_url(url)
        with self._lock:
            return self._trackers.get(key)

    def remove(self, url: str) -> bool:
        """Para e remove o tracker da URL. Retorna True se existia."""
        key = self._normalize_url(url)
        with self._lock:
            tracker = self._trackers.pop(key, None)
        if tracker:
            tracker.stop()
            return True
        return False

    def list_all(self) -> list[dict]:
        """Retorna status de todos os trackers ativos."""
        with self._lock:
            trackers = list(self._trackers.values())
        return [t.status_dict() for t in trackers]

    def stop_all(self) -> None:
        """Para todos os trackers."""
        with self._lock:
            trackers = list(self._trackers.values())
            self._trackers.clear()
        for t in trackers:
            t.stop()
        logger.info("Todos os trackers foram parados.")
