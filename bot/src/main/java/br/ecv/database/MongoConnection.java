package br.ecv.database;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.github.cdimascio.dotenv.Dotenv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gerencia a conexão com o MongoDB.
 */
public class MongoConnection {

    private static final Logger logger = LoggerFactory.getLogger(MongoConnection.class);
    private static MongoClient client;
    private static MongoDatabase database;

    public static void initialize(Dotenv dotenv) {
        String uri = dotenv.get("MONGODB_URI", "mongodb://localhost:27017");
        String dbName = dotenv.get("MONGODB_DATABASE", "ecv_bot");

        client = MongoClients.create(uri);
        database = client.getDatabase(dbName);

        logger.info("MongoDB conectado ao banco: {}", dbName);
    }

    public static MongoDatabase getDatabase() {
        return database;
    }

    public static void close() {
        if (client != null) {
            client.close();
            logger.info("Conexão MongoDB encerrada.");
        }
    }
}
