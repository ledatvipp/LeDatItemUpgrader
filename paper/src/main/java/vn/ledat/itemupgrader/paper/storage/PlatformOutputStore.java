package vn.ledat.itemupgrader.paper.storage;

import java.util.*;
import java.util.concurrent.CompletionStage;
import vn.ledat.itemupgrader.output.storage.*;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.transaction.storage.JournalSql;

/** Async shared-SQL source bridge. Not bootstrapped until real Platform/JDBC/native effect acceptance gates pass. */
public final class PlatformOutputStore implements OutputStore {
    private final PlatformAccess platform;
    private final JdbcOutputRepository repository;
    public PlatformOutputStore(PlatformAccess platform){
        this.platform=Objects.requireNonNull(platform);
        repository=new JdbcOutputRepository(new OutputSql(platform.tableName("outputs"),platform.tableName("output_identities"),platform.tableName("output_schema")));
    }
    public CompletionStage<Void> initialize(JournalSql.Dialect dialect){return platform.query("iup-output-schema",c->{repository.initialize(c,dialect);return null;});}
    @Override public CompletionStage<Claim> claim(Row pending){return platform.query("iup-output-claim",c->repository.claim(c,pending));}
    @Override public CompletionStage<Optional<Row>> find(UUID id){return platform.query("iup-output-find",c->repository.find(c,id));}
    @Override public CompletionStage<Boolean> finish(Row expected,Row completed){return platform.query("iup-output-finish",c->repository.finish(c,expected,completed));}
}
