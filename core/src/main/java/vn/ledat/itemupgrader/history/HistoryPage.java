package vn.ledat.itemupgrader.history;
import java.util.*;
public record HistoryPage(HistoryQuery query,List<HistoryEntry> rows,boolean hasMore) {
    public HistoryPage {
        Objects.requireNonNull(query);rows=List.copyOf(rows);
        if(rows.size()>query.limit()||hasMore&&rows.size()!=query.limit())throw new IllegalArgumentException("invalid history page");
        var cursor=query.after();
        for(var row:rows) {
            var check=new HistoryQuery(query.playerId(),query.filter(),query.upperCreatedMillis(),cursor,query.limit());
            if(!check.accepts(row))throw new IllegalArgumentException("unordered/foreign history result");
            cursor=Optional.of(query.cursor(row));
        }
    }
    public Optional<HistoryQuery.Cursor> next(){return hasMore?Optional.of(query.cursor(rows.getLast())):Optional.empty();}
}
