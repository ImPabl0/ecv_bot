package br.ecv.embeds;

import br.ecv.model.MatchState;
import br.ecv.model.MatchStatistics;
import br.ecv.model.TeamStatistics;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;

import java.awt.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Embeds personalizados para cada tipo de notificação do Leão Bot.
 * Cores baseadas no Esporte Clube Vitória: vermelho e preto.
 */
public class GameEmbeds {

    private static final Color VITORIA_RED = new Color(204, 0, 0);
    private static final Color VITORIA_BLACK = new Color(30, 30, 30);
    private static final Color GOLD = new Color(255, 215, 0);
    private static final Color YELLOW_CARD = new Color(255, 204, 0);
    private static final Color RED_CARD = new Color(200, 0, 0);
    private static final Color GREEN = new Color(0, 180, 0);
    private static final Color BLUE = new Color(0, 120, 215);

    private static final String VITORIA_ICON = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/59/Escudo_do_Esporte_Clube_Vit%C3%B3ria.png/200px-Escudo_do_Esporte_Clube_Vit%C3%B3ria.png";
    private static final String FOOTER_TEXT = "Leão Bot \uD83E\uDD81 | Esporte Clube Vitória";

    /**
     * Embed de início de jogo.
     */
    public static MessageEmbed startMatch(String mandante, String visitante, String campeonato) {
        return new EmbedBuilder()
                .setTitle("\uD83D\uDFE2 COMEÇA O JOGO!")
                .setDescription(String.format("**%s** x **%s**", mandante, visitante))
                .addField("\uD83C\uDFC6 Campeonato", campeonato, false)
                .setColor(GREEN)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Embed de gol.
     */
    public static MessageEmbed goal(String jogador, String minuto, String assistencia) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("⚽ GOOOOOL DO VITÓRIA!")
                .setDescription(String.format("**%s** aos **%s'**", jogador, minuto))
                .setColor(GOLD)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now());

        if (assistencia != null && !assistencia.isBlank()) {
            builder.addField("\uD83C\uDFAF Assistência", assistencia, true);
        }

