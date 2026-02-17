package br.ecv.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Cliente HTTP para a API Football (Flask).
 * Consome os endpoints: GET /match?url=..., DELETE /match?url=..., GET /trackers
 */
public class ApiFootballClient {

    private static final Logger logger = LoggerFactory.getLogger(ApiFootballClient.class);
    private final String baseUrl;
    private final OkHttpClient httpClient;

    public ApiFootballClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Busca os dados de uma partida pela URL do ge.globo.com.
     * Cria o tracker automaticamente se não existir.
     */
    public JsonObject getMatch(String geUrl) throws IOException {
        String encodedUrl = URLEncoder.encode(geUrl, StandardCharsets.UTF_8);
        String url = baseUrl + "/match?url=" + encodedUrl;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                logger.error("API Football retornou {}: {}", response.code(), body);
                throw new IOException("API retornou código " + response.code() + ": " + body);
            }

            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    /**
     * Remove o tracker de uma partida.
     */
    public boolean removeMatch(String geUrl) throws IOException {
        String encodedUrl = URLEncoder.encode(geUrl, StandardCharsets.UTF_8);
        String url = baseUrl + "/match?url=" + encodedUrl;

        Request request = new Request.Builder()
                .url(url)
                .delete()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            return response.isSuccessful();
        }
    }

    /**
     * Lista todos os trackers ativos.
     */
    public JsonObject getTrackers() throws IOException {
        String url = baseUrl + "/trackers";

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "{}";
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    /**
     * Verifica se a API está acessível.
     */
    public boolean isAvailable() {
        try {
            Request request = new Request.Builder()
                    .url(baseUrl + "/")
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                return response.isSuccessful();
            }
        } catch (Exception e) {
            return false;
        }
    }
}
