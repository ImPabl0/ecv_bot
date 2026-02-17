package br.ecv.monitor;

import br.ecv.api.ApiFootballClient;
import br.ecv.config.BotConfig;
import br.ecv.database.PlayerRepository;
import br.ecv.embeds.GameEmbeds;
import br.ecv.model.MatchEvent;
import br.ecv.model.MatchStatistics;
import br.ecv.model.MatchState;
import br.ecv.model.Player;
import br.ecv.stickers.Stickers;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.sticker.GuildSticker;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * Monitor que se conecta via SSE à API Football para receber
 * eventos em tempo real (gols, cartões, substituições, mudanças de período,
 * lances)
 * e envia notificações automáticas no canal do Discord.
 */
public class MatchMonitor {

    private static final Logger logger = LoggerFactory.getLogger(MatchMonitor.class);

    private final JDA jda;
    private final ApiFootballClient apiClient;
    private final int pollIntervalSeconds; // mantido para reconnect delay

    private final OkHttpClient sseClient;
    private EventSource eventSource;
    private ScheduledExecutorService reconnectScheduler;

    private volatile MatchState lastState;
    private final Set<String> processedEventKeys = ConcurrentHashMap.newKeySet();
    private volatile String lastPeriodId = "";
    private volatile boolean running = false;

    public MatchMonitor(JDA jda, ApiFootballClient apiClient, int pollIntervalSeconds) {
        this.jda = jda;
        this.apiClient = apiClient;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.sseClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // sem timeout de leitura para SSE
                .retryOnConnectionFailure(true)
                .build();
    }

