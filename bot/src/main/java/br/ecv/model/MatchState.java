package br.ecv.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Representa o estado completo de uma partida retornada pela API Football.
 */
public class MatchState {

    private String homeTeamName;
    private String homeTeamAbbreviation;
    private int homeTeamId;
    private String homeTeamBadge;

    private String awayTeamName;
    private String awayTeamAbbreviation;
    private int awayTeamId;
    private String awayTeamBadge;

    private int homeScore;
    private int awayScore;

    private String period;
    private String periodId;
    private String currentTime;
    private String championship;
    private String stadium;
    private String sseUrl;

    private List<MatchEvent> events = new ArrayList<>();

    // Estatísticas
    private MatchStatistics statistics;

    // Metadados do tracker
    private boolean trackerRunning;
    private boolean sseConnected;

    /**
     * Cria um MatchState a partir do JSON retornado pela API.
     */
    public static MatchState fromJson(JsonObject json) {
        MatchState state = new MatchState();

        // Home team
        JsonObject home = json.getAsJsonObject("home_team");
        if (home != null) {
            state.homeTeamName = getStr(home, "name");
            state.homeTeamAbbreviation = getStr(home, "abbreviation");
            state.homeTeamId = getInt(home, "id");
            state.homeTeamBadge = getStr(home, "badge_png");
        }

        // Away team
        JsonObject away = json.getAsJsonObject("away_team");
        if (away != null) {
            state.awayTeamName = getStr(away, "name");
            state.awayTeamAbbreviation = getStr(away, "abbreviation");
            state.awayTeamId = getInt(away, "id");
            state.awayTeamBadge = getStr(away, "badge_png");
        }

        state.homeScore = getInt(json, "home_score");
        state.awayScore = getInt(json, "away_score");
        state.period = getStr(json, "period");
        state.periodId = getStr(json, "period_id");
        state.currentTime = getStr(json, "current_time");
        state.championship = getStr(json, "championship");
        state.stadium = getStr(json, "stadium");
        state.sseUrl = getStr(json, "sse_url");

        // Eventos
        JsonArray eventsArray = json.getAsJsonArray("events");
        if (eventsArray != null) {
            for (JsonElement el : eventsArray) {
                if (el.isJsonObject()) {
                    state.events.add(MatchEvent.fromJson(el.getAsJsonObject()));
                }
            }
        }

        // Estatísticas
        if (json.has("statistics") && !json.get("statistics").isJsonNull()) {
            JsonObject statsJson = json.getAsJsonObject("statistics");
            if (statsJson != null) {
                state.statistics = MatchStatistics.fromApiJson(statsJson, state.homeTeamName, state.awayTeamName);
            }
        }

        // Tracker info
        JsonObject tracker = json.getAsJsonObject("_tracker");
        if (tracker != null) {
            state.trackerRunning = tracker.has("is_running") && tracker.get("is_running").getAsBoolean();
            state.sseConnected = tracker.has("sse_connected") && tracker.get("sse_connected").getAsBoolean();
        }

        return state;
    }

    // ── Getters ──────────────────────────────────────────────────────

    public String getHomeTeamName() {
        return homeTeamName;
    }

    public String getHomeTeamAbbreviation() {
        return homeTeamAbbreviation;
    }

    public int getHomeTeamId() {
        return homeTeamId;
    }

    public String getHomeTeamBadge() {
        return homeTeamBadge;
    }

    public String getAwayTeamName() {
        return awayTeamName;
    }

    public String getAwayTeamAbbreviation() {
        return awayTeamAbbreviation;
    }

    public int getAwayTeamId() {
        return awayTeamId;
    }

    public String getAwayTeamBadge() {
        return awayTeamBadge;
    }

    public int getHomeScore() {
        return homeScore;
    }

    public int getAwayScore() {
        return awayScore;
    }

    public String getPeriod() {
        return period;
    }

    public String getPeriodId() {
        return periodId;
    }

    public String getCurrentTime() {
        return currentTime;
    }

    public String getChampionship() {
        return championship;
    }

    public String getStadium() {
        return stadium;
    }

    public String getSseUrl() {
        return sseUrl;
    }

    public List<MatchEvent> getEvents() {
        return events;
    }

    public MatchStatistics getStatistics() {
        return statistics;
    }

    public void setStatistics(MatchStatistics statistics) {
        this.statistics = statistics;
    }

    public void setHomeScore(int homeScore) {
        this.homeScore = homeScore;
    }

    public void setAwayScore(int awayScore) {
        this.awayScore = awayScore;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public void setCurrentTime(String currentTime) {
        this.currentTime = currentTime;
    }

    public boolean isTrackerRunning() {
        return trackerRunning;
    }

    public boolean isSseConnected() {
        return sseConnected;
    }

    public String getScoreText() {
        return String.format("%s %d x %d %s",
                homeTeamAbbreviation, homeScore, awayScore, awayTeamAbbreviation);
    }

    public String getMatchLabel() {
        return String.format("%s x %s", homeTeamName, awayTeamName);
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static String getStr(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }

    private static int getInt(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return 0;
    }
}
