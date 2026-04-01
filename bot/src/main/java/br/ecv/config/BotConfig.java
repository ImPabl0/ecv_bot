package br.ecv.config;

import io.github.cdimascio.dotenv.Dotenv;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuração global do bot. Mantém o estado (ativo/inativo),
 * o canal de notificações e a lista de administradores.
 */
public class BotConfig {

    private static boolean botEnabled = true;
    private static String notificationChannelId = null;
    private static final List<String> ADMIN_IDS = new ArrayList<>();
    private static String guildId;
    private static String apiFootballUrl;
    private static int pollInterval;
    private static String monitoredMatchUrl = null;
    private static String notificationRoleId = null;
    private static boolean debug;

    public static void load(Dotenv dotenv) {
        String adminIds = dotenv.get("ADMIN_IDS");
        if (adminIds != null && !adminIds.isBlank()) {
            ADMIN_IDS.addAll(Arrays.asList(adminIds.split(",")));
        }
        guildId = dotenv.get("GUILD_ID");
        debug = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().toString()
                .contains("-agentlib:jdwp");
        String defaultUrl = debug ? "http://localhost:5000" : "http://api:5000";
        apiFootballUrl = dotenv.get("API_FOOTBALL_URL", defaultUrl);
        String pollStr = dotenv.get("POLL_INTERVAL", "15");
        try {
            pollInterval = Integer.parseInt(pollStr);
        } catch (NumberFormatException e) {
            pollInterval = 15;
        }
    }

    public static boolean isBotEnabled() {
        return botEnabled;
    }

    public static void setBotEnabled(boolean enabled) {
        botEnabled = enabled;
    }

    public static String getNotificationChannelId() {
        return notificationChannelId;
    }

    public static void setNotificationChannelId(String channelId) {
        notificationChannelId = channelId;
    }

    public static boolean isAdmin(String userId) {
        return ADMIN_IDS.contains(userId);
    }

    public static List<String> getAdminIds() {
        return ADMIN_IDS;
    }

    public static String getGuildId() {
        return guildId;
    }

    public static String getApiFootballUrl() {
        return apiFootballUrl;
    }

    public static int getPollInterval() {
        return pollInterval;
    }

    public static String getMonitoredMatchUrl() {
        return monitoredMatchUrl;
    }

    public static void setMonitoredMatchUrl(String url) {
        monitoredMatchUrl = url;
    }

    public static boolean isDebug() {
        return debug;
    }

    public static String getNotificationRoleId() {
        return notificationRoleId;
    }

    public static void setNotificationRoleId(String roleId) {
        notificationRoleId = roleId;
    }

    /**
     * Retorna a menção do cargo de notificação formatada, ou string vazia se não
     * configurado.
     */
    public static String getNotificationRoleMention() {
        if (notificationRoleId == null || notificationRoleId.isBlank()) {
            return "";
        }
        return "<@&" + notificationRoleId + ">";
    }
}
