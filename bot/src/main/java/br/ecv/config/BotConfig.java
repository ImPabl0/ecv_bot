package br.ecv.config;

import br.ecv.database.SettingsRepository;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.bson.Document;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Configuração global do bot. Mantém o estado (ativo/inativo),
 * o canal de notificações, os administradores (por usuário ou cargo)
 * e as configurações persistidas no MongoDB.
 */
public class BotConfig {

    /** Cargo que, por padrão, também pode usar os comandos administrativos. */
    private static final String DEFAULT_ADMIN_ROLE_ID = "1521190531185643710";

    private static final String KEY_NOTIFICATION_CHANNEL = "notificationChannelId";
    private static final String KEY_NOTIFICATION_ROLE = "notificationRoleId";

    private static boolean botEnabled = true;
    private static String notificationChannelId = null;
    private static final List<String> ADMIN_IDS = new ArrayList<>();
    private static final List<String> ADMIN_ROLE_IDS = new ArrayList<>();
    private static String guildId;
    private static String apiFootballUrl;
    private static int pollInterval;
    private static String monitoredMatchUrl = null;
    private static String notificationRoleId = null;
    private static boolean debug;

    public static void load(Dotenv dotenv) {
        String adminIds = dotenv.get("ADMIN_IDS");
        if (adminIds != null && !adminIds.isBlank()) {
            for (String id : adminIds.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isBlank())
                    ADMIN_IDS.add(trimmed);
            }
        }

        ADMIN_ROLE_IDS.add(DEFAULT_ADMIN_ROLE_ID);
        String adminRoleIds = dotenv.get("ADMIN_ROLE_IDS");
        if (adminRoleIds != null && !adminRoleIds.isBlank()) {
            for (String id : adminRoleIds.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isBlank() && !ADMIN_ROLE_IDS.contains(trimmed))
                    ADMIN_ROLE_IDS.add(trimmed);
            }
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

        loadPersistedSettings();
    }

    /**
     * Recarrega do MongoDB as configurações definidas por comando
     * (canal de notificações e cargo mencionado), para que sobrevivam a reinícios.
     */
    private static void loadPersistedSettings() {
        Document doc = SettingsRepository.load();
        if (doc == null)
            return;

        String channelId = doc.getString(KEY_NOTIFICATION_CHANNEL);
        if (channelId != null && !channelId.isBlank())
            notificationChannelId = channelId;

        String roleId = doc.getString(KEY_NOTIFICATION_ROLE);
        if (roleId != null && !roleId.isBlank())
            notificationRoleId = roleId;
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
        SettingsRepository.save(KEY_NOTIFICATION_CHANNEL, channelId);
    }

    public static boolean isAdmin(String userId) {
        return ADMIN_IDS.contains(userId);
    }

    /**
     * Verifica se o membro pode usar os comandos administrativos: por ID de
     * usuário (ADMIN_IDS) ou por possuir um dos cargos autorizados.
     */
    public static boolean isAdmin(Member member) {
        if (member == null)
            return false;
        if (isAdmin(member.getId()))
            return true;
        for (Role role : member.getRoles()) {
            if (ADMIN_ROLE_IDS.contains(role.getId()))
                return true;
        }
        return false;
    }

    public static List<String> getAdminIds() {
        return ADMIN_IDS;
    }

    public static List<String> getAdminRoleIds() {
        return ADMIN_ROLE_IDS;
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
        SettingsRepository.save(KEY_NOTIFICATION_ROLE, roleId);
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
