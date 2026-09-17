package vn.ledat.itemupgrader.pity;
import java.io.*;
import java.util.*;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.quote.UpgradeQuote;
import vn.ledat.itemupgrader.transaction.storage.JournalCodec;
/** Deliberately narrow: different target, source quantity/value, fees, boosts or output rules cannot share pity. */
public final class PityScope {
    private PityScope(){}
    public static String of(PityPolicy policy,String path,UpgradeQuote quote,ItemSnapshot source) {
        Objects.requireNonNull(policy);Objects.requireNonNull(quote);Objects.requireNonNull(source);
        if(!quote.selection().sourceFingerprint().equals(source.fingerprint()))throw new IllegalArgumentException("pity source mismatch");
        if(!policy.matches(path,quote.request().targetId(),quote.terms().profileId()))throw new IllegalArgumentException("pity path mismatch");
        if(quote.terms().pity().isPresent())throw new IllegalArgumentException("scope requires unadjusted quote");
        try {
            var bytes=new ByteArrayOutputStream();var o=new DataOutputStream(bytes);
            o.writeUTF("itemupgrader-pity-v1");o.writeUTF(policy.id());o.writeInt(policy.epoch());
            o.writeUTF(policy.incrementPoints().toPlainString());o.writeUTF(policy.maximumPoints().toPlainString());
            o.writeUTF(policy.minimumSourceValue().toPlainString());o.writeUTF(path);o.writeUTF(quote.request().targetId());
            o.writeUTF(quote.terms().profileId());o.writeUTF(source.facts().key().value());o.writeInt(source.facts().amount());
            o.writeUTF(quote.sourceTotal().stripTrailingZeros().toPlainString());o.writeUTF(quote.targetTotal().stripTrailingZeros().toPlainString());
            o.writeUTF(quote.terms().failure().name());o.writeLong(quote.terms().probability().winningTickets());
            o.writeInt(quote.terms().boosts().size());for(String id:new TreeSet<>(quote.terms().boosts()))o.writeUTF(id);
            o.writeInt(quote.terms().permissionBonuses().size());for(String id:new TreeSet<>(quote.terms().permissionBonuses()))o.writeUTF(id);
            o.writeInt(quote.terms().costs().lines().size());
            for(var line:quote.terms().costs().lines()) {
                o.writeUTF(line.resource().kind().name());o.writeUTF(line.resource().key());
                o.writeUTF(line.onAttempt().stripTrailingZeros().toPlainString());o.writeUTF(line.onSuccess().stripTrailingZeros().toPlainString());
                o.writeUTF(line.onFailure().stripTrailingZeros().toPlainString());
            }
            o.writeBoolean(quote.terms().output().isPresent());
            if(quote.terms().output().isPresent())vn.ledat.itemupgrader.output.OutputSpecCodec.write(o,quote.terms().output().orElseThrow());
            o.flush();return JournalCodec.digest(bytes.toByteArray());
        }catch(IOException impossible){throw new IllegalStateException(impossible);}
    }
}
