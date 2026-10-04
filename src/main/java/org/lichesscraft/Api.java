package org.lichesscraft;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

public final class Api implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final String base;
    private volatile long blockedUntil;
    public Api() { this("https://lichess.org"); }
    public Api(String base) { this.base=base; }
    public static String form(Map<String,String> values) {
        return String.join("&", values.entrySet().stream().map(e -> encode(e.getKey())+"="+encode(e.getValue())).toList());
    }
    public static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    public synchronized JsonObject call(String path, String token, Map<String,String> body) throws Exception {
        if(System.currentTimeMillis()<blockedUntil)throw new Error(429,"Lichess-Anfragelimit erreicht. Bitte mindestens eine Minute warten.");
        var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(25));
        if (token != null) request.header("Authorization", "Bearer "+token);
        if (body==null) request.GET(); else request.header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(body)));
        var response=client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()==429)blockedUntil=System.currentTimeMillis()+60_000;
        check(response.statusCode());
        return response.body().isBlank() ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject();
    }
    public InputStream openStream(String path,String token) throws Exception {
        if(System.currentTimeMillis()<blockedUntil)throw new Error(429,"Lichess-Anfragelimit erreicht. Bitte mindestens eine Minute warten.");
        var request=HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+token).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode()!=200) { response.body().close(); if(response.statusCode()==429)blockedUntil=System.currentTimeMillis()+60_000; check(response.statusCode()); }
        return response.body();
    }
    public static void readStream(InputStream input, Consumer<JsonObject> receiver) throws IOException {
        try (var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8))) {
            String line;
            while ((line=reader.readLine())!=null) if (!line.isBlank()) receiver.accept(JsonParser.parseString(line).getAsJsonObject());
        }
    }
    static void check(int status) throws IOException {
        if (status/100!=2) throw new Error(status,switch(status) {
            case 400 -> "Anfrage abgelehnt: Zug, Spielart oder Parameter prüfen.";
            case 401,403 -> "Account-Berechtigung fehlt oder ist abgelaufen. Neu verbinden.";
            case 404 -> "Partie oder Spieler nicht gefunden.";
            case 429 -> "Lichess-Anfragelimit erreicht. Bitte mindestens eine Minute warten.";
            default -> "Lichess antwortet mit HTTP "+status;
        });
    }
    public static final class Error extends IOException {
        private final int status;
        Error(int status,String message) {super(message);this.status=status;}
        public int status(){return status;}
    }
    @Override public void close() { client.shutdownNow(); }
}
