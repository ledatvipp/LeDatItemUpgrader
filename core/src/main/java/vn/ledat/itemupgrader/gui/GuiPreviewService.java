package vn.ledat.itemupgrader.gui;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import vn.ledat.itemupgrader.catalog.*;
import vn.ledat.itemupgrader.cost.ResourceSnapshot;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.quote.*;
import vn.ledat.itemupgrader.value.ValueResult;

/** Detached view query facade; reuses existing catalog/quote rules. No RNG or transaction entry point. */
public final class GuiPreviewService {
    public record Entry(String id, Optional<CatalogIndex.PricedTarget> target) {
        public Entry { Objects.requireNonNull(id); Objects.requireNonNull(target); }
    }
    public record Preview(GuiContext context, Optional<ItemSnapshot> source, Optional<ValueResult> value,
                          Optional<CatalogIndex.PricedTarget> target, Optional<QuoteResult> quote,
                          List<Entry> entries, int pages, int total, String status) {
        public Preview {
            Objects.requireNonNull(context); Objects.requireNonNull(source); Objects.requireNonNull(value);
            Objects.requireNonNull(target); Objects.requireNonNull(quote); Objects.requireNonNull(status);
            entries=List.copyOf(entries);
            if(pages<0||total<0||entries.size()>45) throw new IllegalArgumentException("invalid GUI preview page");
        }
    }
    private final CatalogService catalog = new CatalogService();
    private final UpgradeQuoteService quotes = new UpgradeQuoteService();
    public Preview calculate(UUID session, GuiContext context, Optional<ItemSnapshot> source, CatalogIndex index,
                             CatalogAccess access, UpgradeRules rules, ResourceSnapshot resources, int pageSize, Instant now) {
        if (pageSize<1||pageSize>45) throw new IllegalArgumentException("invalid GUI page size");
        if (index.definitions()!=rules.catalog() || !resources.playerId().equals(access.playerId()))
            throw new IllegalArgumentException("GUI runtime/resource binding mismatch");
        if(source.isEmpty() || context.sourceSlot()<0) return new Preview(context, Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),List.of(),0,0,"need-source");
        ItemSnapshot item=source.orElseThrow();
        CatalogResult recommended=catalog.recommend(item,index,access);
        if(recommended.status()!=CatalogResult.Status.OK) return new Preview(context,source,Optional.of(recommended.sourceValue()),Optional.empty(),Optional.empty(),List.of(),0,0,
                "catalog-"+recommended.status().name().toLowerCase(java.util.Locale.ROOT).replace('_','-'));
        // Only a blank selection may adopt the first recommendation. Invalid explicit selection is NOT replaced.
        GuiContext effective=context.targetId().isEmpty()?context.target(recommended.rows().getFirst().definition().id()):context;
        var request=new QuoteRequest(session,effective.targetId(),effective.profileId(),effective.boosts(),effective.sourceSlot());
        QuoteResult quote=quotes.quote(request,item,index,access,rules,resources,now);
        Optional<CatalogIndex.PricedTarget> selected=quote.quote().isPresent()?Optional.ofNullable(index.ready().get(effective.targetId())):Optional.empty();
        String status=quote.quote().isEmpty()?"quote-denied":quote.quote().orElseThrow().resources().available()?"preview":"resources-missing";
        List<Entry> rows=List.of(); int pages=1,total=0;
        switch(context.screen()) {
            case MAIN -> { }
            case CATALOG -> {
                var result=catalog.browse(item,index,access,new CatalogQuery(context.page(),pageSize,context.category(),"",Set.of(),"",context.sort()));
                rows=result.rows().stream().map(row->new Entry(row.definition().id(),Optional.of(row))).toList(); pages=result.totalPages(); total=result.totalMatches();
                if(result.status()!=CatalogResult.Status.OK) status="catalog-"+result.status().name().toLowerCase(java.util.Locale.ROOT).replace('_','-');
            }
            case PROFILES, BOOSTS -> {
                var path=index.definitions().pathFor(item.facts().key());
                String profile=rules.profileFor(path,effective.profileId());
                List<String> ids=context.screen()==GuiContext.Screen.PROFILES
                        ?rules.profiles().values().stream().filter(p->p.enabled()&&rules.profileAllowed(path,p.id())&&access.allows(p.permission(),p.requiredConditions())).map(p->p.id()).sorted().toList()
                        :rules.boosts().values().stream().filter(b->b.enabled()&&(b.allowedProfiles().isEmpty()||b.allowedProfiles().contains(profile))&&access.allows(b.permission(),b.requiredConditions())).map(b->b.id()).sorted().toList();
                total=ids.size(); pages=(total+pageSize-1)/pageSize;
                if(total==0) status="empty-list";
                else if(context.page()>pages) status="catalog-page-out-of-range";
                else { int offset=(context.page()-1)*pageSize; rows=ids.subList(offset,Math.min(offset+pageSize,total)).stream().map(id->new Entry(id,Optional.<CatalogIndex.PricedTarget>empty())).toList(); }
            }
        }
        return new Preview(effective,source,Optional.of(recommended.sourceValue()),selected,Optional.of(quote),rows,pages,total,status);
    }
}
