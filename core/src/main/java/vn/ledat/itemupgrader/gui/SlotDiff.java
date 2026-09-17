package vn.ledat.itemupgrader.gui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Full frames make disappearing entries explicit. No stale target/button can remain after pagination. */
public final class SlotDiff {
    private SlotDiff() {}
    public static <T> Map<Integer,T> changed(Map<Integer,T> previous, Map<Integer,T> next, int size) {
        if(size<9||size>54||size%9!=0||next.size()!=size) throw new IllegalArgumentException("require full GUI frame");
        for(int slot=0;slot<size;slot++) if(!next.containsKey(slot)||next.get(slot)==null) throw new IllegalArgumentException("missing frame slot");
        if(previous.keySet().stream().anyMatch(slot->slot==null||slot<0||slot>=size)) throw new IllegalArgumentException("invalid previous slot");
        Map<Integer,T> diff=new LinkedHashMap<>();
        for(int slot=0;slot<size;slot++) if(!Objects.equals(previous.get(slot),next.get(slot))) diff.put(slot,next.get(slot));
        return java.util.Collections.unmodifiableMap(diff);
    }
}
