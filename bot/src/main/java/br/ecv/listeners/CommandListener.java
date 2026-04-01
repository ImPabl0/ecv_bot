package br.ecv.listeners;

import br.ecv.api.ApiFootballClient;
import br.ecv.config.BotConfig;
import br.ecv.database.PlayerRepository;
import br.ecv.embeds.GameEmbeds;
import br.ecv.model.MatchState;
import br.ecv.model.MatchStatistics;
import br.ecv.model.Player;
import br.ecv.monitor.MatchMonitor;
import com.google.gson.JsonObject;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Listener para comandos slash do Leão Bot.
 * Comandos disponíveis:
 * - /painel: Painel administrativo
 * - /monitorar <url>: Inicia monitoramento de uma partida
 * - /parar: Para o monitoramento
 * - /placar: Mostra o placar da partida monitorada
 * - /jogador: Gerencia jogadores (adicionar, remover, listar)
 */
public class CommandListener extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(CommandListener.class);
    private final PlayerRepository playerRepository = new PlayerRepository();
    private final ApiFootballClient apiClient;
    private final MatchMonitor matchMonitor;

    public CommandListener(ApiFootballClient apiClient, MatchMonitor matchMonitor) {
        this.apiClient = apiClient;
        this.matchMonitor = matchMonitor;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String command = event.getName();
        String userId = event.getUser().getId();

        switch (command) {
            case "painel" -> handlePainel(event, userId);
            case "monitorar" -> handleMonitorar(event, userId);
            case "parar" -> handleParar(event, userId);
            case "placar" -> handlePlacar(event);
            case "estatisticas" -> handleEstatisticas(event);
            case "jogador" -> handleJogador(event, userId);
            case "atualizarplacar" -> handleAtualizarPlacar(event, userId);
            case "atualizartempo" -> handleAtualizarTempo(event, userId);
            case "cargonotificacao" -> handleCargoNotificacao(event, userId);
            case "teste" -> handleTeste(event, userId);
        }
    }

    /**
     * Abre o painel administrativo com informações do jogo monitorado.
     */
    private void handlePainel(SlashCommandInteractionEvent event, String userId) {
        if (!BotConfig.isAdmin(userId)) {
            event.replyEmbeds(GameEmbeds.error("Acesso Negado", "Você não tem permissão para acessar o painel."))
                    .setEphemeral(true).queue();
            return;
        }

        String channelName = null;
        if (BotConfig.getNotificationChannelId() != null) {
            TextChannel channel = event.getGuild().getTextChannelById(BotConfig.getNotificationChannelId());
            if (channel != null)
                channelName = channel.getName();
        }

        // Info do jogo monitorado
        String monitoredUrl = BotConfig.getMonitoredMatchUrl();
        String matchLabel = null;
        if (matchMonitor.getLastState() != null) {
            matchLabel = matchMonitor.getLastState().getMatchLabel()
                    + " (" + matchMonitor.getLastState().getScoreText() + ")";
        }

        // Seletor de canais
        List<TextChannel> channels = event.getGuild().getTextChannels();

        StringSelectMenu.Builder menuBuilder = StringSelectMenu.create("select_channel")
                .setPlaceholder("Selecione o canal de notificações")
                .setMinValues(1)
                .setMaxValues(1);

        int count = 0;
        for (TextChannel ch : channels) {
            if (count >= 25)
                break;
            menuBuilder.addOption("#" + ch.getName(), ch.getId());
            count++;
        }

        event.replyEmbeds(GameEmbeds.adminPanel(BotConfig.isBotEnabled(), channelName, monitoredUrl, matchLabel))
                .addActionRow(
                        Button.success("btn_enable", "✅ Ativar Bot"),
                        Button.danger("btn_disable", "❌ Desativar Bot"))
                .addActionRow(menuBuilder.build())
                .setEphemeral(true)
                .queue();
    }

    /**
     * Inicia o monitoramento de uma partida pelo link do ge.globo.com.
     */
    private void handleMonitorar(SlashCommandInteractionEvent event, String userId) {
        if (!checkAdmin(event, userId))
            return;
        if (!checkBotEnabled(event))
            return;

        String url = event.getOption("url").getAsString().trim();

        // Validar URL do ge.globo.com
        if (!url.contains("ge.globo.com") && !url.contains("globoesporte.globo.com")) {
            event.replyEmbeds(GameEmbeds.error("URL Inválida",
                    "A URL deve ser de uma partida do **ge.globo.com**.\n" +
                            "Exemplo: `https://ge.globo.com/futebol/brasileirao-serie-a/jogo/...`"))
                    .setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();

        try {
            // Tentar buscar a partida na API para validar a URL
            JsonObject json = apiClient.getMatch(url);
            MatchState state = MatchState.fromJson(json);

            // Se o jogo monitorado anterior era outro, remover o tracker
            String previousUrl = BotConfig.getMonitoredMatchUrl();
            if (previousUrl != null && !previousUrl.equals(url)) {
                try {
                    apiClient.removeMatch(previousUrl);
                } catch (Exception e) {
                    logger.warn("Falha ao remover tracker anterior: {}", e.getMessage());
                }
            }

            // Atualizar configuração
            BotConfig.setMonitoredMatchUrl(url);

            // Iniciar monitoramento
            matchMonitor.start();

            event.getHook().editOriginalEmbeds(GameEmbeds.success("Monitoramento Iniciado",
                    String.format("Monitorando: **%s**\n%s\n\n" +
                            "Eventos serão notificados automaticamente no canal configurado.",
                            state.getMatchLabel(), state.getScoreText())))
                    .queue();

            logger.info("Monitoramento iniciado: {} ({})", state.getMatchLabel(), url);

        } catch (Exception e) {
            logger.error("Erro ao iniciar monitoramento: {}", e.getMessage());
            event.getHook().editOriginalEmbeds(GameEmbeds.error("Erro",
                    "Não foi possível acessar a partida. Verifique se a URL está correta e se a API está rodando.\n" +
                            "**Erro:** " + e.getMessage()))
                    .queue();
        }
    }

    /**
     * Para o monitoramento da partida atual.
     */
    private void handleParar(SlashCommandInteractionEvent event, String userId) {
        if (!checkAdmin(event, userId))
            return;

        String monitoredUrl = BotConfig.getMonitoredMatchUrl();
        if (monitoredUrl == null || !matchMonitor.isRunning()) {
            event.replyEmbeds(GameEmbeds.error("Sem Monitoramento",
                    "Não há nenhuma partida sendo monitorada no momento."))
                    .setEphemeral(true).queue();
            return;
        }

        // Parar monitor e remover tracker na API
        matchMonitor.stop();

        try {
            apiClient.removeMatch(monitoredUrl);
        } catch (Exception e) {
            logger.warn("Falha ao remover tracker: {}", e.getMessage());
        }

        BotConfig.setMonitoredMatchUrl(null);

        event.replyEmbeds(GameEmbeds.success("Monitoramento Parado",
                "O monitoramento da partida foi encerrado."))
                .setEphemeral(true).queue();

        logger.info("Monitoramento parado por {}", event.getUser().getName());
    }

    /**
     * Mostra o placar da partida monitorada (busca da API).
     */
    private void handlePlacar(SlashCommandInteractionEvent event) {
        String monitoredUrl = BotConfig.getMonitoredMatchUrl();

        if (monitoredUrl == null || monitoredUrl.isBlank()) {
            event.replyEmbeds(GameEmbeds.error("Sem Monitoramento",
                    "Nenhuma partida está sendo monitorada. Use `/monitorar <url>` para iniciar."))
                    .setEphemeral(true).queue();
            return;
        }

        // Se já temos o último estado, usar ele (resposta rápida)
        MatchState state = matchMonitor.getLastState();
        if (state != null) {
            event.replyEmbeds(GameEmbeds.matchInfo(state)).queue();
            return;
        }

        // Caso contrário, buscar da API
        event.deferReply().queue();

        try {
            JsonObject json = apiClient.getMatch(monitoredUrl);
            MatchState freshState = MatchState.fromJson(json);
            event.getHook().editOriginalEmbeds(GameEmbeds.matchInfo(freshState)).queue();
        } catch (Exception e) {
            logger.error("Erro ao buscar placar: {}", e.getMessage());
            event.getHook().editOriginalEmbeds(GameEmbeds.error("Erro",
                    "Não foi possível buscar o placar. Verifique se a API está rodando."))
                    .queue();
        }
    }

    /**
     * Mostra as estatísticas da partida monitorada.
     */
    private void handleEstatisticas(SlashCommandInteractionEvent event) {
        String monitoredUrl = BotConfig.getMonitoredMatchUrl();

        if (monitoredUrl == null || monitoredUrl.isBlank()) {
            event.replyEmbeds(GameEmbeds.error("Sem Monitoramento",
                    "Nenhuma partida está sendo monitorada. Use `/monitorar <url>` para iniciar."))
                    .setEphemeral(true).queue();
            return;
        }

        MatchState state = matchMonitor.getLastState();

        // Tentar usar estatísticas já armazenadas via SSE
        if (state != null && state.getStatistics() != null) {
            event.replyEmbeds(GameEmbeds.statistics(state.getStatistics(), state)).queue();
            return;
        }

        // Caso contrário, buscar da API
        event.deferReply().queue();

        try {
            JsonObject json = apiClient.getMatch(monitoredUrl);
            MatchState freshState = MatchState.fromJson(json);

            if (freshState.getStatistics() != null) {
                event.getHook().editOriginalEmbeds(
                        GameEmbeds.statistics(freshState.getStatistics(), freshState)).queue();
            } else {
                event.getHook().editOriginalEmbeds(GameEmbeds.error("Sem Estatísticas",
                        "As estatísticas ainda não estão disponíveis para esta partida.")).queue();
            }
        } catch (Exception e) {
            logger.error("Erro ao buscar estatísticas: {}", e.getMessage());
            event.getHook().editOriginalEmbeds(GameEmbeds.error("Erro",
                    "Não foi possível buscar as estatísticas. Verifique se a API está rodando.")).queue();
        }
    }

    /**
     * Gerencia jogadores (adicionar, remover, listar).
     */
    private void handleJogador(SlashCommandInteractionEvent event, String userId) {
        if (!checkAdmin(event, userId))
            return;

        String subcommand = event.getSubcommandName();

        switch (Objects.requireNonNull(subcommand)) {
            case "adicionar" -> {
                String nome = event.getOption("nome").getAsString();
                int numero = event.getOption("numero").getAsInt();
                String posicao = event.getOption("posicao").getAsString();
                String time = event.getOption("time").getAsString();
                String stickerId = event.getOption("sticker_id") != null
                        ? event.getOption("sticker_id").getAsString()
                        : null;

                Player player = new Player(nome, numero, posicao, time, stickerId);
                playerRepository.addPlayer(player);

                event.replyEmbeds(GameEmbeds.success("Jogador Adicionado",
                        String.format("**%s** (#%d - %s | %s) foi adicionado ao elenco.",
                                nome, numero, posicao, time)))
                        .setEphemeral(true).queue();
                logger.info("Jogador adicionado: {} #{} ({}) - {}", nome, numero, posicao, time);
            }
            case "remover" -> {
                String nome = event.getOption("nome").getAsString();
                boolean removed = playerRepository.removePlayer(nome);

                if (removed) {
                    event.replyEmbeds(GameEmbeds.success("Jogador Removido",
                            String.format("**%s** foi removido do elenco.", nome)))
                            .setEphemeral(true).queue();
                } else {
                    event.replyEmbeds(GameEmbeds.error("Não Encontrado",
                            String.format("Jogador **%s** não foi encontrado.", nome)))
                            .setEphemeral(true).queue();
                }
            }
            case "listar" -> {
                List<Player> players = playerRepository.findAll();
                StringBuilder sb = new StringBuilder();
                for (Player p : players) {
                    sb.append(String.format("**#%d** %s - %s (%s)",
                            p.getNumero(), p.getNome(), p.getPosicao(),
                            p.getTime() != null ? p.getTime() : "—"));
                    if (p.getStickerId() != null) {
                        sb.append(" 🏷");
                    }
                    sb.append("\n");
                }
                event.replyEmbeds(GameEmbeds.playerList(sb.toString()))
                        .setEphemeral(true).queue();
            }
        }
    }

    /**
     * Configura o cargo que será mencionado em todas as notificações.
     */
    private void handleCargoNotificacao(SlashCommandInteractionEvent event, String userId) {
        if (!checkAdmin(event, userId))
            return;

        var roleOption = event.getOption("cargo");
        if (roleOption == null) {
            // Mostrar cargo atual ou remover
            String currentRoleId = BotConfig.getNotificationRoleId();
            if (currentRoleId != null) {
                event.replyEmbeds(GameEmbeds.success("Cargo de Notificação",
                        String.format(
                                "Cargo configurado: <@&%s>\n\nPara remover, use `/cargonotificacao` com o mesmo cargo.",
                                currentRoleId)))
                        .setEphemeral(true).queue();
            } else {
                event.replyEmbeds(GameEmbeds.error("Sem Cargo",
                        "Nenhum cargo de notificação configurado. Use `/cargonotificacao cargo:@Cargo` para configurar."))
                        .setEphemeral(true).queue();
            }
            return;
        }

        var role = roleOption.getAsRole();
        String currentRoleId = BotConfig.getNotificationRoleId();

        // Se o cargo já está configurado e é o mesmo, remove
        if (role.getId().equals(currentRoleId)) {
            BotConfig.setNotificationRoleId(null);
            event.replyEmbeds(GameEmbeds.success("Cargo Removido",
                    String.format("O cargo **%s** foi removido das notificações.", role.getName())))
                    .setEphemeral(true).queue();
            logger.info("Cargo de notificação removido por {}", event.getUser().getName());
        } else {
            BotConfig.setNotificationRoleId(role.getId());
            event.replyEmbeds(GameEmbeds.success("Cargo Configurado",
                    String.format("O cargo **%s** será mencionado em todas as notificações da partida.",
                            role.getName())))
                    .setEphemeral(true).queue();
            logger.info("Cargo de notificação configurado: {} ({}) por {}", role.getName(), role.getId(),
                    event.getUser().getName());
        }
    }

    /**
     * Força a atualização do placar buscando dados frescos da API e envia no canal.
     */
    private void handleAtualizarPlacar(SlashCommandInteractionEvent event, String userId) {
        if (!checkAdmin(event, userId))
            return;

        String monitoredUrl = BotConfig.getMonitoredMatchUrl();
        if (monitoredUrl == null || monitoredUrl.isBlank()) {
            event.replyEmbeds(GameEmbeds.error("Sem Monitoramento",
                    "Nenhuma partida está sendo monitorada. Use `/monitorar <url>` para iniciar."))
                    .setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();

        try {
            JsonObject json = apiClient.getMatch(monitoredUrl);
            MatchState freshState = MatchState.fromJson(json);

            // Atualizar o estado local do monitor
            MatchState currentState = matchMonitor.getLastState();
            if (currentState != null) {
                currentState.setHomeScore(freshState.getHomeScore());
                currentState.setAwayScore(freshState.getAwayScore());
            }

            // Enviar placar atualizado no canal de notificações
            TextChannel notifChannel = null;
            if (BotConfig.getNotificationChannelId() != null && BotConfig.getGuildId() != null) {
                var guild = event.getJDA().getGuildById(BotConfig.getGuildId());
                if (guild != null) {
                    notifChannel = guild.getTextChannelById(BotConfig.getNotificationChannelId());
                }
            }

            String status = freshState.getCurrentTime().isEmpty()
                    ? freshState.getPeriod()
                    : freshState.getPeriod() + " — " + freshState.getCurrentTime();

            if (notifChannel != null) {
                notifChannel.sendMessageEmbeds(GameEmbeds.scoreboard(
                        freshState.getHomeTeamName(), freshState.getHomeScore(),
                        freshState.getAwayTeamName(), freshState.getAwayScore(),
                        freshState.getChampionship(), status)).queue();
            }

            event.getHook().editOriginalEmbeds(GameEmbeds.success("Placar Atualizado",
                    String.format("Placar atualizado: **%s** %d x %d **%s**",
                            freshState.getHomeTeamName(), freshState.getHomeScore(),
                            freshState.getAwayScore(), freshState.getAwayTeamName())))
                    .queue();

        } catch (Exception e) {
            logger.error("Erro ao atualizar placar: {}", e.getMessage());
            event.getHook().editOriginalEmbeds(GameEmbeds.error("Erro",
                    "Não foi possível atualizar o placar. Verifique se a API está rodando."))
                    .queue();
        }
    }

    /**
     * Força a atualização do tempo do jogo buscando dados frescos da API e envia no
     * canal.
     */
    private void handleAtualizarTempo(SlashCommandInteractionEvent event, String userId) {
        if (!checkAdmin(event, userId))
            return;

        String monitoredUrl = BotConfig.getMonitoredMatchUrl();
        if (monitoredUrl == null || monitoredUrl.isBlank()) {
            event.replyEmbeds(GameEmbeds.error("Sem Monitoramento",
                    "Nenhuma partida está sendo monitorada. Use `/monitorar <url>` para iniciar."))
                    .setEphemeral(true).queue();
            return;
        }

        event.deferReply().queue();

        try {
            JsonObject json = apiClient.getMatch(monitoredUrl);
            MatchState freshState = MatchState.fromJson(json);

            // Atualizar o estado local do monitor
            MatchState currentState = matchMonitor.getLastState();
            if (currentState != null) {
                currentState.setCurrentTime(freshState.getCurrentTime());
                currentState.setPeriod(freshState.getPeriod());
            }

            // Enviar tempo atualizado no canal de notificações
            TextChannel notifChannel = null;
            if (BotConfig.getNotificationChannelId() != null && BotConfig.getGuildId() != null) {
                var guild = event.getJDA().getGuildById(BotConfig.getGuildId());
                if (guild != null) {
                    notifChannel = guild.getTextChannelById(BotConfig.getNotificationChannelId());
                }
            }

            String timeDisplay = freshState.getCurrentTime().isEmpty() ? "—" : freshState.getCurrentTime();
            String periodDisplay = freshState.getPeriod().isEmpty() ? "—" : freshState.getPeriod();

            if (notifChannel != null) {
                notifChannel.sendMessageEmbeds(GameEmbeds.scoreboard(
                        freshState.getHomeTeamName(), freshState.getHomeScore(),
                        freshState.getAwayTeamName(), freshState.getAwayScore(),
                        freshState.getChampionship(),
                        periodDisplay + " — " + timeDisplay)).queue();
            }

            event.getHook().editOriginalEmbeds(GameEmbeds.success("Tempo Atualizado",
                    String.format("**%s** — %s\n%s %d x %d %s",
                            periodDisplay, timeDisplay,
                            freshState.getHomeTeamName(), freshState.getHomeScore(),
                            freshState.getAwayScore(), freshState.getAwayTeamName())))
                    .queue();

        } catch (Exception e) {
            logger.error("Erro ao atualizar tempo: {}", e.getMessage());
            event.getHook().editOriginalEmbeds(GameEmbeds.error("Erro",
                    "Não foi possível atualizar o tempo do jogo. Verifique se a API está rodando."))
                    .queue();
        }
    }

    /**
     * Envia um embed fictício de lance no canal de notificações configurado.
     * Usado para debugar se o envio ao canal está funcionando.
     */
    private void handleTeste(SlashCommandInteractionEvent event, String userId) {
        if (!checkAdmin(event, userId))
            return;

        String channelId = BotConfig.getNotificationChannelId();
        String guildId = BotConfig.getGuildId();

        logger.info("[TESTE] channelId={}, guildId={}, botEnabled={}", channelId, guildId, BotConfig.isBotEnabled());

        if (channelId == null) {
            event.replyEmbeds(GameEmbeds.error("Canal Não Configurado",
                    "Nenhum canal de notificações foi selecionado.\n"
                            + "Configure pelo `/painel`.\n\n"
                            + "**Debug:** channelId=null, guildId=" + guildId))
                    .setEphemeral(true).queue();
            return;
        }

        if (guildId == null) {
            event.replyEmbeds(GameEmbeds.error("Guild Não Configurada",
                    "GUILD_ID não está definido no .env.\n\n"
                            + "**Debug:** channelId=" + channelId + ", guildId=null"))
                    .setEphemeral(true).queue();
            return;
        }

        var guild = event.getJDA().getGuildById(guildId);
        if (guild == null) {
            event.replyEmbeds(GameEmbeds.error("Guild Não Encontrada",
                    "Não foi possível encontrar a guild com ID: " + guildId))
                    .setEphemeral(true).queue();
            return;
        }

        TextChannel channel = guild.getTextChannelById(channelId);
        if (channel == null) {
            event.replyEmbeds(GameEmbeds.error("Canal Não Encontrado",
                    "Não foi possível encontrar o canal com ID: " + channelId
                            + "\nGuild: " + guild.getName() + " (" + guildId + ")"))
                    .setEphemeral(true).queue();
            return;
        }

        logger.info("[TESTE] Enviando embed fictício para #{} ({})", channel.getName(), channelId);

        // Enviar embed de gol fictício
        var embedGol = GameEmbeds.goalFromApi(
                "⚽ GOOOOOL DO VITÓRIA!",
                "Jogador Teste",
                "37",
                "Vitória 1 x 0 Bahia",
                "Jogador Teste recebe na entrada da área e chuta no ângulo! Golaço!",
                null);

        // Enviar embed de lance importante fictício
        var embedLance = GameEmbeds.importantEvent(
                "⚠️ LANCE IMPORTANTE",
                "42",
                "Quase gol! Jogador Teste cabeceia e a bola explode na trave!",
                null);

        String roleMention = BotConfig.getNotificationRoleMention();

        channel.sendMessage(roleMention.isEmpty() ? "**[TESTE]** Embed fictício de debug:"
                : roleMention + " **[TESTE]** Embed fictício de debug:")
                .setEmbeds(embedGol, embedLance)
                .queue(
                        success -> logger.info("[TESTE] Embeds enviados com sucesso para #{}", channel.getName()),
                        error -> logger.error("[TESTE] FALHA ao enviar embeds para #{}: {}", channel.getName(),
                                error.getMessage(), error));

        event.replyEmbeds(GameEmbeds.success("Teste Enviado",
                String.format("Embeds fictícios enviados para **#%s** (%s).\n\n"
                        + "**Debug info:**\n"
                        + "• guildId: %s\n"
                        + "• channelId: %s\n"
                        + "• botEnabled: %s\n"
                        + "• roleMention: %s\n"
                        + "• canal encontrado: ✅",
                        channel.getName(), channelId, guildId, channelId,
                        BotConfig.isBotEnabled(),
                        roleMention.isEmpty() ? "(nenhum)" : roleMention)))
                .setEphemeral(true).queue();
    }

    // ======================== UTILITÁRIOS ========================

    private boolean checkAdmin(SlashCommandInteractionEvent event, String userId) {
        if (!BotConfig.isAdmin(userId)) {
            event.replyEmbeds(GameEmbeds.error("Acesso Negado", "Você não tem permissão para usar este comando."))
                    .setEphemeral(true).queue();
            return false;
        }
        return true;
    }

    private boolean checkBotEnabled(SlashCommandInteractionEvent event) {
        if (!BotConfig.isBotEnabled()) {
            event.replyEmbeds(
                    GameEmbeds.error("Bot Desativado", "O bot está desativado no momento. Ative-o pelo painel."))
                    .setEphemeral(true).queue();
            return false;
        }
        if (BotConfig.getNotificationChannelId() == null) {
            event.replyEmbeds(GameEmbeds.error("Canal Não Configurado",
                    "Nenhum canal de notificações foi selecionado. Configure pelo /painel."))
                    .setEphemeral(true).queue();
            return false;
        }
        return true;
    }
}
