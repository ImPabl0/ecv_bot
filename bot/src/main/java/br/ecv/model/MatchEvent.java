package br.ecv.model;

import com.google.gson.JsonObject;

/**
 * Representa um evento individual de uma partida (gol, cartão, substituição, lance, etc).
 */
public class MatchEvent {

    private String minute;
    private String period;
    private String periodId;
    private String type;         // GOAL, SUBSTITUTION, CARD, IMPORTANT, NORMAL, SUMMARY_AUTOMATIC
    private String typeLabel;
    private String title;
    private String description;
    private String team;         // Abreviação do time (ex: SAN, GRE)
    private int teamId;
    private String player;
    private String playerIn;     // Substituição: quem entrou
    private String playerOut;    // Substituição: quem saiu
    private String goalKind;     // REGULAR_GOAL, PENALTY_GOAL, OWN_GOAL
    private String cardType;     // YELLOW, RED, SECOND_YELLOW
    private String image;        // URL da foto do jogador (quando disponível)

    public static MatchEvent fromJson(JsonObject json) {
        MatchEvent event = new MatchEvent();
        event.minute = getStr(json, "minute");
        event.period = getStr(json, "period");
        event.periodId = getStr(json, "period_id");
        event.type = getStr(json, "type");
        event.typeLabel = getStr(json, "type_label");
        event.title = getStr(json, "title");
        event.description = getStr(json, "description");
        event.team = getStr(json, "team");
        event.teamId = getInt(json, "team_id");
        event.player = getStr(json, "player");
        event.playerIn = getStr(json, "player_in");
        event.playerOut = getStr(json, "player_out");
        event.goalKind = getStr(json, "goal_kind");
        event.cardType = getStr(json, "card_type");
        event.image = getStr(json, "image");
        return event;
    }

    // ── Getters ──────────────────────────────────────────────────────

    public String getMinute() { return minute; }
    public String getPeriod() { return period; }
    public String getPeriodId() { return periodId; }
    public String getType() { return type; }
    public String getTypeLabel() { return typeLabel; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public String getTeam() { return team; }
    public int getTeamId() { return teamId; }
    public String getPlayer() { return player; }
    public String getPlayerIn() { return playerIn; }
    public String getPlayerOut() { return playerOut; }
    public String getGoalKind() { return goalKind; }
    public String getCardType() { return cardType; }
    public String getImage() { return image; }

    public boolean hasImage() { return image != null && !image.isEmpty(); }

    public boolean isGoal() { return "GOAL".equals(type); }
    public boolean isCard() { return "CARD".equals(type); }
    public boolean isSubstitution() { return "SUBSTITUTION".equals(type); }
    public boolean isImportant() { return "IMPORTANT".equals(type); }
    public boolean isNormal() { return "NORMAL".equals(type); }
    public boolean isSummary() { return "SUMMARY_AUTOMATIC".equals(type); }

    /**
     * Gera uma chave única para este evento (para detecção de duplicatas).
     */
    public String uniqueKey() {
        return type + "|" + minute + "|" + player + "|" + playerIn + "|" + playerOut + "|" + title;
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
