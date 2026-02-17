package br.ecv.database;

import br.ecv.model.Player;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import org.bson.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * Repositório para operações CRUD de jogadores no MongoDB.
 */
public class PlayerRepository {

    private static final String COLLECTION_NAME = "players";

    private MongoCollection<Document> getCollection() {
        return MongoConnection.getDatabase().getCollection(COLLECTION_NAME);
    }

    public void addPlayer(Player player) {
        Document doc = new Document()
                .append("nome", player.getNome())
                .append("numero", player.getNumero())
                .append("posicao", player.getPosicao())
                .append("time", player.getTime())
                .append("stickerId", player.getStickerId());
        getCollection().insertOne(doc);
    }

    public boolean removePlayer(String nome) {
        var result = getCollection().deleteOne(Filters.regex("nome", "(?i)" + nome));
        return result.getDeletedCount() > 0;
    }

    public Player findByName(String nome) {
        Document doc = getCollection().find(Filters.regex("nome", "(?i)" + nome)).first();
        if (doc == null) return null;
        return documentToPlayer(doc);
    }

    /**
     * Busca jogador pelo nome e abreviação do time.
     * Primeiro tenta nome exato (case-insensitive), depois busca parcial.
     */
    public Player findByNameAndTeam(String nome, String teamAbbreviation) {
        if (nome == null || nome.isBlank()) return null;

        // Busca exata por nome + time
        if (teamAbbreviation != null && !teamAbbreviation.isBlank()) {
            Document doc = getCollection().find(
                    Filters.and(
                            Filters.regex("nome", "(?i)^" + escapeRegex(nome) + "$"),
                            Filters.regex("time", "(?i)" + escapeRegex(teamAbbreviation))
                    )
            ).first();
            if (doc != null) return documentToPlayer(doc);
        }

        // Busca parcial pelo nome (contém)
        Document doc = getCollection().find(
                Filters.regex("nome", "(?i)" + escapeRegex(nome))
        ).first();
        if (doc != null) return documentToPlayer(doc);

        return null;
    }

    public List<Player> findAll() {
        List<Player> players = new ArrayList<>();
        for (Document doc : getCollection().find()) {
            players.add(documentToPlayer(doc));
        }
        return players;
    }

    private Player documentToPlayer(Document doc) {
        Player player = new Player();
        player.setNome(doc.getString("nome"));
        player.setNumero(doc.getInteger("numero", 0));
        player.setPosicao(doc.getString("posicao"));
        player.setTime(doc.getString("time"));
        player.setStickerId(doc.getString("stickerId"));
        return player;
    }

    private String escapeRegex(String input) {
        return input.replaceAll("([\\\\\\[\\]{}()*+?.^$|])", "\\\\$1");
    }
}
