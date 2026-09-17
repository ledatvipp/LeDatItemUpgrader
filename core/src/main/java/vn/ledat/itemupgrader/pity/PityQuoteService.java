package vn.ledat.itemupgrader.pity;
import java.time.Instant;
import java.util.*;
import vn.ledat.itemupgrader.quote.*;
import vn.ledat.itemupgrader.catalog.*;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.cost.*;
import vn.ledat.itemupgrader.chance.ChanceCalculator;
import vn.ledat.itemupgrader.profile.RiskProfile;
/** Worker-side quote seam. Reader must be an authoritative storage read, never a PAPI value or UI cache.
 * Native transactions remain disabled; callers must also use the atomic journal progression hook. */
public final class PityQuoteService {
    @FunctionalInterface public interface Reader { PitySnapshot read(UUID player,String scope); }
    private final UpgradeQuoteService base=new UpgradeQuoteService();
    public QuoteResult quote(QuoteRequest request,ItemSnapshot source,CatalogIndex index,CatalogAccess access,
            UpgradeRules rules,ResourceSnapshot resources,Instant now,PityPolicies policies,Reader reader) {
        Objects.requireNonNull(policies);Objects.requireNonNull(reader);
        var result=base.quote(request,source,index,access,rules,resources,now);
        if(result.quote().isEmpty()||!policies.enabled())return result;
        var q=result.quote().orElseThrow();var path=rules.catalog().pathFor(source.facts().key());
        if(path.isEmpty())return result; // Explicit paths only: no global fallback pity.
        var policy=policies.find(path.orElseThrow().id(),request.targetId(),q.terms().profileId());
        if(policy.isEmpty()||q.terms().failure()!=RiskProfile.FailureMode.DESTROY)return result;
        var p=policy.orElseThrow();
        if(q.sourceTotal().compareTo(p.minimumSourceValue())<0)return result;
        if(q.terms().output().isEmpty())return QuoteResult.rejected(QuoteResult.Status.PITY_POLICY_DENIED);
        String scope=PityScope.of(p,path.orElseThrow().id(),q,source);
        final PitySnapshot snapshot;
        try { snapshot=Objects.requireNonNull(reader.read(access.playerId(),scope)); }
        catch(RuntimeException unavailable) {return QuoteResult.rejected(QuoteResult.Status.PITY_UNAVAILABLE);}
        if(!snapshot.playerId().equals(access.playerId())||!snapshot.scope().equals(scope))
            return QuoteResult.rejected(QuoteResult.Status.PITY_UNAVAILABLE);
        var stamp=new PityStamp(p.id(),p.epoch(),scope,snapshot.version(),snapshot.failures(),p.incrementPoints(),p.maximumPoints());
        var profile=rules.profiles().get(q.terms().profileId());
        var chance=new ChanceCalculator().calculate(q.sourceTotal(),q.targetTotal(),rules.formulas().get(profile.formulaId()),
                profile.chanceMultiplier(),rules.permissionBonuses(access).stream().map(v->v.adjustment()).toList(),
                q.terms().boosts().stream().map(rules.boosts()::get).map(v->v.adjustment()).toList(),
                rules.settings().minimumPercent(),rules.settings().maximumPercent(),stamp.bonusPoints());
        var terms=new UpgradeQuote.Terms(q.terms().profileId(),q.terms().boosts(),q.terms().permissionBonuses(),chance.probability(),
                q.terms().failure(),q.terms().costs(),q.terms().output(),Optional.of(stamp));
        return new QuoteResult(QuoteResult.Status.QUOTED,Optional.of(new UpgradeQuote(q.quoteId(),q.request(),q.selection(),
                q.sourceTotal(),q.targetTotal(),chance,terms,q.resources())));
    }
    public UpgradeQuoteService.Validation revalidate(UpgradeQuote previous,UUID session,ItemSnapshot source,CatalogIndex index,
            CatalogAccess access,UpgradeRules rules,ResourceSnapshot resources,Instant now,PityPolicies policies,Reader reader) {
        if(!previous.selection().viewerId().equals(access.playerId()))return reject(UpgradeQuoteService.ValidationStatus.WRONG_VIEWER);
        if(!previous.request().sessionId().equals(session))return reject(UpgradeQuoteService.ValidationStatus.WRONG_SESSION);
        if(!now.isBefore(previous.selection().expiresAt()))return reject(UpgradeQuoteService.ValidationStatus.EXPIRED);
        if(new TargetSelectionService().validate(previous.selection(),session,source,index,access,now)!=TargetSelectionService.Check.VALID_PREVIEW)
            return reject(UpgradeQuoteService.ValidationStatus.STALE_SELECTION);
        var result=quote(previous.request(),source,index,access,rules,resources,now,policies,reader);
        if(result.quote().isEmpty())return reject(UpgradeQuoteService.ValidationStatus.NO_LONGER_ELIGIBLE);
        var next=result.quote().orElseThrow();
        if(!next.terms().equals(previous.terms()))return new UpgradeQuoteService.Validation(UpgradeQuoteService.ValidationStatus.RECONFIRM_REQUIRED,Optional.of(next));
        next=new UpgradeQuote(previous.quoteId(),previous.request(),previous.selection(),next.sourceTotal(),next.targetTotal(),next.chance(),next.terms(),next.resources());
        var status=next.resources().available()?UpgradeQuoteService.ValidationStatus.VALID_PREVIEW:
                next.resources().status()==ResourceAssessment.Status.UNAVAILABLE?UpgradeQuoteService.ValidationStatus.RESOURCES_UNAVAILABLE:
                UpgradeQuoteService.ValidationStatus.INSUFFICIENT_RESOURCES;
        return new UpgradeQuoteService.Validation(status,Optional.of(next));
    }
    private static UpgradeQuoteService.Validation reject(UpgradeQuoteService.ValidationStatus s){return new UpgradeQuoteService.Validation(s,Optional.empty());}
}
