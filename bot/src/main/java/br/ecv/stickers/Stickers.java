package br.ecv.stickers;

/**
 * Classe de stickers do servidor Discord.
 * Acesso hierárquico: Stickers.Teams.Vitoria, Stickers.Cards.Yellow, etc.
 * Os valores representam os nomes dos stickers no servidor do Discord.
 */
public final class Stickers {

    private Stickers() {}

    /**
     * Stickers dos times.
     */
    public static final class Teams {
        private Teams() {}

        public static final String Vitoria = "ecv_vitoria";
        public static final String VitoriaComemorando = "ecv_vitoria_comemorando";
        public static final String VitoriaTriste = "ecv_vitoria_triste";
        public static final String Adversario = "ecv_adversario";
    }

    /**
     * Stickers de cartões.
     */
    public static final class Cards {
        private Cards() {}

        public static final String Yellow = "ecv_cartao_amarelo";
        public static final String Red = "ecv_cartao_vermelho";
    }

    /**
     * Stickers de eventos de jogo.
     */
    public static final class Events {
        private Events() {}

        public static final String Goal = "ecv_gol";
        public static final String Substitution = "ecv_substituicao";
        public static final String Whistle = "ecv_apito";
        public static final String Trophy = "ecv_trofeu";
        public static final String Victory = "ecv_vitoria_final";
        public static final String Defeat = "ecv_derrota";
        public static final String Draw = "ecv_empate";
    }

    /**
     * Stickers de reações.
     */
    public static final class Reactions {
        private Reactions() {}

        public static final String Celebrate = "ecv_comemorar";
        public static final String Sad = "ecv_triste";
        public static final String Angry = "ecv_raiva";
        public static final String Love = "ecv_amor";
    }

    /**
     * Stickers de jogadores.
     */
    public static final class Players {
        private Players() {}

        public static final String Unknown = "ecv_jogador_desconhecido";
    }
}
