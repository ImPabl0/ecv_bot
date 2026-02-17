package br.ecv.model;

import org.bson.codecs.pojo.annotations.BsonId;
import org.bson.types.ObjectId;

/**
 * Modelo de jogador armazenado no MongoDB.
 */
public class Player {

    @BsonId
    private ObjectId id;
    private String nome;
    private int numero;
    private String posicao;
    private String time;
    private String stickerId;

    public Player() {}

    public Player(String nome, int numero, String posicao, String time, String stickerId) {
        this.nome = nome;
        this.numero = numero;
        this.posicao = posicao;
        this.time = time;
        this.stickerId = stickerId;
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public String getNome() {
        return nome;
    }

    public void setNome(String nome) {
        this.nome = nome;
    }

    public int getNumero() {
        return numero;
    }

    public void setNumero(int numero) {
        this.numero = numero;
    }

    public String getPosicao() {
        return posicao;
    }

    public void setPosicao(String posicao) {
        this.posicao = posicao;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getStickerId() {
        return stickerId;
    }

    public void setStickerId(String stickerId) {
        this.stickerId = stickerId;
    }

    @Override
    public String toString() {
        return String.format("#%d %s (%s) - %s", numero, nome, posicao, time);
    }
}
