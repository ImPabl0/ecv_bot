package br.ecv.listeners;

import br.ecv.config.BotConfig;
import br.ecv.embeds.GameEmbeds;
import br.ecv.monitor.MatchMonitor;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listener para interações com botões e menus do painel administrativo.
 */
public class ButtonListener extends ListenerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ButtonListener.class);
    private final MatchMonitor matchMonitor;

    public ButtonListener(MatchMonitor matchMonitor) {
        this.matchMonitor = matchMonitor;
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String userId = event.getUser().getId();

        if (!BotConfig.isAdmin(userId)) {
            event.replyEmbeds(GameEmbeds.error("Acesso Negado", "Você não tem permissão."))
                    .setEphemeral(true).queue();
            return;
        }

        switch (event.getComponentId()) {
            case "btn_enable" -> {
                BotConfig.setBotEnabled(true);
                logger.info("Bot ativado por {}", event.getUser().getName());
                event.editMessageEmbeds(buildAdminPanel(event)).queue();
            }
            case "btn_disable" -> {
                BotConfig.setBotEnabled(false);
                logger.info("Bot desativado por {}", event.getUser().getName());
                event.editMessageEmbeds(buildAdminPanel(event)).queue();
            }
        }
    }

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        // O menu de jogos (select_game) é tratado no CommandListener.
        if (!event.getComponentId().equals("select_channel")) {
            return;
        }

        String userId = event.getUser().getId();

        if (!BotConfig.isAdmin(userId)) {
            event.replyEmbeds(GameEmbeds.error("Acesso Negado", "Você não tem permissão."))
                    .setEphemeral(true).queue();
            return;
        }

        if (event.getComponentId().equals("select_channel")) {
            String channelId = event.getValues().get(0);
            BotConfig.setNotificationChannelId(channelId);

            TextChannel channel = event.getGuild().getTextChannelById(channelId);
            String channelName = channel != null ? channel.getName() : "Desconhecido";

            logger.info("Canal de notificações alterado para #{} por {}", channelName, event.getUser().getName());

            String monitoredUrl = BotConfig.getMonitoredMatchUrl();
            String matchLabel = null;
            if (matchMonitor.getLastState() != null) {
                matchLabel = matchMonitor.getLastState().getMatchLabel()
                        + " (" + matchMonitor.getLastState().getScoreText() + ")";
            }

            event.editMessageEmbeds(GameEmbeds.adminPanel(BotConfig.isBotEnabled(), channelName,
                    monitoredUrl, matchLabel)).queue();
        }
    }

    private net.dv8tion.jda.api.entities.MessageEmbed buildAdminPanel(ButtonInteractionEvent event) {
        String channelName = getChannelName(event);
        String monitoredUrl = BotConfig.getMonitoredMatchUrl();
        String matchLabel = null;
        if (matchMonitor.getLastState() != null) {
            matchLabel = matchMonitor.getLastState().getMatchLabel()
                    + " (" + matchMonitor.getLastState().getScoreText() + ")";
        }
        return GameEmbeds.adminPanel(BotConfig.isBotEnabled(), channelName, monitoredUrl, matchLabel);
    }

    private String getChannelName(ButtonInteractionEvent event) {
        if (BotConfig.getNotificationChannelId() != null) {
            TextChannel ch = event.getGuild().getTextChannelById(BotConfig.getNotificationChannelId());
            if (ch != null) return ch.getName();
        }
        return null;
    }
}
