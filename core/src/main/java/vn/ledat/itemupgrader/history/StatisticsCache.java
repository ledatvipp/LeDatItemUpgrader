package vn.ledat.itemupgrader.history;
import java.util.*;
import java.time.*;
/** Bounded, synchronized display cache. No database callbacks from placeholders. Fresh quote pity must bypass this cache. */
public final class StatisticsCache {
    private record Entry(PlayerStatistics value,Instant expires){}
    public record Ticket(UUID playerId,UUID incarnation){}
    private final int maximum;private final Duration ttl;
    private final LinkedHashMap<UUID,Entry> entries=new LinkedHashMap<>(16,0.75f,true);
    private final Map<UUID,Ticket> loads=new HashMap<>();private boolean closed;
    public StatisticsCache(int maximum,Duration ttl) {
        if(maximum<1||maximum>10000||ttl.isNegative()||ttl.isZero()||ttl.compareTo(Duration.ofMinutes(30))>0)
            throw new IllegalArgumentException("cache limits");this.maximum=maximum;this.ttl=ttl;
    }
    public synchronized Optional<Ticket> begin(UUID player) {
        Objects.requireNonNull(player);if(closed||loads.containsKey(player)||loads.size()>=maximum)return Optional.empty();
        var ticket=new Ticket(player,UUID.randomUUID());loads.put(player,ticket);return Optional.of(ticket);
    }
    public synchronized boolean complete(Ticket ticket,PlayerStatistics value,Instant now) {
        if(closed||!loads.remove(ticket.playerId(),ticket))return false;
        if(!value.playerId().equals(ticket.playerId()))throw new IllegalArgumentException("foreign statistics cache result");
        var old=entries.get(ticket.playerId());
        if(old!=null&&(old.value().completed()>value.completed() || old.value().completed()==value.completed()&&!old.value().equals(value)))return false;
        entries.put(value.playerId(),new Entry(value,now.plus(ttl)));
        while(entries.size()>maximum)entries.remove(entries.keySet().iterator().next());return true;
    }
    public synchronized void failed(Ticket ticket){loads.remove(ticket.playerId(),ticket);}
    public synchronized Optional<PlayerStatistics> get(UUID player,Instant now) {
        var e=entries.get(player);if(e==null)return Optional.empty();
        if(!now.isBefore(e.expires())){entries.remove(player);return Optional.empty();}return Optional.of(e.value());
    }
    public synchronized void invalidate(UUID player){entries.remove(player);loads.remove(player);}
    public synchronized int size(){return entries.size();}
    public synchronized void close(){closed=true;loads.clear();entries.clear();}
    public String placeholder(UUID player,String key,Instant now) {
        return get(player,now).map(s->switch(key){case "completed"->Long.toString(s.completed());case "wins"->Long.toString(s.wins());
            case "losses"->Long.toString(s.losses());case "win_rate"->s.winRate().toPlainString();default->"";}).orElse("");
    }
}
