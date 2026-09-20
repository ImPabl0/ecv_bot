package br.ecv.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import com.mongodb.client.model.UpdateOptions;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Repositório das configurações persistentes do bot (canal de notificações,
 * cargo mencionado, etc). Tudo fica em um único documento na coleção
 * {@code settings}, identificado por {@code _id = "bot"}.
 */
public class SettingsRepository {

    private static final Logger logger = LoggerFactory.getLogger(SettingsRepository.class);
    private static final String COLLECTION_NAME = "settings";
    private static final String DOC_ID = "bot";

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
     * Salva (ou remove, quando {@code value} é nulo) um campo das configurações.
     */
    public static void save(String key, String value) {
        try {
            getCollection().updateOne(
                    Filters.eq("_id", DOC_ID),
                    value == null ? Updates.unset(key) : Updates.set(key, value),
                    new UpdateOptions().upsert(true));
        } catch (Exception e) {
            logger.warn("Não foi possível salvar '{}' nas configurações: {}", key, e.getMessage());
        }
    }
}
