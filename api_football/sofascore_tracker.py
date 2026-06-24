"""
sofascore_tracker.py — Tracker em tempo real para partidas do Sofascore.

Como o Sofascore não expõe WebSocket/SSE público, este módulo faz
polling inteligente com detecção de mudanças e converte os dados para
o mesmo formato de eventos SSE que o bot Java já consome.

Fluxo:
  1. Busca estado inicial via sofascore_scraper.fetch_sofascore_match()
  2. Faz polling a cada POLL_INTERVAL_LIVE (10s durante o jogo, 30s fora)
  3. Compara incidentes novos / removidos / placar / período
  4. Publica eventos (play_new, period_change, score_change, statistics)
     exatamente no formato esperado pelo MatchMonitor.java
"""

import threading
import logging
import queue
import time as _time
from dataclasses import dataclass, field, asdict
from datetime import datetime

from scraper import MatchData, Event, MatchStatistics

from sofascore_scraper import (
    extract_event_id,
    fetch_sofascore_match,
    fetch_sofascore_event,
    fetch_sofascore_incidents,
    _incident_to_event,
    _incident_unique_key,
    _parse_statistics,
    _api_get,
)

logger = logging.getLogger(__name__)

POLL_INTERVAL_LIVE = 10       # segundos entre polls durante jogo ao vivo
POLL_INTERVAL_IDLE = 30       # segundos entre polls quando não está ao vivo
POLL_INTERVAL_FINISHED = 120  # segundos entre polls depois de encerrado


# ─── SofascoreTracker ──────────────────────────────────────────────


