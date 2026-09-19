package vn.ledat.itemupgrader.history;
import java.time.Duration;
import java.util.*;
import vn.ledat.itemupgrader.gui.*;
import vn.ledat.itemupgrader.pity.PityPolicies;
/** Read-side settings. Enabling history does not initialize schemas or enable transactional writes. */
public record HistorySettings(boolean enabled,boolean placeholdersEnabled,int maximumSessions,int maximumQueries,
        int maximumPages,int cacheSize,Duration cacheTtl,Duration requestTimeout,Duration sessionTimeout,
        int retentionDays,int retentionBatch,MenuCompiler.CompiledMenu menu,PityPolicies pity,Map<String,GuiSettings.Cue> sounds) {
    public HistorySettings(boolean enabled,boolean placeholdersEnabled,int sessions,int queries,int pages,int cache,Duration cacheTtl,Duration requestTimeout,
            Duration sessionTimeout,int days,int batch,MenuCompiler.CompiledMenu menu,PityPolicies pity){
        this(enabled,placeholdersEnabled,sessions,queries,pages,cache,cacheTtl,requestTimeout,sessionTimeout,days,batch,menu,pity,Map.of());
    }
    public HistorySettings {
        sounds=Map.copyOf(sounds);if(!Set.of("open","click","error","close").containsAll(sounds.keySet()))throw new IllegalArgumentException("history sound cue");
        Objects.requireNonNull(menu);Objects.requireNonNull(pity);
        if(maximumSessions<1||maximumSessions>128||maximumQueries<1||maximumQueries>32||maximumPages<1||maximumPages>100
                ||cacheSize<1||cacheSize>10000||retentionDays<1||retentionDays>3650||retentionBatch<1||retentionBatch>1000)
            throw new IllegalArgumentException("history bounds");
        bound(cacheTtl,5,1800);bound(requestTimeout,1,30);bound(sessionTimeout,30,600);
        long count=menu.slots().values().stream().filter(e->e.role()==MenuDefinition.Role.INFO).count();
        if(count<1||count>45)throw new IllegalArgumentException("history requires 1..45 INFO entry slots");
        var roles=Set.of(MenuDefinition.Role.FILLER,MenuDefinition.Role.INFO,MenuDefinition.Role.BUTTON);
        var actions=Set.of(MenuDefinition.Action.NONE,MenuDefinition.Action.NEXT_PAGE,MenuDefinition.Action.PREVIOUS_PAGE,MenuDefinition.Action.REFRESH,MenuDefinition.Action.CLOSE);
        if(menu.slots().values().stream().anyMatch(e->!roles.contains(e.role())||!actions.contains(e.action())
                ||e.role()==MenuDefinition.Role.INFO&&e.action()!=MenuDefinition.Action.NONE||!e.argument().isEmpty()))
            throw new IllegalArgumentException("history is read-only; invalid role/action/argument");
        for(var a:List.of(MenuDefinition.Action.NEXT_PAGE,MenuDefinition.Action.PREVIOUS_PAGE,MenuDefinition.Action.CLOSE,MenuDefinition.Action.REFRESH))
            if(menu.slots().values().stream().noneMatch(e->e.action()==a))throw new IllegalArgumentException("history navigation missing "+a);
    }
    private static void bound(Duration d,int min,int max) {
        if(d==null||d.compareTo(Duration.ofSeconds(min))<0||d.compareTo(Duration.ofSeconds(max))>0)throw new IllegalArgumentException("history duration bounds");
    }
    public List<Integer> entrySlots(){return menu.slots().entrySet().stream().filter(e->e.getValue().role()==MenuDefinition.Role.INFO).map(Map.Entry::getKey).sorted().toList();}
}
