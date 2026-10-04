package org.lichesscraft;

import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.*;
import java.util.*;

public final class Board {
    private final ChessPlugin plugin;
    private final NamespacedKey marker;
    private final Map<UUID,Integer> targets=new HashMap<>();
    private final List<Entity> pieces=new ArrayList<>();
    private final List<Entity> permanent=new ArrayList<>();
    private TextDisplay status;
    private Location origin;
    private int size;
    public Board(ChessPlugin plugin) { this.plugin=plugin; marker=new NamespacedKey(plugin,"board"); }
    public Location origin() { return origin==null?null:origin.clone(); }
    public int size() { return size; }
    public void create(Location start,int size,boolean buildFloor) {
        if(size<2 || size>6) throw new IllegalArgumentException("Feldgröße muss 2–6 sein.");
        int width=8*size;
        if(start.getBlockY()<start.getWorld().getMinHeight() || start.getBlockY()+5>=start.getWorld().getMaxHeight()) throw new IllegalArgumentException("Brett liegt außerhalb der Bauhöhe.");
        if(buildFloor) {
            for(int x=0;x<width;x++) for(int z=0;z<width;z++) for(int y=0;y<5;y++) if(!start.clone().add(x,y,z).getBlock().getType().isAir()) throw new IllegalArgumentException("Brettfläche muss frei sein: "+width+"×"+width+"×5 Blöcke. Stelle dich auf eine freie ebene Fläche.");
        }
        cleanup(); origin=start.toBlockLocation(); this.size=size;
        for(int file=0;file<8;file++) for(int rank=0;rank<8;rank++) {
            if(buildFloor) for(int x=0;x<size;x++) for(int z=0;z<size;z++) origin.clone().add(file*size+x,0,rank*size+z).getBlock().setType((file+rank)%2==0?Material.GRAY_CONCRETE:Material.WHITE_CONCRETE,false);
            int index=rank*8+file;
            Interaction hit=origin.getWorld().spawn(center(index).add(0,0.05,0),Interaction.class);
            hit.setInteractionWidth(size-0.1f); hit.setInteractionHeight(3); hit.setResponsive(true); tag(hit); targets.put(hit.getUniqueId(),index); permanent.add(hit);
            TextDisplay coordinate=text(center(index).add(0,0.03,-size*0.38),Position.name(index),0.65f); permanent.add(coordinate);
        }
        status=text(origin.clone().add(width/2.0,4,1),"LichessCraft · /schach hilfe",1.1f); permanent.add(status);
        render(new Position("startpos",""));
    }
    private Location center(int index) { return origin.clone().add(index%8*size+size/2.0,1,index/8*size+size/2.0); }
    private void tag(Entity entity) { entity.getPersistentDataContainer().set(marker,PersistentDataType.BYTE,(byte)1); entity.setPersistent(false); }
    public boolean marked(Entity entity) { return entity.getPersistentDataContainer().has(marker,PersistentDataType.BYTE); }
    public Integer square(Entity entity) { return targets.get(entity.getUniqueId()); }
    public Integer square(Location location) {
        if(origin==null || location.getWorld()!=origin.getWorld() || location.getBlockY()!=origin.getBlockY()) return null;
        int x=location.getBlockX()-origin.getBlockX(),z=location.getBlockZ()-origin.getBlockZ();
        return x>=0 && z>=0 && x<8*size && z<8*size ? z/size*8+x/size : null;
    }
    private TextDisplay text(Location location,String value,float scale) {
        TextDisplay display=location.getWorld().spawn(location,TextDisplay.class);
        display.text(Component.text(value)); display.setBillboard(Display.Billboard.CENTER); display.setSeeThrough(false); display.setShadowed(true);
        display.setTransformation(new Transformation(new Vector3f(),new Quaternionf(),new Vector3f(scale),new Quaternionf())); tag(display); return display;
    }
    public void render(Position position) {
        if(origin==null) return;
        pieces.forEach(Entity::remove); pieces.clear();
        for(int index=0;index<64;index++) {
            char piece=position.at(index); if(piece=='.') continue;
            Location center=center(index);
            Material material=Character.isUpperCase(piece)?Material.QUARTZ_BLOCK:Material.BLACKSTONE;
            float height=switch(Character.toLowerCase(piece)) { case 'p'->0.8f; case 'n','b'->1.15f; case 'r'->1.3f; case 'q'->1.6f; default->1.9f; };
            BlockDisplay body=origin.getWorld().spawn(center.clone().add(-0.4,0,-0.4),BlockDisplay.class);
            body.setBlock(material.createBlockData()); body.setTransformation(new Transformation(new Vector3f(),new Quaternionf(),new Vector3f(0.8f,height,0.8f),new Quaternionf())); tag(body); pieces.add(body);
            String label=switch(Character.toLowerCase(piece)) { case 'p'->"Bauer"; case 'r'->"Turm"; case 'n'->"Springer"; case 'b'->"Läufer"; case 'q'->"Dame"; default->"König"; };
            pieces.add(text(center.clone().add(0,height+0.15,0),(Character.isUpperCase(piece)?"Weiß · ":"Schwarz · ")+label,0.8f));
        }
    }
    public void status(String message) { if(status!=null) status.text(Component.text(message)); }
    public void highlight(int index) { if(origin!=null) origin.getWorld().spawnParticle(Particle.END_ROD,center(index).add(0,0.4,0),12,0.4,0.4,0.4,0); }
    public boolean nearby(Player player) { return origin!=null && player.getWorld()==origin.getWorld() && player.getLocation().distanceSquared(origin)<10000; }
    public void cleanup() { pieces.forEach(Entity::remove); permanent.forEach(Entity::remove); pieces.clear(); permanent.clear(); targets.clear(); status=null; }
    public void remove() {
        cleanup();
        if(origin!=null) for(int x=0;x<8*size;x++) for(int z=0;z<8*size;z++) {
            var block=origin.clone().add(x,0,z).getBlock();
            if(block.getType()==Material.GRAY_CONCRETE || block.getType()==Material.WHITE_CONCRETE) block.setType(Material.AIR,false);
        }
        origin=null;
    }
}
