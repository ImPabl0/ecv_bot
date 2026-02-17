package br.ecv.model;

import com.google.gson.JsonObject;

/**
 * Estatísticas completas de uma partida (ambos os times).
 * Atualizado em tempo real via SSE quando a Globo envia dataType "statistics".
 */
public class MatchStatistics {

    private String homeTeamName;
    private String awayTeamName;
    private TeamStatistics homeTeam;
    private TeamStatistics awayTeam;
    private String lastUpdated;

    public static MatchStatistics fromJson(JsonObject json) {
        MatchStatistics stats = new MatchStatistics();
        stats.homeTeamName = getStr(json, "home_team_name");
        stats.awayTeamName = getStr(json, "away_team_name");
        stats.lastUpdated = getStr(json, "last_updated");

        JsonObject homeObj = json.getAsJsonObject("home_team");
        if (homeObj != null) {
            stats.homeTeam = TeamStatistics.fromJson(homeObj);
        } else {
            stats.homeTeam = new TeamStatistics();
        }

        JsonObject awayObj = json.getAsJsonObject("away_team");
        if (awayObj != null) {
            stats.awayTeam = TeamStatistics.fromJson(awayObj);
        } else {
            stats.awayTeam = new TeamStatistics();
        }

        return stats;
    }

    /**
     * Cria MatchStatistics a partir de um JSON da API /match (campo "statistics").
     * Neste formato os nomes dos times não estão dentro de statistics,
     * então precisam ser passados externamente.
     */
    public static MatchStatistics fromApiJson(JsonObject json, String homeTeamName, String awayTeamName) {
        MatchStatistics stats = new MatchStatistics();
        stats.homeTeamName = homeTeamName;
        stats.awayTeamName = awayTeamName;
        stats.lastUpdated = getStr(json, "last_updated");

        JsonObject homeObj = json.getAsJsonObject("home_team");
        if (homeObj != null) {
            stats.homeTeam = TeamStatistics.fromJson(homeObj);
        } else {
            stats.homeTeam = new TeamStatistics();
        }

        JsonObject awayObj = json.getAsJsonObject("away_team");
        if (awayObj != null) {
            stats.awayTeam = TeamStatistics.fromJson(awayObj);
        } else {
            stats.awayTeam = new TeamStatistics();
        }

        return stats;
    }

    // ── Getters ──────────────────────────────────────────────────────

    public String getHomeTeamName() {
        return homeTeamName;
    }

    public String getAwayTeamName() {
        return awayTeamName;
    }

    public TeamStatistics getHomeTeam() {
        return homeTeam;
    }

    public TeamStatistics getAwayTeam() {
        return awayTeam;
    }

    public String getLastUpdated() {
        return lastUpdated;
    }

    public void setHomeTeamName(String homeTeamName) {
        this.homeTeamName = homeTeamName;
    }

    public void setAwayTeamName(String awayTeamName) {
        this.awayTeamName = awayTeamName;
    }

    private static String getStr(JsonObject obj, String key) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }
}
