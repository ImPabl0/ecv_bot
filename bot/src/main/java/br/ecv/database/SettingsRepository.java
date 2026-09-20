package br.ecv.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repositório das configurações persistentes do bot (canal de notificações,
 * cargo mencionado, etc). Tudo fica em um único documento na coleção
 * {@code settings}, identificado por {@code _id = "bot"}.
 *
 * As escritas são assíncronas de propósito: elas são disparadas a partir de
 * interações do Discord, que precisam ser respondidas em até 3 segundos. Se o
 * MongoDB estiver lento ou fora do ar, a gravação falha em segundo plano e a
 * configuração continua valendo em memória, sem travar a thread do gateway.
 */
public class SettingsRepository {

    private static final Logger logger = LoggerFactory.getLogger(SettingsRepository.class);
    private static final String COLLECTION_NAME = "settings";
    private static final String DOC_ID = "bot";

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "settings-writer");
        t.setDaemon(true);
        return t;
    });

    private static MongoCollection<Document> getCollection() {
        return MongoConnection.getDatabase().getCollection(COLLECTION_NAME);
    }

    /**
     * Lê o documento de configurações. Retorna {@code null} se ainda não existir
     * ou se o banco não estiver disponível.
     */
    public static Document load() {
        try {
            return getCollection().find(Filters.eq("_id", DOC_ID)).first();
        } catch (Exception e) {
            logger.warn("Não foi possível carregar as configurações do MongoDB: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Agenda a gravação (ou remoção, quando {@code value} é nulo) de um campo das
     * configurações. Retorna imediatamente.
     */
    public static void save(String key, String value) {
        WRITER.execute(() -> {
            try {
                getCollection().updateOne(
                        Filters.eq("_id", DOC_ID),
                        value == null ? Updates.unset(key) : Updates.set(key, value),
                        new UpdateOptions().upsert(true));
                logger.debug("Configuração '{}' salva no MongoDB.", key);
            } catch (Exception e) {
                logger.warn("Não foi possível salvar '{}' nas configurações: {}", key, e.getMessage());
            }
        });
    }

    public static void shutdown() {
        WRITER.shutdown();
    }
}
