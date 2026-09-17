package vn.ledat.itemupgrader.demo;

import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import vn.ledat.itemupgrader.transaction.*;
import vn.ledat.itemupgrader.transaction.storage.*;
import vn.ledat.itemupgrader.demo.support.SimulationJournal;

/** CLI offline inspection/replay of a synthetic fixture extracted by the SQLite SQL test. Never live data migration. */
public final class JournalPayloadInspect {
    private JournalPayloadInspect(){}
    public static void main(String[] args)throws Exception{
        if(args.length<2||args.length>3)throw new IllegalArgumentException("usage: JournalPayloadInspect <state.bin> <plan.bin> [simulate-resume]");
        byte[] bytes;try(var in=Files.newInputStream(Path.of(args[0]))){bytes=in.readNBytes(JournalCodec.MAX_BYTES+1);}
        byte[] planBytes;try(var in=Files.newInputStream(Path.of(args[1]))){planBytes=in.readNBytes(JournalCodec.MAX_BYTES+1);}
        var record=JournalCodec.decodeState(JournalCodec.decodePlan(planBytes),bytes);System.out.println("state="+record.state()+" version="+record.version()+" sample="+record.sample()+" recovery="+new RecoveryPlanner().inspect(record).action());
        if(args.length==3){
            if(!args[2].equals("simulate-resume"))throw new IllegalArgumentException("unsupported mode");
            AtomicInteger draws=new AtomicInteger();var journal=SimulationJournal.restored(record);
            var result=TransactionDemo.engine(journal,TransactionDemo.successfulPort(),()->{draws.incrementAndGet();return 999_999_999;}).resumeQuiescent(record).toCompletableFuture().get();
            System.out.println("SIMULATION result="+result.status()+" additional-draws="+draws.get()+" sample="+result.record().orElseThrow().sample());
        }
    }
}