    /**
     * Inicia o monitoramento via SSE.
     */
    public synchronized void start() {
        if (running) {
            logger.warn("Monitor já está em execução. Parando o anterior...");
            stop();
        }

        lastState = null;
        processedEventKeys.clear();
        lastPeriodId = "";
        running = true;

        reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "MatchMonitor-Reconnect");
            t.setDaemon(true);
            return t;
        });

        connectSSE();
        logger.info("MatchMonitor iniciado (SSE)");
    }

    /**
     * Para o monitoramento.
     */
    public synchronized void stop() {
        running = false;

        if (eventSource != null) {
            eventSource.cancel();
            eventSource = null;
        }

        if (reconnectScheduler != null) {
            reconnectScheduler.shutdown();
            try {
                if (!reconnectScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    reconnectScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                reconnectScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            reconnectScheduler = null;
        }

        lastState = null;
        processedEventKeys.clear();
        lastPeriodId = "";
        logger.info("MatchMonitor parado.");
    }

    /**
     * Verifica se o monitor está ativo.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Retorna o último estado da partida.
     */
    public MatchState getLastState() {
        return lastState;
    }

    /**
     * Conecta ao endpoint SSE da API Flask.
     */
    private void connectSSE() {
        String monitoredUrl = BotConfig.getMonitoredMatchUrl();
        if (monitoredUrl == null || monitoredUrl.isBlank()) {
            logger.warn("Nenhuma URL monitorada configurada.");
            return;
        }

        String encodedUrl = URLEncoder.encode(monitoredUrl, StandardCharsets.UTF_8);
        String sseUrl = BotConfig.getApiFootballUrl() + "/stream?url=" + encodedUrl;

        Request request = new Request.Builder()
                .url(sseUrl)
                .header("Accept", "text/event-stream")
                .build();

        EventSource.Factory factory = EventSources.createFactory(sseClient);

        eventSource = factory.newEventSource(request, new EventSourceListener() {

            @Override
            public void onOpen(EventSource source, Response response) {
                logger.info("SSE conectado: {}", sseUrl);
            }

            @Override
            public void onEvent(EventSource source, String id, String type, String data) {
                if (!running || !BotConfig.isBotEnabled())
                    return;

                try {
                    switch (type != null ? type : "") {
                        case "connected" -> handleConnected(data);
                        case "play_new" -> handlePlayNew(data);
                        case "play_update" -> handlePlayUpdate(data);
                        case "period_change" -> handlePeriodChange(data);
                        case "score_change" -> handleScoreChange(data);
                        case "statistics" -> handleStatistics(data);
                        case "ping" -> logger.trace("SSE ping recebido");
                        default -> logger.debug("SSE evento desconhecido: {}", type);
                    }
                } catch (Exception e) {
                    logger.error("Erro ao processar evento SSE [{}]: {}", type, e.getMessage(), e);
                }
            }

            @Override
            public void onClosed(EventSource source) {
                logger.warn("SSE conexão fechada.");
                scheduleReconnect();
            }

            @Override
            public void onFailure(EventSource source, Throwable t, Response response) {
                if (!running)
                    return;
                String msg = t != null ? t.getMessage() : "response=" + (response != null ? response.code() : "null");
                logger.error("SSE falha de conexão: {}", msg);
                scheduleReconnect();
            }
        });
    }

    /**
     * Agenda reconexão SSE após falha.
     */
    private void scheduleReconnect() {
        if (!running || reconnectScheduler == null || reconnectScheduler.isShutdown())
            return;

        int delay = Math.max(pollIntervalSeconds, 10);
        logger.info("Reconectando SSE em {}s...", delay);

        try {
            reconnectScheduler.schedule(() -> {
                if (running) {
                    if (eventSource != null) {
                        eventSource.cancel();
                    }
                    connectSSE();
                }
            }, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            // scheduler foi desligado
        }
    }

    // ======================== HANDLERS DE EVENTOS SSE ========================

    /**
     * Evento "connected": recebe o estado inicial completo.
     * Marca todos os eventos existentes como processados (não notifica).
     */
    private void handleConnected(String data) {
        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
        MatchState state = MatchState.fromJson(json);

        // Marcar todos os eventos existentes como já processados
        for (MatchEvent event : state.getEvents()) {
            processedEventKeys.add(event.uniqueKey());
        }

        lastPeriodId = state.getPeriodId() != null ? state.getPeriodId() : "";
        lastState = state;

        logger.info("SSE estado inicial: {} ({} eventos pré-existentes)",
                state.getScoreText(), state.getEvents().size());
    }

    /**
     * Evento "play_new": novo lance na partida.
     * Notifica gols, cartões, substituições, lances importantes e normais.
     */
    private void handlePlayNew(String data) {
        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
        MatchEvent event = MatchEvent.fromJson(json);

        String key = event.uniqueKey();
        if (processedEventKeys.contains(key)) {
            return; // Evento duplicado
        }
        processedEventKeys.add(key);

        // Atualizar estado local com novo evento
        if (lastState != null) {
            lastState.getEvents().add(event);
        }

        TextChannel channel = getNotificationChannel();
        if (channel == null)
            return;

        logger.info("Novo evento SSE: [{}] {} - {} ({})", event.getType(), event.getMinute(),
                event.getPlayer().isEmpty() ? event.getTitle() : event.getPlayer(), event.getTeam());

        switch (event.getType()) {
            case "GOAL" -> handleGoal(channel, lastState, event);
            case "CARD" -> handleCard(channel, lastState, event);
            case "SUBSTITUTION" -> handleSubstitution(channel, lastState, event);
            case "IMPORTANT" -> handleImportant(channel, event);
            case "NORMAL" -> handleNormal(channel, event);
            default -> {
                // SUMMARY_AUTOMATIC e outros são ignorados
            }
        }
    }

    /**
     * Evento "play_update": atualização de um lance existente.
     * Pode ser atualização de descrição ou mudança de status.
     */
    private void handlePlayUpdate(String data) {
        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
        MatchEvent event = MatchEvent.fromJson(json);

        // Atualizar no estado local (se existir)
        if (lastState != null) {
            List<MatchEvent> events = lastState.getEvents();
            for (int i = 0; i < events.size(); i++) {
                if (events.get(i).uniqueKey().equals(event.uniqueKey())) {
                    events.set(i, event);
                    break;
                }
            }
        }

        logger.debug("Play update: [{}] {}", event.getType(), event.getTitle());
    }

    /**
     * Evento "period_change": mudança de período da partida.
     */
    private void handlePeriodChange(String data) {
        JsonObject json = JsonParser.parseString(data).getAsJsonObject();

        String oldPeriodId = json.has("old_period_id") ? json.get("old_period_id").getAsString() : "";
        String newPeriodId = json.has("new_period_id") ? json.get("new_period_id").getAsString() : "";
        String period = json.has("period") ? json.get("period").getAsString() : "";
        int homeScore = json.has("home_score") ? json.get("home_score").getAsInt() : 0;
        int awayScore = json.has("away_score") ? json.get("away_score").getAsInt() : 0;
        String homeTeam = json.has("home_team") ? json.get("home_team").getAsString() : "";
        String awayTeam = json.has("away_team") ? json.get("away_team").getAsString() : "";
        String championship = json.has("championship") ? json.get("championship").getAsString() : "";

        // Atualizar estado local
        if (lastState != null) {
            // Atualizar campos via reflexão evitada — usar o estado recebido
        }
        lastPeriodId = newPeriodId;

        TextChannel channel = getNotificationChannel();
        if (channel == null)
            return;

        logger.info("Mudança de período SSE: {} -> {}", oldPeriodId, newPeriodId);

        switch (newPeriodId) {
            case "PRIMEIRO_TEMPO" -> {
                channel.sendMessageEmbeds(GameEmbeds.startMatch(homeTeam, awayTeam, championship)).queue();
                trySendSticker(channel, Stickers.Events.Whistle);
            }
            case "INTERVALO" -> {
                channel.sendMessageEmbeds(GameEmbeds.scoreboard(
                        homeTeam, homeScore, awayTeam, awayScore, championship, "Intervalo")).queue();
            }
            case "SEGUNDO_TEMPO" -> {
                channel.sendMessageEmbeds(GameEmbeds.scoreboard(
                        homeTeam, homeScore, awayTeam, awayScore, championship, "Começa o 2º tempo!")).queue();
                trySendSticker(channel, Stickers.Events.Whistle);
            }
            case "FIM_DE_JOGO", "ENCERRADO" -> {
                channel.sendMessageEmbeds(GameEmbeds.endMatch(
                        homeTeam, homeScore, awayTeam, awayScore, championship)).queue();
                trySendSticker(channel, Stickers.Events.Whistle);
            }
        }
    }

    /**
     * Evento "statistics": estatísticas da partida recebidas em tempo real.
     */
    private void handleStatistics(String data) {
        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
        MatchStatistics stats = MatchStatistics.fromJson(json);

        if (lastState != null) {
            lastState.setStatistics(stats);
        }

        logger.info("Estatísticas atualizadas via SSE — Posse: {}% x {}%",
                stats.getHomeTeam().getBallPossession(),
                stats.getAwayTeam().getBallPossession());
    }

    /**
     * Evento "score_change": mudança no placar (atualiza estado local).
     */
    private void handleScoreChange(String data) {
        JsonObject json = JsonParser.parseString(data).getAsJsonObject();
        int homeScore = json.has("home_score") ? json.get("home_score").getAsInt() : 0;
        int awayScore = json.has("away_score") ? json.get("away_score").getAsInt() : 0;
        String homeAbbr = json.has("home_abbreviation") ? json.get("home_abbreviation").getAsString() : "";
        String awayAbbr = json.has("away_abbreviation") ? json.get("away_abbreviation").getAsString() : "";

        logger.info("Placar atualizado via SSE: {} {} x {} {}",
                homeAbbr, homeScore, awayScore, awayAbbr);

        // Atualizar o estado local para que embeds subsequentes reflitam o placar correto
        MatchState current = lastState;
        if (current != null) {
            current.setHomeScore(homeScore);
            current.setAwayScore(awayScore);
        }
    }

    // ======================== HANDLERS DE TIPOS DE EVENTO ========================

    /**
     * Trata evento de gol.
     */
    private void handleGoal(TextChannel channel, MatchState state, MatchEvent event) {
        PlayerRepository playerRepository = new PlayerRepository();
        String jogador = event.getPlayer();
        String minuto = event.getMinute();
        String team = event.getTeam();
        String imageUrl = event.getImage();

        // Construir texto do gol
        String golTipo = switch (event.getGoalKind()) {
            case "PENALTY_GOAL" -> " (Pênalti)";
            case "OWN_GOAL" -> " (Contra)";
            default -> "";
        };

        boolean isVitoria = isVitoriaTeam(team);

        String titulo;
        if (isVitoria) {
            titulo = "⚽ GOOOOOL DO VITÓRIA!" + golTipo;
        } else {
            titulo = "⚽ Gol de " + (state != null ? getTeamDisplayName(state, team) : team) + golTipo;
        }

        String placar = state != null ? state.getScoreText() : "";
        MessageEmbed embed = GameEmbeds.goalFromApi(titulo, jogador, minuto, placar,
                event.getDescription(), imageUrl);
        channel.sendMessageEmbeds(embed).queue();

        // Tentar enviar sticker do jogador
        Player player = playerRepository.findByNameAndTeam(jogador, team);
        if (player != null && player.getStickerId() != null) {
            trySendSticker(channel, player.getStickerId());
        } else if (isVitoria) {
            trySendSticker(channel, Stickers.Players.Unknown);
        }

        // Sticker de gol
        if (isVitoria) {
            trySendSticker(channel, Stickers.Events.Goal);
        }
    }

    /**
     * Trata evento de cartão.
     */
    private void handleCard(TextChannel channel, MatchState state, MatchEvent event) {
        PlayerRepository playerRepository = new PlayerRepository();
        String jogador = event.getPlayer();
        String minuto = event.getMinute();
        String team = event.getTeam();
        String imageUrl = event.getImage();

        String cardType = !event.getCardType().isEmpty() ? event.getCardType() : event.getGoalKind();

        if (cardType.contains("RED") || cardType.contains("SECOND_YELLOW")) {
            channel.sendMessageEmbeds(GameEmbeds.redCard(jogador, minuto, imageUrl)).queue();
            trySendSticker(channel, Stickers.Cards.Red);
        } else {
            channel.sendMessageEmbeds(GameEmbeds.yellowCard(jogador, minuto, imageUrl)).queue();
            trySendSticker(channel, Stickers.Cards.Yellow);
        }

        // Tentar enviar sticker do jogador
        Player player = playerRepository.findByNameAndTeam(jogador, team);
        if (player != null && player.getStickerId() != null) {
            trySendSticker(channel, player.getStickerId());
        }
    }

    /**
     * Trata evento de substituição.
     */
    private void handleSubstitution(TextChannel channel, MatchState state, MatchEvent event) {
        PlayerRepository playerRepository = new PlayerRepository();
        String saiu = event.getPlayerOut();
        String entrou = event.getPlayerIn();
        String minuto = event.getMinute().isEmpty() ? "—" : event.getMinute();
        String team = event.getTeam();
        String imageUrl = event.getImage();

        channel.sendMessageEmbeds(GameEmbeds.substitution(saiu, entrou, minuto, imageUrl)).queue();

        // Tentar enviar sticker do jogador que entrou
        Player player = playerRepository.findByNameAndTeam(entrou, team);
        if (player != null && player.getStickerId() != null) {
            trySendSticker(channel, player.getStickerId());
        } else if (isVitoriaTeam(team)) {
            trySendSticker(channel, Stickers.Players.Unknown);
        }
    }

    /**
     * Trata evento importante (lances perigosos, etc.).
     */
    private void handleImportant(TextChannel channel, MatchEvent event) {
        String description = event.getDescription();
        if (description != null && !description.isBlank()) {
            MessageEmbed embed = GameEmbeds.importantEvent(event.getTitle(), event.getMinute(), description,
                    event.getImage());
            channel.sendMessageEmbeds(embed).queue();
        }
    }

    /**
     * Trata evento normal (lances comuns da partida).
     */
    private void handleNormal(TextChannel channel, MatchEvent event) {
        String title = event.getTitle();
        String description = event.getDescription();
        // Só notifica se tiver conteúdo relevante
        if ((title != null && !title.isBlank()) || (description != null && !description.isBlank())) {
            MessageEmbed embed = GameEmbeds.normalEvent(
                    event.getMinute(), event.getTypeLabel(), title, description, event.getImage());
            channel.sendMessageEmbeds(embed).queue();
        }
    }

    // ======================== UTILITÁRIOS ========================

    private TextChannel getNotificationChannel() {
        String channelId = BotConfig.getNotificationChannelId();
        if (channelId == null)
            return null;

        String guildId = BotConfig.getGuildId();
        if (guildId == null)
            return null;

        var guild = jda.getGuildById(guildId);
        if (guild == null)
            return null;

        return guild.getTextChannelById(channelId);
    }

    private boolean isVitoriaTeam(String teamAbbreviation) {
        if (teamAbbreviation == null)
            return false;
        String lower = teamAbbreviation.toLowerCase();
        return lower.equals("vit") || lower.equals("ecv") || lower.contains("vitória")
                || lower.contains("vitoria");
    }

    private String getTeamDisplayName(MatchState state, String teamAbbreviation) {
        if (teamAbbreviation.equalsIgnoreCase(state.getHomeTeamAbbreviation())) {
            return state.getHomeTeamName();
        } else if (teamAbbreviation.equalsIgnoreCase(state.getAwayTeamAbbreviation())) {
            return state.getAwayTeamName();
        }
        return teamAbbreviation;
    }

    private void trySendSticker(TextChannel channel, String stickerName) {
        try {
            var guild = channel.getGuild();
            List<GuildSticker> stickers = guild.getStickersByName(stickerName, true);
            if (!stickers.isEmpty()) {
                channel.sendStickers(stickers.get(0)).queue(
                        success -> logger.debug("Sticker '{}' enviado.", stickerName),
                        error -> logger.warn("Falha ao enviar sticker '{}': {}", stickerName, error.getMessage()));
            }
        } catch (Exception e) {
            logger.warn("Erro ao enviar sticker '{}': {}", stickerName, e.getMessage());
        }
    }
}