@dataclass
class SofascoreTracker:
    """
    Rastreia uma partida individual do Sofascore via polling.
    Compatível com o MatchTracker do ge.globo.com (mesma interface pub/sub).
    """
    url: str
    event_id: int = 0
    data: MatchData = field(default_factory=MatchData)
    last_updated: datetime | None = None
    last_error: str | None = None
    event_count: int = 0
    update_count: int = 0
    sse_connected: bool = False  # Sempre False (não há SSE), mas mantido para compatibilidade

    # Estado interno
    _known_incidents: dict = field(default_factory=dict, repr=False)   # key → incident dict
    _thread: threading.Thread | None = field(default=None, repr=False)
    _stop_event: threading.Event = field(default_factory=threading.Event, repr=False)
    _lock: threading.Lock = field(default_factory=threading.Lock, repr=False)
    _started: bool = False
    _subscribers: list = field(default_factory=list, repr=False)
    _sub_lock: threading.Lock = field(default_factory=threading.Lock, repr=False)
    _timer_base_seconds: int = field(default=0, repr=False)
    _timer_start_realtime: float = field(default=0.0, repr=False)
    _timer_running: bool = field(default=False, repr=False)
    _finished_polls: int = field(default=0, repr=False)

    # ─── Interface pública (mesma do MatchTracker) ──────────────────

    def subscribe(self) -> queue.Queue:
        q = queue.Queue(maxsize=500)
        with self._sub_lock:
            self._subscribers.append(q)
        return q

    def unsubscribe(self, q: queue.Queue) -> None:
        with self._sub_lock:
            try:
                self._subscribers.remove(q)
            except ValueError:
                pass

    def _publish(self, event_type: str, data: dict) -> None:
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
        if self._started:
            return

        self.event_id = extract_event_id(self.url)
        if not self.event_id:
            raise ValueError(f"Não foi possível extrair event_id da URL: {self.url}")

        self._stop_event.clear()
        self._started = True

        # Fetch inicial (síncrono)
        self._initial_fetch()

        # Thread de polling
        self._thread = threading.Thread(
            target=self._poll_loop,
            name=f"sofascore-{self.event_id}",
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        if not self._started:
            return
        self._stop_event.set()
        self._started = False
        if self._thread and self._thread.is_alive():
            self._thread.join(timeout=5)
        self._thread = None
        logger.info("Sofascore tracker parado: event_id=%d", self.event_id)

    @property
    def is_running(self) -> bool:
        return self._started and not self._stop_event.is_set()

    def get_data(self) -> MatchData:
        with self._lock:
            if self._timer_running and self._timer_start_realtime > 0:
                elapsed = _time.time() - self._timer_start_realtime
                total_seconds = self._timer_base_seconds + int(elapsed)
                minutes = total_seconds // 60
                seconds = total_seconds % 60
                self.data.current_time = f"{minutes}:{seconds:02d}"
            return self.data

    # ─── Estado inicial ─────────────────────────────────────────────

    def _initial_fetch(self) -> None:
        try:
            match_data, incidents_raw = fetch_sofascore_match(self.event_id)
            with self._lock:
                self.data = match_data
                self.event_count = len(match_data.events)
                self.update_count = 1
                self.last_updated = datetime.now()
                self.last_error = None

                # Indexar incidentes conhecidos
                for inc in incidents_raw:
                    key = _incident_unique_key(inc)
                    self._known_incidents[key] = inc

            # Sincronizar timer
            self._sync_timer(match_data.current_time, match_data.stream_info.timer_status)

            logger.info(
                "Sofascore fetch inicial — %s: %s %d x %d %s | %d eventos | %s",
                self._match_label(),
                match_data.home_team.abbreviation,
                match_data.home_score,
                match_data.away_score,
                match_data.away_team.abbreviation,
                len(match_data.events),
                match_data.period,
            )
        except Exception as e:
            with self._lock:
                self.last_error = str(e)
            logger.error("Erro no fetch inicial Sofascore: %s", e)
            raise

    # ─── Loop de polling ────────────────────────────────────────────

    def _poll_loop(self) -> None:
        while not self._stop_event.is_set():
            interval = self._get_poll_interval()
            self._stop_event.wait(interval)
            if self._stop_event.is_set():
                break

            try:
                self._poll_once()
            except Exception as e:
                with self._lock:
                    self.last_error = str(e)
                logger.error("Erro no polling Sofascore (event_id=%d): %s", self.event_id, e)

    def _get_poll_interval(self) -> float:
        with self._lock:
            pid = self.data.period_id
        if pid in ("PRIMEIRO_TEMPO", "SEGUNDO_TEMPO"):
            return POLL_INTERVAL_LIVE
        elif pid == "INTERVALO":
            return POLL_INTERVAL_IDLE
        elif pid in ("FIM_DE_JOGO", "CANCELADO", "ADIADO"):
            self._finished_polls += 1
            if self._finished_polls > 5:
                # Parar polling automático depois de 5 polls pós-encerramento
                logger.info("Partida encerrada — parando polling automático (event_id=%d)", self.event_id)
                self._stop_event.set()
            return POLL_INTERVAL_FINISHED
        else:
            return POLL_INTERVAL_IDLE

    def _poll_once(self) -> None:
        """Faz um poll completo e detecta mudanças."""
        # 1. Buscar evento principal (placar, status)
        event_data = fetch_sofascore_event(self.event_id)

        # 2. Buscar incidentes
        try:
            incidents_raw = fetch_sofascore_incidents(self.event_id)
        except Exception:
            incidents_raw = []

        # 3. Buscar estatísticas
        stats = None
        try:
            stats_data = _api_get(f"/event/{self.event_id}/statistics")
            stats = _parse_statistics(stats_data)
        except Exception:
            pass

        with self._lock:
            old_period_id = self.data.period_id
            old_home_score = self.data.home_score
            old_away_score = self.data.away_score

            # Atualizar dados gerais
            self._update_event_data(event_data)

            # Detectar mudanças de período
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
                logger.info(
                    "Mudança de período — %s: %s → %s",
                    self._match_label(), old_period_id, self.data.period_id,
                )

            # Detectar mudanças de placar
            if self.data.home_score != old_home_score or self.data.away_score != old_away_score:
                self._publish("score_change", {
                    "home_score": self.data.home_score,
                    "away_score": self.data.away_score,
                    "home_abbreviation": self.data.home_team.abbreviation,
                    "away_abbreviation": self.data.away_team.abbreviation,
                })

            # Detectar novos incidentes
            self._detect_new_incidents(incidents_raw)

            # Atualizar estatísticas
            if stats:
                self.data.statistics = stats
                self._publish("statistics", {
                    "home_team_name": self.data.home_team.name,
                    "away_team_name": self.data.away_team.name,
                    "home_team": asdict(stats.home_team),
                    "away_team": asdict(stats.away_team),
                    "last_updated": datetime.now().isoformat(),
                })

            self.update_count += 1
            self.last_updated = datetime.now()
            self.last_error = None

    def _update_event_data(self, event_data: dict) -> None:
        """Atualiza placar, período e tempo a partir do evento principal."""
        from sofascore_scraper import STATUS_CODE_TO_PERIOD

        # Placar
        home_score_data = event_data.get("homeScore", {})
        away_score_data = event_data.get("awayScore", {})
        if isinstance(home_score_data, dict):
            self.data.home_score = home_score_data.get("current", self.data.home_score)
        if isinstance(away_score_data, dict):
            self.data.away_score = away_score_data.get("current", self.data.away_score)

        # Status / período
        status = event_data.get("status", {})
        status_code = status.get("code", 0)
        status_type = status.get("type", "")

        period_label, period_id = STATUS_CODE_TO_PERIOD.get(
            status_code, (self.data.period, self.data.period_id)
        )
        self.data.period = period_label
        self.data.period_id = period_id

        # Timer
        time_info = event_data.get("time", {})
        if status_type == "inprogress" and time_info:
            current_ts = time_info.get("currentPeriodStartTimestamp", 0)
            initial = time_info.get("initial", 0)
            if current_ts > 0:
                elapsed = int(_time.time()) - current_ts + initial
                minutes = elapsed // 60
                seconds = elapsed % 60
                self.data.current_time = f"{minutes}:{seconds:02d}"
                self._sync_timer(self.data.current_time, "INICIADO")
        else:
            self._sync_timer(self.data.current_time, "PARADO")

        self.data.stream_info.status = status_type.upper() if status_type else ""
        self.data.stream_info.timer_status = "INICIADO" if status_type == "inprogress" else "PARADO"

    def _detect_new_incidents(self, incidents_raw: list) -> None:
        """Compara incidentes atuais com conhecidos e publica novos."""
        current_keys = set()
        for inc in incidents_raw:
            key = _incident_unique_key(inc)
            current_keys.add(key)

            if key not in self._known_incidents:
                # Incidente novo!
                self._known_incidents[key] = inc
                ev = _incident_to_event(inc, self.data.home_team, self.data.away_team)
                if ev is not None:
                    ev._raw_id = key  # type: ignore
                    self.data.events.append(ev)
                    self.event_count = len(self.data.events)

                    logger.info(
                        "Novo incidente — %s | %s' %s | %s",
                        self._match_label(),
                        ev.minute,
                        ev.type,
                        ev.title,
                    )
                    self._publish("play_new", asdict(ev))

        # Remover incidentes que desapareceram (ex: VAR anulou gol)
        removed_keys = set(self._known_incidents.keys()) - current_keys
        for key in removed_keys:
            del self._known_incidents[key]
            self.data.events = [
                ev for ev in self.data.events
                if getattr(ev, '_raw_id', None) != key
            ]
            self.event_count = len(self.data.events)
            logger.info("Incidente removido — %s | key=%s", self._match_label(), key)

    # ─── Timer ──────────────────────────────────────────────────────

    def _sync_timer(self, current_time_str: str = None, timer_status: str = None) -> None:
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
            if self._timer_running and self._timer_start_realtime > 0:
                elapsed = _time.time() - self._timer_start_realtime
                self._timer_base_seconds += int(elapsed)
            self._timer_running = False
            self._timer_start_realtime = 0.0

    # ─── Helpers ────────────────────────────────────────────────────

    def _match_label(self) -> str:
        if self.data.home_team.name:
            return f"{self.data.home_team.abbreviation}x{self.data.away_team.abbreviation}"
        return f"sofascore:{self.event_id}"

    def status_dict(self) -> dict:
        with self._lock:
            return {
                "url": self.url,
                "source": "sofascore",
                "event_id": self.event_id,
                "is_running": self.is_running,
                "sse_connected": False,
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
