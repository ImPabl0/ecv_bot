package br.ecv;

import br.ecv.api.ApiFootballClient;
import br.ecv.config.BotConfig;
import br.ecv.database.MongoConnection;
import br.ecv.listeners.CommandListener;
import br.ecv.listeners.ButtonListener;
import br.ecv.monitor.MatchMonitor;
import io.github.cdimascio.dotenv.Dotenv;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

        private static final Logger logger = LoggerFactory.getLogger(Main.class);

        public static void main(String[] args) throws Exception {
                Dotenv dotenv = Dotenv.load();

                String token = dotenv.get("DISCORD_TOKEN");
                if (token == null || token.isBlank()) {
                        logger.error("DISCORD_TOKEN não encontrado no .env!");
                        return;
                }

                // Inicializar conexão MongoDB
                MongoConnection.initialize(dotenv);
                logger.info("Conexão com MongoDB estabelecida.");

                // Carregar configuração do bot
                BotConfig.load(dotenv);

                // Inicializar cliente da API Football
                ApiFootballClient apiClient = new ApiFootballClient(BotConfig.getApiFootballUrl());
                logger.info("API Football URL: {}", BotConfig.getApiFootballUrl());

                // Construir JDA
                JDA jda = JDABuilder.createDefault(token)
                                .setStatus(OnlineStatus.ONLINE)
                                .setActivity(Activity.watching("os jogos do Vitória \uD83E\uDD81"))
                                .enableIntents(
                                                GatewayIntent.GUILD_MESSAGES,
                                                GatewayIntent.MESSAGE_CONTENT,
                                                GatewayIntent.GUILD_MEMBERS)
                                .build();

                jda.awaitReady();
                logger.info("Leão Bot está online! \uD83E\uDD81");

                // Inicializar MatchMonitor
                MatchMonitor matchMonitor = new MatchMonitor(jda, apiClient, BotConfig.getPollInterval());

                // Registrar listeners (passando dependências)
                jda.addEventListener(
                                new CommandListener(apiClient, matchMonitor),
                                new ButtonListener(matchMonitor));

                // Registrar slash commands
                jda.updateCommands().addCommands(
                                Commands.slash("painel", "Abre o painel administrativo do Leão Bot"),
                                Commands.slash("monitorar", "Monitora uma partida pelo link do ge.globo.com")
                                                .addOption(OptionType.STRING, "url", "URL da partida no ge.globo.com",
                                                                true),
                                Commands.slash("parar", "Para o monitoramento da partida atual"),
                                Commands.slash("placar", "Mostra o placar da partida monitorada"),
                                Commands.slash("estatisticas", "Mostra as estatísticas da partida monitorada"),
                                Commands.slash("jogador", "Gerenciar jogadores no banco de dados")
                                                .addSubcommands(
                                                                new SubcommandData("adicionar", "Adiciona um jogador")
                                                                                .addOption(OptionType.STRING, "nome",
                                                                                                "Nome do jogador", true)
                                                                                .addOption(OptionType.INTEGER, "numero",
                                                                                                "Número da camisa",
                                                                                                true)
                                                                                .addOption(OptionType.STRING, "posicao",
                                                                                                "Posição do jogador",
                                                                                                true)
                                                                                .addOption(OptionType.STRING, "time",
                                                                                                "Abreviação do time (ex: VIT, FLA)",
                                                                                                true)
                                                                                .addOption(OptionType.STRING,
                                                                                                "sticker_id",
                                                                                                "Nome do sticker no Discord",
                                                                                                false),
                                                                new SubcommandData("remover", "Remove um jogador")
                                                                                .addOption(OptionType.STRING, "nome",
                                                                                                "Nome do jogador",
                                                                                                true),
                                                                new SubcommandData("listar",
                                                                                "Lista todos os jogadores")))
                                .queue();

                logger.info("Slash commands registrados com sucesso.");

                // Shutdown hook
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                        matchMonitor.stop();
                        MongoConnection.close();
                        jda.shutdown();
                        logger.info("Leão Bot desligado.");
                }));
        }
}
