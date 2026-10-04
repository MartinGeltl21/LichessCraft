package org.lichesscraft;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public final class OAuth implements AutoCloseable {
    private record Pending(UUID player,String verifier,long deadline) {}
    private record Confirmation(String code,AccountStore.Account account,long deadline) {}
    private final Map<String,Pending> pending=new ConcurrentHashMap<>();
    private final Map<UUID,Confirmation> confirmations=new ConcurrentHashMap<>();
    private final Api api;
    private final AccountStore store;
    private final String publicUrl,clientId;
    private final HttpServer server;
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    public OAuth(Api api,AccountStore store,String publicUrl,String bind,int port,String clientId) throws IOException {
        this.api=api; this.store=store; this.publicUrl=publicUrl.replaceAll("/+$",""); this.clientId=clientId;
        if (!URI.create(this.publicUrl).getScheme().equals("https")) throw new IllegalArgumentException("OAuth public-url muss HTTPS verwenden.");
        server=HttpServer.create(new InetSocketAddress(bind,port),16);
        server.setExecutor(executor); server.createContext("/callback",this::callback);
        server.createContext("/",exchange->reply(exchange,200,"LichessCraft läuft. Starte die Anmeldung mit /schach verbinden in Minecraft."));
        server.start();
    }
    public String begin(UUID player) throws Exception {
        clean(); pending.entrySet().removeIf(e->e.getValue().player().equals(player)); confirmations.remove(player);
        String state=random(),verifier=random();
        pending.put(state,new Pending(player,verifier,System.currentTimeMillis()+300_000));
        String challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        return "https://lichess.org/oauth?"+Api.form(Map.of("response_type","code","client_id",clientId,"redirect_uri",publicUrl+"/callback","code_challenge_method","S256","code_challenge",challenge,"scope","board:play challenge:write challenge:read","state",state));
    }
    public AccountStore.Account confirm(UUID player,String code) throws Exception {
        Confirmation confirmation=confirmations.get(player);
        if (confirmation==null || confirmation.deadline()<System.currentTimeMillis() || !MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),confirmation.code().getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("Bestätigungscode ungültig oder abgelaufen.");
        store.put(player,confirmation.account()); confirmations.remove(player,confirmation);
        return confirmation.account();
    }
    public void cancel(UUID player) { pending.entrySet().removeIf(e->e.getValue().player().equals(player)); confirmations.remove(player); }
    int localPort() { return server.getAddress().getPort(); }
    private void callback(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestMethod().equals("GET") || !exchange.getRequestURI().getPath().equals("/callback")) { reply(exchange,405,"Ungültige Anfrage."); return; }
            clean();
            String query=exchange.getRequestURI().getRawQuery();
            if(query==null || query.length()>4096) { reply(exchange,400,"Ungültiger Rückruf."); return; }
            Map<String,String> values=new HashMap<>();
            for(String pair:query.split("&")) { String[] parts=pair.split("=",2); values.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),parts.length>1?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):""); }
            Pending session=pending.remove(values.getOrDefault("state",""));
            if(session==null || session.deadline()<System.currentTimeMillis()) { reply(exchange,400,"Anmeldung abgelaufen. Bitte in Minecraft erneut verbinden."); return; }
            if(values.containsKey("error") || !values.containsKey("code")) { reply(exchange,400,"Anmeldung abgebrochen. Du kannst das Fenster schließen."); return; }
            JsonObject token=api.call("/api/token",null,Map.of("grant_type","authorization_code","code",values.get("code"),"code_verifier",session.verifier(),"redirect_uri",publicUrl+"/callback","client_id",clientId));
            String access=token.get("access_token").getAsString();
            JsonObject account=api.call("/api/account",access,null);
            String username=account.get("username").getAsString();
            if(!username.matches("[A-Za-z0-9_-]{1,40}")) throw new IllegalArgumentException("Invalid username");
            long ttl=token.has("expires_in")?token.get("expires_in").getAsLong():0;
            if(ttl<=0) throw new IllegalArgumentException("Token expiry missing");
            String code=random().substring(0,12);
            confirmations.put(session.player(),new Confirmation(code,new AccountStore.Account(username,access,System.currentTimeMillis()+Math.min(ttl,315360000L)*1000L),System.currentTimeMillis()+300_000));
            reply(exchange,200,"Lichess-Account: "+username+". Prüfe den Namen und bestätige innerhalb von 5 Minuten in Minecraft mit: /schach bestaetigen "+code+". Erst dann wird der Account gespeichert.");
        } catch(Exception error) { reply(exchange,502,"Verbindung zu Lichess fehlgeschlagen. Bitte /schach verbinden erneut ausführen."); }
    }
    private void clean() { long now=System.currentTimeMillis(); pending.entrySet().removeIf(e->e.getValue().deadline()<now); confirmations.entrySet().removeIf(e->e.getValue().deadline()<now); }
    static String random() { byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static void reply(HttpExchange exchange,int status,String message) throws IOException {
        byte[] bytes=message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");
        exchange.getResponseHeaders().set("Referrer-Policy","no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; frame-ancestors 'none'");
        exchange.sendResponseHeaders(status,bytes.length); try(var output=exchange.getResponseBody()) { output.write(bytes); }
    }
    @Override public void close() { server.stop(0); executor.shutdownNow(); pending.clear(); confirmations.clear(); }
}
