package org.lichesscraft;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ApiOAuthTest {
    @TempDir Path directory;
    HttpServer fake;
    Api api;
    final AtomicReference<String> posted=new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        fake=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        fake.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();
            posted.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String value=switch(path){case "/api/token"->"{\"access_token\":\"FAKE_TEST_TOKEN\",\"expires_in\":3600}";case "/api/account"->"{\"username\":\"TestPlayer\"}";case "/stream"->"\n{\"type\":\"gameFull\"}\n\n{\"type\":\"gameState\"}\n";default->"{}";};
            byte[] bytes=value.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(path.equals("/limited")?429:200,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}
        });fake.start();api=new Api("http://127.0.0.1:"+fake.getAddress().getPort());
    }
    @AfterEach void teardown(){api.close();fake.stop(0);}
    static Map<String,String> query(String raw){Map<String,String> values=new HashMap<>();for(String item:raw.split("&")){String[] pair=item.split("=",2);values.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8),URLDecoder.decode(pair[1],StandardCharsets.UTF_8));}return values;}
    @Test void pkceLoginRequiresIngameConfirmationAndCorrectPlayer() throws Exception {
        var store=new AccountStore(directory);UUID id=UUID.randomUUID();
        try(var oauth=new OAuth(api,store,"https://example.invalid","127.0.0.1",0,"test-client")) {
            Map<String,String> request=query(URI.create(oauth.begin(id)).getRawQuery());assertEquals("S256",request.get("code_challenge_method"));assertTrue(request.get("scope").contains("board:play"));
            String url="http://127.0.0.1:"+oauth.localPort()+"/callback?state="+request.get("state")+"&code=fake_code";
            try(var client=HttpClient.newHttpClient()) {
                var response=client.send(HttpRequest.newBuilder(URI.create(url)).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());assertNull(store.get(id));
                String code=response.body().split("/schach bestaetigen ")[1].split("\\.")[0];
                assertThrows(IllegalArgumentException.class,()->oauth.confirm(UUID.randomUUID(),code));assertThrows(IllegalArgumentException.class,()->oauth.confirm(id,"wrong"));
                assertEquals("TestPlayer",oauth.confirm(id,code).username());assertNotNull(store.get(id));assertThrows(IllegalArgumentException.class,()->oauth.confirm(id,code));
                assertEquals(400,client.send(HttpRequest.newBuilder(URI.create(url)).build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            }
        }
    }
    @Test void replacedLoginRejectsOldState() throws Exception {
        UUID id=UUID.randomUUID();try(var oauth=new OAuth(api,new AccountStore(directory),"https://example.invalid","127.0.0.1",0,"test")) {
            String state=query(URI.create(oauth.begin(id)).getRawQuery()).get("state");oauth.begin(id);
            try(var client=HttpClient.newHttpClient()){assertEquals(400,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+oauth.localPort()+"/callback?state="+state+"&code=fake")).build(),HttpResponse.BodyHandlers.ofString()).statusCode());}
        }
    }
    @Test void insecurePublicCallbackRejected() throws Exception {assertThrows(IllegalArgumentException.class,()->new OAuth(api,new AccountStore(directory),"http://example.invalid","127.0.0.1",0,"test"));}
    @Test void streamHandlesHeartbeatAndMultipleEvents() throws Exception {List<String> types=new ArrayList<>();Api.readStream(api.openStream("/stream","fake"),event->types.add(event.get("type").getAsString()));assertEquals(List.of("gameFull","gameState"),types);}
    @Test void formEncodingAndRateLimitBackoff() throws Exception {
        api.call("/post",null,Map.of("code","a+b &=é"));assertEquals("a+b &=é",query(posted.get()).get("code"));
        assertEquals(429,assertThrows(Api.Error.class,()->api.call("/limited",null,null)).status());assertEquals(429,assertThrows(Api.Error.class,()->api.call("/post",null,null)).status());
    }
}
