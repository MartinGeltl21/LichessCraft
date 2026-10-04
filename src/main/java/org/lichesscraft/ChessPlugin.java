package org.lichesscraft;

import com.google.gson.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ChessPlugin extends JavaPlugin implements Listener,TabExecutor {
    private final ExecutorService executor=Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID,Integer> selections=new HashMap<>();
    private final Map<UUID,String> promotions=new HashMap<>();
    private final Map<UUID,Integer> lastClick=new HashMap<>();
    private final Set<UUID> actions=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean moving=new AtomicBoolean();
    private Api api;
    private AccountStore accounts;
    private OAuth oauth;
    private Board board;
    private volatile boolean running,connected;
    private volatile long generation,lastStreamLine;
    private volatile InputStream stream;
    private UUID streamPlayer;
    private String gameId,white="",black="",status="",winner="";
    private Position position=new Position("startpos","");
    private long whiteTime,blackTime,stateTime;
    private long actionVersion;
    private String confirmedMoves="";
    private final List<Chunk> heldChunks=new ArrayList<>();

    @Override public void onEnable() {
        saveDefaultConfig(); api=new Api(); board=new Board(this);
        try {
            accounts=new AccountStore(getDataFolder().toPath().resolve("private"));
            String publicUrl=getConfig().getString("oauth.public-url","");
            if(!publicUrl.isBlank()) oauth=new OAuth(api,accounts,publicUrl,getConfig().getString("oauth.bind","0.0.0.0"),getConfig().getInt("oauth.port",8765),getConfig().getString("oauth.client-id","lichesscraft"));
            else getLogger().info("OAuth deaktiviert: oauth.public-url noch nicht eingerichtet. Brettbau ist verfügbar.");
        } catch(Exception error) {
            getLogger().severe("Account-/OAuth-Speicher konnte nicht gestartet werden: "+error.getClass().getSimpleName()+". Konfiguration und Dateirechte prüfen.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        running=true;
        getServer().getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("schach")).setExecutor(this); getCommand("schach").setTabCompleter(this);
        loadBoard();
        Bukkit.getScheduler().runTaskTimer(this,this::tick,20,20);
    }
    @Override public void onDisable() {
        running=false; stopStream();
        if(oauth!=null) oauth.close(); executor.shutdownNow(); if(api!=null) api.close();
        if(board!=null) board.cleanup(); releaseChunks();
    }
    private void main(Runnable task) { if(running) Bukkit.getScheduler().runTask(this,()->{if(running)task.run();}); }
    private void tell(UUID id,String text) { main(()->{Player player=Bukkit.getPlayer(id); if(player!=null) player.sendMessage(Component.text("[Schach] "+text));}); }
    private void link(UUID id,String text,String url) { main(()->{Player player=Bukkit.getPlayer(id); if(player!=null)player.sendMessage(Component.text("[Schach] "+text).clickEvent(ClickEvent.openUrl(url)));}); }
    private void work(UUID id,Checked task) {
        if(!actions.add(id)) { tell(id,"Eine Anfrage läuft bereits."); return; }
        executor.submit(()->{try { task.run(); } catch(Exception e) { tell(id,message(e)); } finally { actions.remove(id); }});
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static String message(Exception e) { return e instanceof IOException || e instanceof IllegalArgumentException ? e.getMessage() : "Vorgang fehlgeschlagen ("+e.getClass().getSimpleName()+"). Bitte erneut versuchen."; }
    private AccountStore.Account account(UUID id) {
        AccountStore.Account account=accounts.get(id); if(account==null)throw new IllegalArgumentException("Zuerst /schach verbinden und im Browser anmelden."); return account;
    }
    private boolean admin(CommandSender sender) { if(sender.hasPermission("lichesscraft.admin") || sender.isOp())return true; sender.sendMessage("Dafür brauchst du Schach-Adminrechte.");return false; }
    public Board boardForTest() { return board; }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(args.length==0 || args[0].equalsIgnoreCase("hilfe")) { help(sender);return true; }
        if(args[0].equalsIgnoreCase("status")) { sender.sendMessage("LichessCraft 0.1.0 | OAuth: "+(oauth==null?"nicht konfiguriert":"bereit")+" | Brett: "+(board.origin()==null?"fehlt":"bereit")+" | Partie: "+(gameId==null?"keine":gameId+" / "+status+" / "+(connected?"verbunden":"synchronisiert nicht"))); if(sender instanceof Player p) {var a=accounts.get(p.getUniqueId());sender.sendMessage("Account: "+(a==null?"nicht verbunden":a.username()));} return true; }
        if(!(sender instanceof Player player)) { sender.sendMessage("Dieser Befehl benötigt einen Spieler. /schach hilfe oder status sind in der Konsole verfügbar.");return true; }
        if(!player.hasPermission("lichesscraft.play")) { player.sendMessage("Du hast keine Schach-Spielberechtigung.");return true; }
        UUID id=player.getUniqueId();
        try {
            switch(args[0].toLowerCase(Locale.ROOT)) {
                case "brett" -> {
                    if(!admin(player))break;
                    if(board.origin()!=null)throw new IllegalArgumentException("Es gibt bereits ein Brett. Zuerst /schach entfernen.");
                    Location origin=player.getLocation().toBlockLocation();
                    board.create(origin,getConfig().getInt("board.square-size",4),true); holdChunks(); saveBoard();
                    player.sendMessage("Brett erstellt: a1 ist hier, Dateien laufen nach +X, Reihen nach +Z.");
                }
                case "entfernen" -> {
                    if(!admin(player))break;
                    if(active())throw new IllegalArgumentException("Laufende Partie zuerst beenden. /schach freigeben löscht nur die lokale Anzeige.");
                    stopStream();gameId=null;board.remove();releaseChunks();saveBoard();player.sendMessage("Brett entfernt.");
                }
                case "verbinden" -> {
                    if(oauth==null)throw new IllegalArgumentException("Admin muss zuerst oauth.public-url mit HTTPS konfigurieren und neu starten.");
                    if(active() && controls(id))throw new IllegalArgumentException("Account während einer laufenden Partie nicht wechseln.");
                    link(id,"Hier klicken, um deinen Lichess-Account zu verbinden",oauth.begin(id));
                }
                case "bestaetigen" -> {
                    if(args.length!=2 || oauth==null)throw new IllegalArgumentException("/schach bestaetigen <Code aus dem Browser>");
                    if(active() && controls(id))throw new IllegalArgumentException("Account während einer laufenden Partie nicht wechseln.");
                    var connectedAccount=oauth.confirm(id,args[1]);tell(id,"Verbunden mit "+connectedAccount.username()+". /schach partien zeigt deine laufenden Spiele.");
                }
                case "trennen" -> {
                    if(active() && controls(id))throw new IllegalArgumentException("Account während einer laufenden Partie nicht trennen.");
                    accounts.remove(id); if(oauth!=null)oauth.cancel(id);tell(id,"Lokale Account-Verknüpfung gelöscht. Du kannst die App zusätzlich unter lichess.org/account/oauth/token widerrufen.");
                }
                case "partien" -> {
                    var a=account(id);
                    work(id,()->{
                        JsonObject result=api.call("/api/account/playing",a.token(),null);
                        var games=result.getAsJsonArray("nowPlaying"); if(games==null || games.isEmpty())tell(id,"Keine laufenden Partien. /schach herausfordern <Lichess-Name>");
                        else for(var entry:games) {JsonObject g=entry.getAsJsonObject();String gid=g.get("gameId").getAsString();tell(id,"Partie "+gid+" — /schach spielen "+gid);}
                    });
                }
                case "herausfordern" -> {
                    if(args.length!=2 || !args[1].matches("[A-Za-z0-9_-]{2,30}"))throw new IllegalArgumentException("/schach herausfordern <Lichess-Name>");
                    if(active())throw new IllegalArgumentException("Das Brett wird gerade benutzt.");
                    var a=account(id);
                    work(id,()->{var result=api.call("/api/challenge/"+args[1],a.token(),Map.of("rated","false","variant","standard","clock.limit","900","clock.increment","10"));String gid=result.getAsJsonObject("challenge").get("id").getAsString();link(id,"Einladung gesendet: Gegner muss binnen 20 Sekunden annehmen.","https://lichess.org/"+gid);tell(id,"Nach Annahme: /schach spielen "+gid+" (oder /schach partien).");});
                }
                case "annehmen" -> {
                    if(args.length!=2 || !args[1].matches("[A-Za-z0-9]{8}"))throw new IllegalArgumentException("/schach annehmen <Herausforderungs-ID>");
                    var a=account(id);work(id,()->{api.call("/api/challenge/"+args[1]+"/accept",a.token(),Map.of());tell(id,"Angenommen. /schach spielen "+args[1]);});
                }
                case "spielen" -> {
                    if(args.length!=2 || !args[1].matches("[A-Za-z0-9]{8}"))throw new IllegalArgumentException("/schach spielen <Partie-ID aus /schach partien>");
                    if(board.origin()==null)throw new IllegalArgumentException("Admin muss zuerst /schach brett aufstellen.");
                    var a=account(id);
                    if(active() && !args[1].equals(gameId))throw new IllegalArgumentException("Das Brett ist durch eine laufende Partie belegt.");
                    if(args[1].equals(gameId) && connected) {if(!controls(id))throw new IllegalArgumentException("Diese Partie gehört einem anderen Account.");player.sendMessage("Du kannst deine Figuren auf dem Brett bewegen.");break;}
                    gameId=args[1];status="connecting";streamPlayer=id;white="";black="";selections.clear();promotions.clear();saveBoard();startStream(id,a,gameId);
                }
                case "zug" -> {
                    if(args.length!=2 || !args[1].matches("[a-h][1-8][a-h][1-8][qrbn]?"))throw new IllegalArgumentException("/schach zug e2e4 (Umwandlung z. B. e7e8q)");
                    sendMove(id,args[1]);
                }
                case "umwandeln" -> {
                    if(args.length!=2 || !args[1].matches("[qrbn]"))throw new IllegalArgumentException("/schach umwandeln q|r|b|n");
                    String move=promotions.remove(id);if(move==null)throw new IllegalArgumentException("Keine Umwandlung ausgewählt.");sendMove(id,move+args[1]);
                }
                case "aufgeben", "remis" -> {
                    if(args.length!=2 || !args[1].equalsIgnoreCase("ja"))throw new IllegalArgumentException("Bestätige mit /schach "+args[0].toLowerCase(Locale.ROOT)+" ja. Remis bietet an oder nimmt ein vorhandenes Angebot an.");
                    if(!active() || !controls(id))throw new IllegalArgumentException("Du spielst diese Partie nicht.");
                    var a=account(id);String gid=gameId;String endpoint=args[0].equalsIgnoreCase("aufgeben")?"resign":"draw/yes";
                    work(id,()->{api.call("/api/board/game/"+gid+"/"+endpoint,a.token(),Map.of());tell(id,endpoint.equals("resign")?"Aufgabe gesendet.":"Remis-Anfrage gesendet.");});
                }
                case "freigeben" -> {
                    if(!admin(player))break;
                    if(args.length!=2 || !args[1].equalsIgnoreCase("ja"))throw new IllegalArgumentException("/schach freigeben ja beendet nur die Anzeige. Die Partie läuft auf Lichess weiter!");
                    stopStream();gameId=null;streamPlayer=null;white="";black="";status="";selections.clear();promotions.clear();saveBoard();board.render(new Position("startpos",""));board.status("Brett frei · /schach spielen <ID>");player.sendMessage("Brett freigegeben. Eventuelle Partie auf Lichess weiterführen oder dort aufgeben.");
                }
                default -> help(player);
            }
        } catch(Exception e) { player.sendMessage("[Schach] "+message(e)); }
        return true;
    }
    private void help(CommandSender sender) {
        sender.sendMessage("Schach: /schach verbinden → Browser → /schach bestaetigen <Code>");
        sender.sendMessage("/schach partien | herausfordern <Name> | annehmen <ID> | spielen <ID>");
        sender.sendMessage("Rechtsklick: Figur → Zielfeld. Alternativ /schach zug e2e4");
        sender.sendMessage("/schach umwandeln q|r|b|n | aufgeben ja | remis ja | status | trennen");
        if(sender.isOp() || sender.hasPermission("lichesscraft.admin"))sender.sendMessage("Admin: /schach brett | entfernen | freigeben ja (Partie läuft auf Lichess weiter)");
    }
    private boolean active() { return gameId!=null && (status.equals("connecting") || status.equals("created") || status.equals("started")); }
    private boolean controls(UUID id) {var a=accounts.get(id);return a!=null && (a.username().equalsIgnoreCase(white)||a.username().equalsIgnoreCase(black));}
    private boolean isWhite(UUID id) {return account(id).username().equalsIgnoreCase(white);}
    private void select(Player player,int square) {
        UUID id=player.getUniqueId();
        int tick=Bukkit.getCurrentTick();if(Objects.equals(lastClick.put(id,tick),tick))return;
        try {
            if(!active() || !connected || !controls(id))throw new IllegalArgumentException("Verbinde deinen Account und starte eine Partie mit /schach spielen <ID>.");
            if(isWhite(id)!=position.whiteTurn())throw new IllegalArgumentException("Dein Gegner ist am Zug.");
            char piece=position.at(square);
            if(piece!='.' && Character.isUpperCase(piece)==isWhite(id)) {selections.put(id,square);promotions.remove(id);board.highlight(square);player.sendMessage("Ausgewählt: "+Position.name(square)+". Klicke das Zielfeld.");return;}
            Integer from=selections.remove(id);if(from==null)throw new IllegalArgumentException("Wähle zuerst eine eigene Figur.");
            String move=Position.name(from)+Position.name(square);
            if(Character.toLowerCase(position.at(from))=='p' && (square/8==0 || square/8==7)) {
                promotions.put(id,move);
                for(String promotion:List.of("q","r","b","n"))player.sendMessage(Component.text("Umwandlung: "+switch(promotion){case "q"->"Dame";case "r"->"Turm";case "b"->"Läufer";default->"Springer";}).clickEvent(ClickEvent.runCommand("/schach umwandeln "+promotion)));
            } else sendMove(id,move);
        } catch(Exception e) {player.sendMessage("[Schach] "+message(e));}
    }
    private void sendMove(UUID id,String move) {
        if(!active() || !connected || !controls(id))throw new IllegalArgumentException("Keine synchronisierte eigene Partie aktiv.");
        if(isWhite(id)!=position.whiteTurn())throw new IllegalArgumentException("Dein Gegner ist am Zug.");
        var a=account(id);String gid=gameId;long gen=generation;
        if(!moving.compareAndSet(false,true))throw new IllegalArgumentException("Der vorherige Zug wird noch bestätigt.");
        long version=++actionVersion;String before=confirmedMoves;
        executor.submit(()->{
            try {api.call("/api/board/game/"+gid+"/move/"+move,a.token(),Map.of());main(()->{if(gen==generation && before.equals(confirmedMoves))Bukkit.getScheduler().runTaskLater(this,()->{if(gen==generation && version==actionVersion && before.equals(confirmedMoves)){connected=false;closeStream();tell(id,"Zugstatus unklar. Brett wird neu synchronisiert; Zug nicht erneut senden.");}},100);});}
            catch(Exception e) {main(()->{if(gen==generation){
                if(e instanceof Api.Error error && error.status()<500) {moving.set(false);tell(id,message(e));}
                else {connected=false;closeStream();tell(id,"Zugstatus unklar. Automatische Synchronisierung läuft; Zug nicht erneut senden.");}
            }});}
        });
    }
    private void stopStream() { generation++;connected=false; moving.set(false);closeStream(); }
    private void closeStream() {InputStream current=stream;if(current!=null)try{current.close();}catch(IOException ignored){} }
    private void startStream(UUID id,AccountStore.Account account,String gid) {
        stopStream();long gen=generation;
        executor.submit(()->{
            int failures=0;
            while(running && gen==generation) {
                try(InputStream input=api.openStream("/api/board/game/stream/"+gid,account.token())) {
                    if(gen!=generation)break;stream=input;lastStreamLine=System.currentTimeMillis();
                    try(var reader=new BufferedReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;while(running && gen==generation && (line=reader.readLine())!=null) {
                            lastStreamLine=System.currentTimeMillis();if(line.isBlank())continue;
                            JsonObject event=JsonParser.parseString(line).getAsJsonObject();main(()->{if(gen==generation)receive(event,id,account,gid);});
                            failures=0;
                        }
                    }
                    main(()->{if(gen==generation)connected=false;});
                } catch(Exception error) {
                    if(gen!=generation || !running)break;
                    if(error instanceof Api.Error apiError && List.of(400,401,403,404).contains(apiError.status())) {
                        tell(id,message(error));main(()->{if(gen==generation){stopStream();gameId=null;streamPlayer=null;status="";saveBoard();board.status("Partie konnte nicht geöffnet werden · /schach partien");}});break;
                    }
                    if(failures++==0)tell(id,"Spielstream unterbrochen: "+message(error)+" Automatische Neuverbindung läuft.");
                    main(()->{if(gen==generation){connected=false;board.status("Verbindung unterbrochen · synchronisiere …");}});
                }
                if(gen!=generation || !running)break;
                try {Thread.sleep(Math.min(60_000,Math.max(2_000,2_000L << Math.min(failures,5))));}catch(InterruptedException e){Thread.currentThread().interrupt();break;}
            }
        });
    }
    private void receive(JsonObject event,UUID id,AccountStore.Account account,String gid) {
        String type=string(event,"type","");
        if(type.equals("gameFull")) {
            String variant=event.has("variant")?string(event.getAsJsonObject("variant"),"key",""):"";
            String fen=string(event,"initialFen","startpos");
            if(!variant.equals("standard") || !fen.equals("startpos")) {tell(id,"Erste Version unterstützt ausschließlich Standard-Schach aus der Startstellung.");stopStream();gameId=null;status="";saveBoard();return;}
            white=string(event.getAsJsonObject("white"),"id","");black=string(event.getAsJsonObject("black"),"id","");
            if(!account.username().equalsIgnoreCase(white) && !account.username().equalsIgnoreCase(black)) {tell(id,"Diese Partie gehört nicht deinem Account.");stopStream();gameId=null;status="";saveBoard();return;}
            connected=true;moving.set(false);actionVersion++;receiveState(event.getAsJsonObject("state"),id);
            tell(id,"Partie "+gid+" auf dem Brett. Du spielst "+(isWhite(id)?"Weiß":"Schwarz")+".");
        } else if(type.equals("gameState"))receiveState(event,id);
    }
    private void receiveState(JsonObject state,UUID id) {
        String moves=string(state,"moves","");
        try {position=new Position("startpos",moves);}catch(IllegalArgumentException e){tell(id,"Spielstand konnte nicht gelesen werden. Synchronisation gestoppt.");stopStream();return;}
        boolean changed=!moves.equals(confirmedMoves);
        confirmedMoves=moves;
        if(changed){moving.set(false);actionVersion++;selections.clear();promotions.clear();}
        status=string(state,"status","");winner=string(state,"winner","");
        whiteTime=state.has("wtime")?state.get("wtime").getAsLong():0;blackTime=state.has("btime")?state.get("btime").getAsLong():0;stateTime=System.currentTimeMillis();connected=true;
        board.render(position);
        if(state.has("wdraw") && state.get("wdraw").getAsBoolean() || state.has("bdraw") && state.get("bdraw").getAsBoolean())tell(id,"Remis-Angebot liegt vor. Annehmen mit /schach remis ja.");
        if(!active()) {board.status("Partie beendet: "+status+(winner.isBlank()?"":" · Gewinner: "+winner));link(id,"Partie auf Lichess öffnen","https://lichess.org/"+gameId);stopStream();saveBoard();}
    }
    private static String string(JsonObject object,String key,String fallback) {return object!=null && object.has(key) && !object.get(key).isJsonNull()?object.get(key).getAsString():fallback;}
    private void tick() {
        if(active() && connected) {
            if(System.currentTimeMillis()-lastStreamLine>35_000) {connected=false;closeStream();board.status("Verbindung unterbrochen · synchronisiere …");return;}
            long elapsed=status.equals("started")?Math.max(0,System.currentTimeMillis()-stateTime):0;
            board.status(white+" (Weiß) "+clock(whiteTime-(position.whiteTurn()?elapsed:0))+"\n"+black+" (Schwarz) "+clock(blackTime-(!position.whiteTurn()?elapsed:0))+"\n"+(position.whiteTurn()?"Weiß":"Schwarz")+" am Zug");
        }
    }
    private static String clock(long millis){long seconds=Math.max(0,millis/1000);return "%d:%02d".formatted(seconds/60,seconds%60);}
    @EventHandler(ignoreCancelled=true) public void interact(PlayerInteractEvent event) {
        if(event.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND || event.getAction()!=Action.RIGHT_CLICK_BLOCK || event.getClickedBlock()==null)return;
        Integer square=board.square(event.getClickedBlock().getLocation());if(square==null)return;event.setCancelled(true);select(event.getPlayer(),square);
    }
    @EventHandler(ignoreCancelled=true) public void entityInteract(PlayerInteractEntityEvent event) {
        if(event.getHand()!=org.bukkit.inventory.EquipmentSlot.HAND)return;Integer square=board.square(event.getRightClicked());if(square==null)return;event.setCancelled(true);select(event.getPlayer(),square);
    }
    @EventHandler(ignoreCancelled=true) public void entityInteractAt(PlayerInteractAtEntityEvent event) { entityInteract(event); }
    @EventHandler(ignoreCancelled=true) public void damage(EntityDamageEvent event){if(board.marked(event.getEntity()))event.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void breakBlock(BlockBreakEvent event){if(board.square(event.getBlock().getLocation())!=null)event.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void placeBlock(BlockPlaceEvent event){if(insideBoard(event.getBlock().getLocation()))event.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void entityExplosion(EntityExplodeEvent event){event.blockList().removeIf(b->insideBoard(b.getLocation()));}
    @EventHandler(ignoreCancelled=true) public void blockExplosion(BlockExplodeEvent event){event.blockList().removeIf(b->insideBoard(b.getLocation()));}
    @EventHandler(ignoreCancelled=true) public void pistonExtend(BlockPistonExtendEvent event){if(event.getBlocks().stream().anyMatch(b->insideBoard(b.getLocation())||insideBoard(b.getRelative(event.getDirection()).getLocation())))event.setCancelled(true);}
    @EventHandler(ignoreCancelled=true) public void pistonRetract(BlockPistonRetractEvent event){if(event.getBlocks().stream().anyMatch(b->insideBoard(b.getLocation())||insideBoard(b.getRelative(event.getDirection().getOppositeFace()).getLocation())))event.setCancelled(true);}
    @EventHandler public void quit(PlayerQuitEvent event){selections.remove(event.getPlayer().getUniqueId());promotions.remove(event.getPlayer().getUniqueId());lastClick.remove(event.getPlayer().getUniqueId());}
    private boolean insideBoard(Location location){Location origin=board.origin();if(origin==null)return false;int y=location.getBlockY();location=location.clone();location.setY(origin.getY());return y>=origin.getY() && y<origin.getY()+5 && board.square(location)!=null;}
    private void holdChunks(){releaseChunks();Location origin=board.origin();if(origin==null)return;int width=8*board.size();for(int x=origin.getBlockX()>>4;x<=(origin.getBlockX()+width-1)>>4;x++)for(int z=origin.getBlockZ()>>4;z<=(origin.getBlockZ()+width-1)>>4;z++){Chunk chunk=origin.getWorld().getChunkAt(x,z);chunk.addPluginChunkTicket(this);heldChunks.add(chunk);}}
    private void releaseChunks(){heldChunks.forEach(c->c.removePluginChunkTicket(this));heldChunks.clear();}
    public void saveBoard(){
        YamlConfiguration yaml=new YamlConfiguration();Location origin=board.origin();
        if(origin!=null){yaml.set("world",origin.getWorld().getName());yaml.set("x",origin.getBlockX());yaml.set("y",origin.getBlockY());yaml.set("z",origin.getBlockZ());yaml.set("size",board.size());}
        if(gameId!=null && active()){yaml.set("game",gameId);yaml.set("player",streamPlayer==null?null:streamPlayer.toString());}
        try{yaml.save(new File(getDataFolder(),"board.yml"));}catch(IOException e){getLogger().severe("Brettzustand konnte nicht gespeichert werden.");}
    }
    private void loadBoard(){
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(new File(getDataFolder(),"board.yml"));
        if(!yaml.contains("world"))return;
        World world=Bukkit.getWorld(yaml.getString("world",""));if(world==null){getLogger().warning("Brettwelt nicht geladen; board.yml bleibt erhalten.");return;}
        try{board.create(new Location(world,yaml.getInt("x"),yaml.getInt("y"),yaml.getInt("z")),yaml.getInt("size",4),false);holdChunks();
            if(yaml.contains("game") && yaml.contains("player")){gameId=yaml.getString("game");streamPlayer=UUID.fromString(yaml.getString("player"));status="connecting";var a=accounts.get(streamPlayer);if(a!=null)startStream(streamPlayer,a,gameId);else{board.status("Account neu verbinden, dann /schach spielen "+gameId);}}
        }catch(Exception e){getLogger().warning("Brett konnte nicht wiederhergestellt werden: "+e.getClass().getSimpleName());}
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args){
        if(args.length==1){List<String> commands=new ArrayList<>(List.of("hilfe","status","verbinden","bestaetigen","trennen","partien","herausfordern","annehmen","spielen","zug","umwandeln","aufgeben","remis"));if(sender.isOp()||sender.hasPermission("lichesscraft.admin"))commands.addAll(List.of("brett","entfernen","freigeben"));return commands.stream().filter(s->s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();}
        if(args.length==2 && List.of("aufgeben","remis","freigeben").contains(args[0]))return List.of("ja");
        if(args.length==2 && args[0].equals("umwandeln"))return List.of("q","r","b","n");return List.of();
    }
}