        return builder.build();
    }

    /**
     * Embed de cartão amarelo.
     */
    public static MessageEmbed yellowCard(String jogador, String minuto, String imageUrl) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("\uD83D\uDFE8 CARTÃO AMARELO")
                .setDescription(String.format("**%s** recebeu cartão amarelo aos **%s'**", jogador, minuto))
                .setColor(YELLOW_CARD)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now());

        if (imageUrl != null && !imageUrl.isBlank()) {
            builder.setImage(imageUrl);
        }

        return builder.build();
    }

    /**
     * Embed de cartão vermelho.
     */
    public static MessageEmbed redCard(String jogador, String minuto, String imageUrl) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("\uD83D\uDFE5 CARTÃO VERMELHO")
                .setDescription(String.format("**%s** foi expulso aos **%s'**!", jogador, minuto))
                .setColor(RED_CARD)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now());

        if (imageUrl != null && !imageUrl.isBlank()) {
            builder.setImage(imageUrl);
        }

        return builder.build();
    }

    /**
     * Embed de substituição.
     */
    public static MessageEmbed substitution(String saiu, String entrou, String minuto, String imageUrl) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("\uD83D\uDD04 SUBSTITUIÇÃO")
                .setDescription(String.format("Aos **%s'**", minuto))
                .addField("\uD83D\uDD3B Saiu", saiu, true)
                .addField("\uD83D\uDD3A Entrou", entrou, true)
                .setColor(VITORIA_BLACK)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now());

        if (imageUrl != null && !imageUrl.isBlank()) {
            builder.setImage(imageUrl);
        }

        return builder.build();
    }

    /**
     * Embed de placar.
     */
    public static MessageEmbed scoreboard(String mandante, int golsMandante,
            String visitante, int golsVisitante,
            String campeonato, String status) {
        String statusText = (status != null && !status.isBlank()) ? status : "Em andamento";

        return new EmbedBuilder()
                .setTitle("\uD83D\uDCCA PLACAR")
                .setDescription(String.format(
                        "**%s** %d x %d **%s**",
                        mandante, golsMandante, golsVisitante, visitante))
                .addField("\uD83C\uDFC6 Campeonato", campeonato, true)
                .addField("⏱ Status", statusText, true)
                .setColor(VITORIA_RED)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Embed de fim de jogo.
     */
    public static MessageEmbed endMatch(String mandante, int golsMandante,
            String visitante, int golsVisitante,
            String campeonato) {
        String resultado;
        Color color;

        // Determinar se o Vitória está envolvido e o resultado
        boolean vitoriaEhMandante = mandante.toLowerCase().contains("vitória")
                || mandante.toLowerCase().contains("vitoria");
        boolean vitoriaEhVisitante = visitante.toLowerCase().contains("vitória")
                || visitante.toLowerCase().contains("vitoria");

        if (vitoriaEhMandante && golsMandante > golsVisitante) {
            resultado = "\uD83C\uDFC6 VITÓRIA DO LEÃO!";
            color = GOLD;
        } else if (vitoriaEhVisitante && golsVisitante > golsMandante) {
            resultado = "\uD83C\uDFC6 VITÓRIA DO LEÃO!";
            color = GOLD;
        } else if (golsMandante == golsVisitante) {
            resultado = "\uD83E\uDD1D EMPATE";
            color = VITORIA_BLACK;
        } else {
            resultado = "\uD83D\uDE14 DERROTA";
            color = new Color(100, 100, 100);
        }

        return new EmbedBuilder()
                .setTitle("🏁 FIM DE JOGO!")
                .setDescription(String.format(
                        "**%s** %d x %d **%s**\n\n**%s**",
                        mandante, golsMandante, golsVisitante, visitante, resultado))
                .addField("\uD83C\uDFC6 Campeonato", campeonato, false)
                .setColor(color)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Embed de gol vindo da API (com informações automáticas).
     */
    public static MessageEmbed goalFromApi(String titulo, String jogador, String minuto,
            String placar, String descricao, String imageUrl) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(titulo)
                .setDescription(String.format("**%s** aos **%s'**", jogador, minuto))
                .addField("\uD83D\uDCCA Placar", placar, true)
                .setColor(GOLD)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now());

        if (descricao != null && !descricao.isBlank()) {
            String desc = descricao.length() > 200 ? descricao.substring(0, 200) + "..." : descricao;
            builder.addField("\uD83D\uDCDD Lance", desc, false);
        }

        if (imageUrl != null && !imageUrl.isBlank()) {
            builder.setImage(imageUrl);
        }

        return builder.build();
    }

    /**
     * Embed de lance importante.
     */
    public static MessageEmbed importantEvent(String title, String minuto, String descricao, String imageUrl) {
        String minuteText = (minuto != null && !minuto.isBlank()) ? minuto + "' — " : "";
        String titleText = (title != null && !title.isBlank()) ? title : "⚠\uFE0F LANCE IMPORTANTE";
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(titleText)
                .setDescription(minuteText + descricao)
                .setColor(VITORIA_RED)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now());

        if (imageUrl != null && !imageUrl.isBlank()) {
            builder.setImage(imageUrl);
        }

        return builder.build();
    }

    /**
     * Embed de lance normal (notícia genérica da partida).
     */
    public static MessageEmbed normalEvent(String minuto, String typeLabel, String title,
            String descricao, String imageUrl) {
        String header = (typeLabel != null && !typeLabel.isBlank()) ? typeLabel : "Lance";
        String minutePrefix = (minuto != null && !minuto.isBlank()) ? minuto + "' — " : "";
        String body = (title != null && !title.isBlank()) ? title : descricao;

        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("\uD83D\uDCCB " + header)
                .setDescription(minutePrefix + (body != null ? body : ""))
                .setColor(BLUE)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now());

        // Se tem título e descrição separados, mostrar descrição como field
        if (title != null && !title.isBlank() && descricao != null && !descricao.isBlank()
                && !title.equals(descricao)) {
            String desc = descricao.length() > 300 ? descricao.substring(0, 300) + "..." : descricao;
            builder.addField("\uD83D\uDCDD Detalhes", desc, false);
        }

        if (imageUrl != null && !imageUrl.isBlank()) {
            builder.setImage(imageUrl);
        }

        return builder.build();
    }

    /**
     * Embed do painel administrativo.
     */
    public static MessageEmbed adminPanel(boolean botEnabled, String channelName, String monitoredUrl,
            String matchLabel) {
        String statusEmoji = botEnabled ? "✅" : "❌";
        String statusText = botEnabled ? "Ativo" : "Desativado";
        String channelText = (channelName != null) ? "#" + channelName : "Nenhum canal selecionado";
        String matchText;
        if (monitoredUrl != null && !monitoredUrl.isBlank()) {
            matchText = (matchLabel != null && !matchLabel.isBlank())
                    ? matchLabel + "\n[Link](" + monitoredUrl + ")"
                    : "[" + monitoredUrl + "](" + monitoredUrl + ")";
        } else {
            matchText = "Nenhum jogo monitorado";
        }

        return new EmbedBuilder()
                .setTitle("⚙\uFE0F Painel Administrativo - Leão Bot")
                .setDescription("Gerencie as configurações do bot abaixo:")
                .addField(statusEmoji + " Status do Bot", statusText, true)
                .addField("\uD83D\uDCE2 Canal de Notificações", channelText, true)
                .addField("⚽ Jogo Monitorado", matchText, false)
                .setColor(VITORIA_RED)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Embed de informações da partida (com link SSE).
     */
    public static MessageEmbed matchInfo(MatchState state) {
        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("\uD83D\uDCCA Informações da Partida")
                .setDescription(String.format("**%s** %d x %d **%s**",
                        state.getHomeTeamName(), state.getHomeScore(),
                        state.getAwayScore(), state.getAwayTeamName()))
                .addField("\uD83C\uDFC6 Campeonato", state.getChampionship(), true)
                .addField("⏱ Período", state.getPeriod(), true)
                .addField("🕐 Tempo", state.getCurrentTime().isEmpty() ? "—" : state.getCurrentTime(), true)
                .setColor(VITORIA_RED)
                .setThumbnail(state.getHomeTeamBadge() != null && !state.getHomeTeamBadge().isEmpty()
                        ? state.getHomeTeamBadge()
                        : VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now());

        if (state.getStadium() != null && !state.getStadium().isEmpty()) {
            builder.addField("\uD83C\uDFDF Estádio", state.getStadium(), true);
        }

        if (state.getSseUrl() != null && !state.getSseUrl().isEmpty()) {
            builder.addField("\uD83D\uDD17 SSE (tempo real)", state.getSseUrl(), false);
        }

        return builder.build();
    }

    /**
     * Embed do painel administrativo (retrocompatibilidade).
     */
    public static MessageEmbed adminPanel(boolean botEnabled, String channelName) {
        return adminPanel(botEnabled, channelName, null, null);
    }

    /**
     * Embed de lista de jogadores.
     */
    public static MessageEmbed playerList(String playerListText) {
        return new EmbedBuilder()
                .setTitle("\uD83D\uDCCB Elenco do Vitória")
                .setDescription(playerListText.isEmpty() ? "Nenhum jogador cadastrado." : playerListText)
                .setColor(VITORIA_RED)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Embed de sucesso genérico.
     */
    public static MessageEmbed success(String title, String description) {
        return new EmbedBuilder()
                .setTitle("✅ " + title)
                .setDescription(description)
                .setColor(GREEN)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Embed de erro genérico.
     */
    public static MessageEmbed error(String title, String description) {
        return new EmbedBuilder()
                .setTitle("❌ " + title)
                .setDescription(description)
                .setColor(RED_CARD)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now())
                .build();
    }

    /**
     * Embed de estatísticas da partida.
     */
    public static MessageEmbed statistics(MatchStatistics stats, MatchState state) {
        String homeName = stats.getHomeTeamName() != null && !stats.getHomeTeamName().isEmpty()
                ? stats.getHomeTeamName()
                : "Mandante";
        String awayName = stats.getAwayTeamName() != null && !stats.getAwayTeamName().isEmpty()
                ? stats.getAwayTeamName()
                : "Visitante";

        TeamStatistics home = stats.getHomeTeam();
        TeamStatistics away = stats.getAwayTeam();

        String scoreText = state != null ? state.getScoreText() : "";
        String period = state != null ? state.getPeriod() : "";
        String championship = state != null ? state.getChampionship() : "";

        StringBuilder desc = new StringBuilder();
        if (!scoreText.isEmpty()) {
            desc.append("**").append(scoreText).append("**\n");
        }
        if (!period.isEmpty()) {
            desc.append("⏱ ").append(period);
            if (state != null && !state.getCurrentTime().isEmpty()) {
                desc.append(" — ").append(state.getCurrentTime());
            }
            desc.append("\n");
        }
        if (!championship.isEmpty()) {
            desc.append("\uD83C\uDFC6 ").append(championship).append("\n");
        }

        EmbedBuilder builder = new EmbedBuilder()
                .setTitle("\uD83D\uDCCA Estatísticas da Partida")
                .setDescription(desc.toString())
                .setColor(VITORIA_RED)
                .setThumbnail(VITORIA_ICON)
                .setFooter(FOOTER_TEXT)
                .setTimestamp(Instant.now());

        // Posse de bola
        builder.addField("⚽ Posse de Bola",
                formatStat(homeName, home.getBallPossession() + "%", awayName, away.getBallPossession() + "%"),
                false);

        // Finalizações
        builder.addField("\uD83C\uDFAF Finalizações",
                formatStat(homeName, String.valueOf(home.getTotalFinishes()), awayName,
                        String.valueOf(away.getTotalFinishes())),
                false);

        // Finalizações no gol
        builder.addField("\uD83E\uDD45 No Gol",
                formatStat(homeName, String.valueOf(home.getGoalFinish()), awayName,
                        String.valueOf(away.getGoalFinish())),
                true);

        // Finalizações para fora
        builder.addField("\u274C Para Fora",
                formatStat(homeName, String.valueOf(home.getBallOutFinish()), awayName,
                        String.valueOf(away.getBallOutFinish())),
                true);

        // Na trave
        builder.addField("\uD83E\uDDF1 Na Trave",
                formatStat(homeName, String.valueOf(home.getBallOnThePost()), awayName,
                        String.valueOf(away.getBallOnThePost())),
                true);

        // Escanteios
        builder.addField("\uD83D\uDEA9 Escanteios",
                formatStat(homeName, String.valueOf(home.getCornerKick()), awayName,
                        String.valueOf(away.getCornerKick())),
                true);

        // Impedimentos
        builder.addField("\uD83D\uDEAB Impedimentos",
                formatStat(homeName, String.valueOf(home.getOffSide()), awayName, String.valueOf(away.getOffSide())),
                true);

        // Faltas
        builder.addField("\u26A0\uFE0F Faltas",
                formatStat(homeName, String.valueOf(home.getFoulMade()), awayName, String.valueOf(away.getFoulMade())),
                true);

        // Cartões amarelos
        builder.addField("\uD83D\uDFE8 Amarelos",
                formatStat(homeName, String.valueOf(home.getYellowCardReceived()), awayName,
                        String.valueOf(away.getYellowCardReceived())),
                true);

        // Cartões vermelhos
        builder.addField("\uD83D\uDFE5 Vermelhos",
                formatStat(homeName, String.valueOf(home.getRedCardReceived()), awayName,
                        String.valueOf(away.getRedCardReceived())),
                true);

        // Passes
        builder.addField("\uD83D\uDC5F Passes (certos/total)",
                formatStat(homeName,
                        home.getRightPasses() + "/" + home.getTotalPasses() + " (" + home.getPassAccuracy() + "%)",
                        awayName,
                        away.getRightPasses() + "/" + away.getTotalPasses() + " (" + away.getPassAccuracy() + "%)"),
                false);

        // Desarmes
        builder.addField("\uD83E\uDDBF Desarmes",
                formatStat(homeName, String.valueOf(home.getTackle()), awayName, String.valueOf(away.getTackle())),
                true);

        // Defesas
        builder.addField("\uD83E\uDDE4 Defesas",
                formatStat(homeName, String.valueOf(home.getDefense()), awayName, String.valueOf(away.getDefense())),
                true);

        // Horário da atualização (converte UTC -> UTC-3 e exibe só o horário)
        String lastUpdated = stats.getLastUpdated();
        if (lastUpdated != null && !lastUpdated.isEmpty()) {
            try {
                DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern(
                        "EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH);
                ZonedDateTime utcTime = ZonedDateTime.parse(lastUpdated, inputFormatter);
                ZonedDateTime brTime = utcTime.withZoneSameInstant(ZoneId.of("America/Sao_Paulo"));
                String timeStr = brTime.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
                builder.addField("\uD83D\uDD52 Atualizado em", timeStr, false);
            } catch (Exception e) {
                builder.addField("\uD83D\uDD52 Atualizado em", lastUpdated, false);
            }
        }

        return builder.build();
    }

    /**
     * Formata uma estatística comparativa entre dois times.
     */
    private static String formatStat(String team1, String val1, String team2, String val2) {
        return String.format("**%s:** %s\n**%s:** %s", team1, val1, team2, val2);
    }
}
