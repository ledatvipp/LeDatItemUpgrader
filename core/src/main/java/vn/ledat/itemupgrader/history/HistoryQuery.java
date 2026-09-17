package vn.ledat.itemupgrader.history;
import java.util.*;
/** Stable descending keyset; cursor stays server-side and is bound to subject/filter/window. No OFFSET scan. */
public record HistoryQuery(UUID playerId,Filter filter,long upperCreatedMillis,Optional<Cursor> after,int limit) {
    public enum Filter { ALL, WIN, LOSS, UNFINISHED }
    public record Cursor(UUID playerId,Filter filter,long upperCreatedMillis,long createdMillis,UUID transactionId) {
        public Cursor {
            Objects.requireNonNull(playerId);Objects.requireNonNull(filter);Objects.requireNonNull(transactionId);
            if(createdMillis<0||upperCreatedMillis<createdMillis)throw new IllegalArgumentException("invalid history cursor time");
        }
    }
    public HistoryQuery {
        Objects.requireNonNull(playerId);Objects.requireNonNull(filter);Objects.requireNonNull(after);
        if(upperCreatedMillis<0||limit<1||limit>45)throw new IllegalArgumentException("history limits");
        after.ifPresent(c->{if(!c.playerId().equals(playerId)||c.filter()!=filter||c.upperCreatedMillis()!=upperCreatedMillis)
            throw new IllegalArgumentException("cursor subject/filter/window mismatch");});
    }
    public Cursor cursor(HistoryEntry row) {
        if(!row.playerId().equals(playerId))throw new IllegalArgumentException("wrong history owner");
        return new Cursor(playerId,filter,upperCreatedMillis,row.createdAt().toEpochMilli(),row.transactionId());
    }
    public boolean accepts(HistoryEntry row) {
        if(!row.playerId().equals(playerId)||row.createdAt().toEpochMilli()>upperCreatedMillis)return false;
        if(filter==Filter.WIN&&row.outcome()!=HistoryEntry.Outcome.WIN||filter==Filter.LOSS&&row.outcome()!=HistoryEntry.Outcome.LOSS
                ||filter==Filter.UNFINISHED&&row.terminal())return false;
        return after.isEmpty()||compare(row,after.orElseThrow())<0;
    }
    private static int compare(HistoryEntry row,Cursor c) {
        int time=Long.compare(row.createdAt().toEpochMilli(),c.createdMillis());
        return time!=0?time:row.transactionId().toString().compareTo(c.transactionId().toString());
    }
}
