package org.lichesscraft;

import com.sun.net.httpserver.HttpServer;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class PaperSmoke extends JavaPlugin {
    private ChessPlugin chess;
    private HttpServer fake;
    private Api fakeApi;
    private final AtomicReference<String> moves=new AtomicReference<>("");
    private final AtomicReference<String> status=new AtomicReference<>("started");
    private final AtomicInteger moveRequests=new AtomicInteger();
    private final AtomicInteger streams=new AtomicInteger();
    private int checks;
    private Player white,black;
    private final List<String> messages=new ArrayList<>();
    private final UUID whiteId=new UUID(1,1),blackId=new UUID(2,2);
    private final AtomicInteger updateVersion=new AtomicInteger();
    @Override public void onEnable(){Bukkit.getScheduler().runTaskLater(this,()->{try{run();}catch(Throwable e){fail(e);}},5);}
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;getLogger().info("CHECK "+message);}
    private Object field(String name)throws Exception{Field f=ChessPlugin.class.getDeclaredField(name);f.setAccessible(true);return f.get(chess);}
    private void set(String name,Object value)throws Exception{Field f=ChessPlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(chess,value);}
    private void method(String name)throws Exception{Method m=ChessPlugin.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(chess);}
    private Player player(UUID id,String name,boolean op,Location location){
        return (Player)java.lang.reflect.Proxy.newProxyInstance(Player.class.getClassLoader(),new Class[]{Player.class},(proxy,m,args)->switch(m.getName()){
            case "getUniqueId"->id;case "getName"->name;case "isOp"->op;case "hasPermission"->op || args[0].equals("lichesscraft.play");case "getLocation"->location.clone();case "getWorld"->location.getWorld();case "sendMessage"->{if(args!=null)messages.add(Arrays.toString(args));yield null;}case "toString"->name;case "hashCode"->id.hashCode();case "equals"->proxy==args[0];default->m.getReturnType().isPrimitive()?primitive(m.getReturnType()):null;});
    }
    private static Object primitive(Class<?> type){if(type==boolean.class)return false;if(type==int.class)return 0;if(type==long.class)return 0L;if(type==float.class)return 0f;if(type==double.class)return 0d;if(type==short.class)return (short)0;if(type==byte.class)return (byte)0;if(type==char.class)return (char)0;return null;}
    private void command(Player player,String...args){chess.onCommand(player,Objects.requireNonNull(chess.getCommand("schach")),"schach",args);}
    private void later(BooleanSupplier condition,Runnable task){waitFor(condition,task,0);}
    private void waitFor(BooleanSupplier condition,Runnable task,int tries){Bukkit.getScheduler().runTaskLater(this,()->{try{if(condition.getAsBoolean())task.run();else if(tries>200)throw new AssertionError("Timed out waiting for game state");else waitFor(condition,task,tries+1);}catch(Throwable e){fail(e);}},1);}
    private boolean connected(){try{return (boolean)field("connected");}catch(Exception e){throw new RuntimeException(e);}}
    private boolean sameMoves(String expected){try{return expected.equals(field("confirmedMoves"));}catch(Exception e){throw new RuntimeException(e);}}
    private void run()throws Exception{
        chess=(ChessPlugin)Bukkit.getPluginManager().getPlugin("LichessCraft");check(chess!=null && chess.isEnabled(),"plugin enabled");
        check(chess.getCommand("schach")!=null,"command registered");
        World world=Bukkit.getWorlds().getFirst();Location origin=new Location(world,64,100,64);
        white=player(whiteId,"TestWhite",true,origin);black=player(blackId,"TestBlack",false,origin);
        if(Files.exists(getDataFolder().toPath().resolve("phase1.done"))){
            check(chess.boardForTest().origin()!=null,"board restored after actual server restart");
            check(chess.boardForTest().origin().getBlockX()==64,"board coordinates restored");
            check(count(Interaction.class)==64,"64 interactions restored without duplicates");
            check(count(BlockDisplay.class)==32,"32 starting pieces restored");
            check(count(TextDisplay.class)==97,"coordinates piece labels and status restored");
            command(white,"entfernen");check(chess.boardForTest().origin()==null,"board removed");
            check(origin.getBlock().getType()==Material.AIR,"created floor removed");
            check(world.getEntitiesByClasses(Interaction.class,BlockDisplay.class,TextDisplay.class).isEmpty(),"display entities removed");complete();return;
        }
        command(black,"brett");check(chess.boardForTest().origin()==null,"non-admin cannot create board");
        origin.getBlock().setType(Material.DIAMOND_BLOCK);command(white,"brett");check(chess.boardForTest().origin()==null,"occupied footprint rejected");check(origin.getBlock().getType()==Material.DIAMOND_BLOCK,"existing block preserved");origin.getBlock().setType(Material.AIR);
        command(white,"brett");check(chess.boardForTest().origin()!=null,"admin board creation");
        check(origin.getBlock().getType()==Material.GRAY_CONCRETE,"a1 is dark");check(origin.clone().add(4,0,0).getBlock().getType()==Material.WHITE_CONCRETE,"b1 is light");
        check(count(Interaction.class)==64,"64 clickable squares");check(count(BlockDisplay.class)==32,"32 piece bodies");check(count(TextDisplay.class)==97,"97 labels (actual "+count(TextDisplay.class)+")");
        BlockBreakEvent breakEvent=new BlockBreakEvent(origin.getBlock(),white);chess.breakBlock(breakEvent);check(breakEvent.isCancelled(),"board break protected even for OP");
        check(chess.boardForTest().square(origin.clone().add(31,0,31))==63,"h8 coordinate mapping");check(chess.boardForTest().square(origin.clone().add(32,0,31))==null,"outside board excluded");
        HttpURLConnection health=(HttpURLConnection)URI.create("http://127.0.0.1:18765/").toURL().openConnection();check(health.getResponseCode()==200,"real plugin OAuth web endpoint running");health.disconnect();
        startFake();Api original=(Api)field("api");set("api",fakeApi);original.close();
        AccountStore accounts=(AccountStore)field("accounts");accounts.put(whiteId,new AccountStore.Account("testwhite","FAKE_WHITE_TEST_TOKEN",System.currentTimeMillis()+3_600_000));accounts.put(blackId,new AccountStore.Account("testblack","FAKE_BLACK_TEST_TOKEN",System.currentTimeMillis()+3_600_000));
        command(white,"spielen","abcdefgh");later(this::connected,()->{try{whiteTurn();}catch(Throwable e){fail(e);}});
    }
    private long count(Class<? extends Entity> type){return Bukkit.getWorlds().getFirst().getEntitiesByClass(type).size();}
    private String state(){return "{\"type\":\"gameState\",\"moves\":\""+moves.get()+"\",\"status\":\""+status.get()+"\",\"wtime\":900000,\"btime\":900000}";}
    private void startFake()throws Exception{
        fake=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);fake.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        fake.createContext("/",exchange->{try{
            String path=exchange.getRequestURI().getPath();
            if(path.contains("/stream/")){
                streams.incrementAndGet();exchange.sendResponseHeaders(200,0);var output=exchange.getResponseBody();
                String full="{\"type\":\"gameFull\",\"variant\":{\"key\":\"standard\"},\"initialFen\":\"startpos\",\"white\":{\"id\":\"testwhite\"},\"black\":{\"id\":\"testblack\"},\"state\":"+state()+"}\n";
                output.write(full.getBytes(StandardCharsets.UTF_8));output.flush();
                int seen=updateVersion.get();
                while(true){Thread.sleep(100);int current=updateVersion.get();output.write((current==seen?"\n":state()+"\n").getBytes(StandardCharsets.UTF_8));output.flush();seen=current;}
            }else if(path.contains("/move/")){
                moveRequests.incrementAndGet();String move=path.substring(path.lastIndexOf('/')+1);boolean valid=move.equals("e2e4")||move.equals("e7e5");
                byte[] bytes=(valid?"{\"ok\":true}":"{\"error\":\"illegal\"}").getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(valid?200:400,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
                if(valid){moves.set(moves.get().isBlank()?move:moves.get()+" "+move);updateVersion.incrementAndGet();}
            }else{byte[] bytes="{\"ok\":true}".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();}
        }catch(Exception ignored){exchange.close();}});fake.start();fakeApi=new Api("http://127.0.0.1:"+fake.getAddress().getPort());
    }
    private void whiteTurn()throws Exception{
        check(field("white").equals("testwhite"),"identity received from API");
        command(black,"zug","e7e5");check(moveRequests.get()==0,"out-of-turn move never sent");
        command(white,"zug","e2e4");later(()->sameMoves("e2e4"),()->{try{blackTurn();}catch(Throwable e){fail(e);}});
    }
    private void blackTurn()throws Exception{
        check(moveRequests.get()==1,"white move sent once and confirmed");
        check(((Position)field("position")).at(Position.index("e4"))=='P',"board follows confirmed move");
        command(black,"zug","e7e5");later(()->sameMoves("e2e4 e7e5"),()->{try{rejectedMove();}catch(Throwable e){fail(e);}});
    }
    private void rejectedMove()throws Exception{
        check(moveRequests.get()==2,"second Minecraft account controls black");command(white,"zug","e4e6");
        later(()->{try{return moveRequests.get()==3 && !((AtomicBoolean)field("moving")).get();}catch(Exception e){throw new RuntimeException(e);}},()->{try{restoreSession();}catch(Throwable e){fail(e);}});
    }
    private void restoreSession()throws Exception{
        check(sameMoves("e2e4 e7e5"),"rejected move leaves board unchanged");
        command(white,"trennen");check(((AccountStore)field("accounts")).get(whiteId)!=null,"active account cannot be unlinked");
        command(white,"entfernen");check(chess.boardForTest().origin()!=null,"active board cannot be removed");
        int before=streams.get();method("stopStream");chess.saveBoard();chess.boardForTest().cleanup();method("loadBoard");
        later(()->connected()&&streams.get()>before,()->{try{finishGame();}catch(Throwable e){fail(e);}});
    }
    private void finishGame()throws Exception{
        check(sameMoves("e2e4 e7e5"),"persisted session resynchronizes full move history");check(count(Interaction.class)==64,"session restore does not duplicate entities");
        status.set("resign");updateVersion.incrementAndGet();later(()->{try{return field("status").equals("resign");}catch(Exception e){throw new RuntimeException(e);}},()->{try{
            check(!connected(),"finished game stream closes");String yaml=Files.readString(chess.getDataFolder().toPath().resolve("board.yml"));check(!yaml.contains("game:"),"finished game not resumed after restart");
            Files.createDirectories(getDataFolder().toPath());Files.writeString(getDataFolder().toPath().resolve("phase1.done"),"done");complete();
        }catch(Throwable e){fail(e);}});
    }
    private void complete(){getLogger().info("SMOKE COMPLETE: "+checks+" checks");Bukkit.shutdown();}
    private void fail(Throwable error){getLogger().severe("SMOKE FAILED: "+error);error.printStackTrace();Bukkit.shutdown();}
    @Override public void onDisable(){if(fake!=null)fake.stop(0);if(fakeApi!=null)fakeApi.close();}
}
