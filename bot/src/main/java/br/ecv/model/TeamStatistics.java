package br.ecv.model;

import com.google.gson.JsonObject;

/**
 * Estatísticas de um time individual durante uma partida.
 */
public class TeamStatistics {

    private int wrongFinish;
    private int goalFinish;
    private int ballOutFinish;
    private int ballOnThePost;
    private int blockedFinish;
    private int penaltyReceived;
    private int cornerKick;
    private int offSide;
    private int defense;
    private int tackle;
    private int foulMade;
    private int yellowCardReceived;
    private int redCardReceived;
    private int ballPossession;
    private int rightPasses;
    private int totalPasses;
    private int wrongPasses;

    public static TeamStatistics fromJson(JsonObject json) {
        TeamStatistics stats = new TeamStatistics();
        stats.wrongFinish = getInt(json, "wrong_finish");
        stats.goalFinish = getInt(json, "goal_finish");
        stats.ballOutFinish = getInt(json, "ball_out_finish");
        stats.ballOnThePost = getInt(json, "ball_on_the_post");
        stats.blockedFinish = getInt(json, "blocked_finish");
        stats.penaltyReceived = getInt(json, "penalty_received");
        stats.cornerKick = getInt(json, "corner_kick");
        stats.offSide = getInt(json, "off_side");
        stats.defense = getInt(json, "defense");
        stats.tackle = getInt(json, "tackle");
        stats.foulMade = getInt(json, "foul_made");
        stats.yellowCardReceived = getInt(json, "yellow_card_received");
        stats.redCardReceived = getInt(json, "red_card_received");
        stats.ballPossession = getInt(json, "ball_possession");
        stats.rightPasses = getInt(json, "right_passes");
        stats.totalPasses = getInt(json, "total_passes");
        stats.wrongPasses = getInt(json, "wrong_passes");
        return stats;
    }

    // ── Getters ──────────────────────────────────────────────────────

    public int getWrongFinish() {
        return wrongFinish;
    }

    public int getGoalFinish() {
        return goalFinish;
    }

    public int getBallOutFinish() {
        return ballOutFinish;
    }

    public int getBallOnThePost() {
        return ballOnThePost;
    }

    public int getBlockedFinish() {
        return blockedFinish;
    }

    public int getPenaltyReceived() {
        return penaltyReceived;
    }

    public int getCornerKick() {
        return cornerKick;
    }

    public int getOffSide() {
        return offSide;
    }

    public int getDefense() {
        return defense;
    }

    public int getTackle() {
        return tackle;
    }

    public int getFoulMade() {
        return foulMade;
    }

    public int getYellowCardReceived() {
        return yellowCardReceived;
    }

    public int getRedCardReceived() {
        return redCardReceived;
    }

    public int getBallPossession() {
        return ballPossession;
    }

    public int getRightPasses() {
        return rightPasses;
    }

    public int getTotalPasses() {
        return totalPasses;
    }

    public int getWrongPasses() {
        return wrongPasses;
    }

    /** Total de finalizações (gol + fora + trave + bloqueada + errada). */
    public int getTotalFinishes() {
        return goalFinish + ballOutFinish + ballOnThePost + blockedFinish + wrongFinish;
    }

    /** Precisão de passes como porcentagem. */
    public int getPassAccuracy() {
        return totalPasses > 0 ? (int) Math.round((double) rightPasses / totalPasses * 100) : 0;
    }

    private static int getInt(JsonObject obj, String key) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsInt();
        }
        return 0;
    }
}
