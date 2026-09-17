package vn.ledat.itemupgrader.demo;

import java.nio.file.*;
import vn.ledat.itemupgrader.output.storage.OutputCodec;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;

/** Offline verification of bytes loaded by the SQLite SQL test; no provider creation. */
public final class OutputPayloadInspect {
    private OutputPayloadInspect(){}
    public static void main(String[] args)throws Exception{
        if(args.length!=2)throw new IllegalArgumentException("expected output-payload-file plan-file");
        var output=OutputCodec.decode(Files.readAllBytes(Path.of(args[0])));
        var plan=JournalCodec.decodePlan(Files.readAllBytes(Path.of(args[1])));output.checkPlan(plan);
        System.out.println("attempt="+output.attemptId()+" outputDigest="+OutputCodec.digest(output)+" success="+output.success().facts().key()+" failureDamage="+output.failure().map(s->s.facts().damage()).orElse(-1));
    }
}
