package vn.ledat.itemupgrader.history;
import java.time.*;
import java.util.*;
/** Server-held keyset navigation with revision, incarnation, request and timeout fencing.
 * Data fetches never hold locks while doing I/O; rendering must recheck native holder and current permissions. */
public final class HistorySessionStore {
    public enum Navigation { FIRST, NEXT, PREVIOUS, REFRESH }
    public record View(UUID viewer,UUID session,UUID subject,long revision,HistoryQuery.Filter filter,int pageNumber,boolean busy,Optional<HistoryPage> page){}
    public record Request(UUID viewer,UUID session,UUID requestId,long revision,HistoryQuery query,Navigation navigation,Instant expiresAt){}
    private static final class Session {
        final UUID viewer,subject,id=UUID.randomUUID();final long revision;final HistoryQuery.Filter filter;
        final int pageSize;final List<Optional<HistoryQuery.Cursor>> trail=new ArrayList<>();
        Instant expiresAt;HistoryPage page;Request pending;long upper;int pageNumber=1;
        Session(UUID v,UUID s,long r,HistoryQuery.Filter f,int size,Instant now,Duration ttl) {
            viewer=v;subject=s;revision=r;filter=f;pageSize=size;expiresAt=now.plus(ttl);upper=now.toEpochMilli();trail.add(Optional.empty());
        }
        View view(){return new View(viewer,id,subject,revision,filter,pageNumber,pending!=null,Optional.ofNullable(page));}
    }
    private final int maximum,maxPages;private final Duration timeout,ttl;private boolean closed;
    private final Map<UUID,Session> sessions=new HashMap<>();
    public HistorySessionStore(int maximum,int maxPages,Duration timeout,Duration ttl) {
        if(maximum<1||maximum>128||maxPages<1||maxPages>100||timeout.compareTo(Duration.ofSeconds(1))<0||timeout.compareTo(Duration.ofSeconds(30))>0
                ||ttl.compareTo(Duration.ofSeconds(30))<0||ttl.compareTo(Duration.ofMinutes(10))>0)throw new IllegalArgumentException("history session bounds");
        this.maximum=maximum;this.maxPages=maxPages;this.timeout=timeout;this.ttl=ttl;
    }
    public synchronized Optional<View> open(UUID viewer,UUID subject,long revision,HistoryQuery.Filter filter,int pageSize,Instant now) {
        Objects.requireNonNull(viewer);Objects.requireNonNull(subject);Objects.requireNonNull(filter);
        new HistoryQuery(subject,filter,now.toEpochMilli(),Optional.empty(),pageSize);
        if(revision<1)throw new IllegalArgumentException("history revision");sweep(now);
        if(closed||!sessions.containsKey(viewer)&&sessions.size()>=maximum)return Optional.empty();
        var s=new Session(viewer,subject,revision,filter,pageSize,now,ttl);sessions.put(viewer,s);return Optional.of(s.view());
    }
    public synchronized Optional<View> view(UUID viewer,UUID session,long revision,Instant now) {
        var s=current(viewer,session,revision,now);return s==null?Optional.empty():Optional.of(s.view());
    }
    public synchronized Optional<Request> begin(UUID viewer,UUID session,long revision,Navigation navigation,Instant now) {
        var s=current(viewer,session,revision,now);if(s==null||s.pending!=null)return Optional.empty();
        Optional<HistoryQuery.Cursor> after;
        long upper=s.upper;
        switch(navigation) {
            case FIRST->{if(s.page!=null)return Optional.empty();after=Optional.empty();}
            case NEXT->{if(s.page==null||s.page.next().isEmpty()||s.pageNumber>=maxPages)return Optional.empty();after=s.page.next();}
            case PREVIOUS->{if(s.pageNumber<=1)return Optional.empty();after=s.trail.get(s.pageNumber-2);}
            case REFRESH->{after=Optional.empty();upper=now.toEpochMilli();}
            default->throw new IllegalArgumentException("navigation");
        }
        var query=new HistoryQuery(s.subject,s.filter,upper,after,s.pageSize);
        s.pending=new Request(viewer,s.id,UUID.randomUUID(),revision,query,navigation,now.plus(timeout));return Optional.of(s.pending);
    }
    public synchronized Optional<View> complete(Request request,HistoryPage page,long currentRevision,Instant now) {
        var s=current(request.viewer(),request.session(),currentRevision,now);
        if(s==null||!request.equals(s.pending)||!now.isBefore(request.expiresAt()))return Optional.empty();
        if(!request.query().equals(page.query()))throw new IllegalArgumentException("history response/query mismatch");
        switch(request.navigation()) {
            case FIRST,REFRESH->{s.trail.clear();s.trail.add(Optional.empty());s.pageNumber=1;s.upper=request.query().upperCreatedMillis();}
            case NEXT->{while(s.trail.size()>s.pageNumber)s.trail.removeLast();s.trail.add(request.query().after());s.pageNumber++;}
            case PREVIOUS->s.pageNumber--;
        }
        s.page=page;s.pending=null;s.expiresAt=now.plus(ttl);return Optional.of(s.view());
    }
    public synchronized boolean failed(Request request,long revision,Instant now) {
        var s=current(request.viewer(),request.session(),revision,now);
        if(s==null||!request.equals(s.pending))return false;s.pending=null;return true;
    }
    public synchronized void close(UUID viewer,UUID expectedSession){var s=sessions.get(viewer);if(s!=null&&s.id.equals(expectedSession))sessions.remove(viewer);}
    public synchronized void quit(UUID viewer){sessions.remove(viewer);}
    public synchronized int sweep(Instant now) {
        int before=sessions.size();sessions.values().removeIf(s->!now.isBefore(s.expiresAt));
        for(var s:sessions.values())if(s.pending!=null&&!now.isBefore(s.pending.expiresAt()))s.pending=null;
        return before-sessions.size();
    }
    public synchronized void shutdown(){closed=true;sessions.clear();}
    public synchronized int size(){return sessions.size();}
    private Session current(UUID viewer,UUID session,long revision,Instant now) {
        if(closed)return null;var s=sessions.get(viewer);
        if(s==null||!s.id.equals(session))return null;
        if(s.revision!=revision||!now.isBefore(s.expiresAt)){sessions.remove(viewer);return null;}
        if(s.pending!=null&&!now.isBefore(s.pending.expiresAt()))s.pending=null;return s;
    }
}
