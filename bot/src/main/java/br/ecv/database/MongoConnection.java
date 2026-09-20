package br.ecv.database;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Gerencia a conexão com o MongoDB.
 */
public class MongoConnection {

    private static final Logger logger = LoggerFactory.getLogger(MongoConnection.class);

    /**
     * O padrão do driver é 30s. Como o bot precisa responder interações do
     * Discord em até 3s, uma indisponibilidade do banco tem que falhar rápido
     * em vez de segurar a thread que chamou.
     */
    private static final int SERVER_SELECTION_TIMEOUT_SECONDS = 5;

    private static MongoClient client;
    private static MongoDatabase database;

    public static void initialize(Dotenv dotenv) {
        String uri = dotenv.get("MONGODB_URI", "mongodb://localhost:27017");
        String dbName = dotenv.get("MONGODB_DATABASE", "ecv_bot");

        MongoClientSettings settings = MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString(uri))
                .applyToClusterSettings(b -> b.serverSelectionTimeout(
                        SERVER_SELECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                .build();

        client = MongoClients.create(settings);
        database = client.getDatabase(dbName);

        logger.info("MongoDB conectado ao banco: {}", dbName);
    }

    public static MongoDatabase getDatabase() {
        return database;
    }

    public static void close() {
        SettingsRepository.shutdown();
        if (client != null) {
            client.close();
            logger.info("Conexão MongoDB encerrada.");
        }
    }
}
