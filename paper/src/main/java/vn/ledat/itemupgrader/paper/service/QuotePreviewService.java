package vn.ledat.itemupgrader.paper.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import vn.ledat.itemupgrader.catalog.CatalogAccess;
import vn.ledat.itemupgrader.catalog.CatalogIndex;
import vn.ledat.itemupgrader.cost.ResourceSnapshot;
import vn.ledat.itemupgrader.item.ItemSnapshot;
import vn.ledat.itemupgrader.paper.item.PaperItemSnapshotFactory;
import vn.ledat.itemupgrader.paper.item.PlatformIdentityIndex;
import vn.ledat.itemupgrader.paper.message.Messages;
import vn.ledat.itemupgrader.paper.platform.PlatformAccess;
import vn.ledat.itemupgrader.quote.QuoteRequest;
import vn.ledat.itemupgrader.quote.QuoteResult;
import vn.ledat.itemupgrader.quote.UpgradeQuote;
import vn.ledat.itemupgrader.quote.UpgradeQuoteService;
import vn.ledat.itemupgrader.runtime.PreviewRequestGate;
import vn.ledat.itemupgrader.runtime.RuntimeStore;
import vn.ledat.itemupgrader.runtime.UpgraderRuntime;
import vn.ledat.itemupgrader.util.Decimals;

/** Read-only admin preview. Owner capture -> detached calculation -> owner recheck. No debit/give/roll. */
public final class QuotePreviewService {
    private final JavaPlugin owner;
    private final PlatformAccess platform;
    private final RuntimeStore<UpgraderRuntime> runtime;
    private final Messages messages;
    private final CatalogCacheService cache;
    private final PlatformIdentityIndex identities;
    private final AccessSnapshotService accessSnapshots;
    private final CostResourceCapture resourceCapture;
    private final PaperItemSnapshotFactory snapshots = new PaperItemSnapshotFactory();
    private final UpgradeQuoteService quotes = new UpgradeQuoteService();
    private final PreviewRequestGate jobs = new PreviewRequestGate(16, Duration.ofSeconds(30), System::nanoTime);
    private final AtomicInteger computations = new AtomicInteger();
    public QuotePreviewService(JavaPlugin owner, PlatformAccess platform, RuntimeStore<UpgraderRuntime> runtime, Messages messages,
                               CatalogCacheService cache, PlatformIdentityIndex identities, AccessSnapshotService accessSnapshots) {
        this.owner = owner; this.platform = platform; this.runtime = runtime; this.messages = messages;
        this.cache = cache; this.identities = identities; this.accessSnapshots = accessSnapshots;
        this.resourceCapture = new CostResourceCapture(owner, platform, identities);
    }
    public void preview(Player player, QuoteRequest request) {
        var current = runtime.snapshot();
        if (current.isEmpty()) { messages.send(player, "not-ready"); return; }
        UUID id = player.getUniqueId(); var definition = current.orElseThrow();
        if (jobs.contains(id)) { messages.send(player, "quote-busy"); return; }
        if (!platform.acquire(id, "quote", Duration.ofMillis(definition.value().commandCooldownMillis()))) { messages.send(player, "cooldown"); return; }
        if (player.getInventory().getItemInMainHand().getType().isAir()) { messages.send(player, "empty-hand"); return; }
        var acquired = jobs.begin(id);
        if (acquired.isEmpty()) { messages.send(player, "backend-busy"); return; }
        var job = acquired.orElseThrow(); messages.send(player, "quote-loading");
        try {
            cache.prepare(player, definition, index -> ready(job, definition, index, request),
                    reason -> fail(job, "catalog-build-" + reason.name().toLowerCase(Locale.ROOT)));
        } catch (RuntimeException error) { report(job, error); }
    }
    private void ready(PreviewRequestGate.Ticket job, RuntimeStore.Snapshot<UpgraderRuntime> definition, CatalogIndex index, QuoteRequest request) {
        try {
            platform.player(job.viewer(), player -> {
                if (!jobs.isCurrent(job)) return;
                if (!current(definition, index)) { fail(job, "quote-stale"); return; }
                if (!permitted(player)) { fail(job, "no-permission"); return; }
                try {
                    ItemSnapshot source = captureSource(player, definition, request);
                    CatalogAccess access = accessSnapshots.capture(player, definition.value());
                    var needed = definition.value().upgradeRules().resourcesFor(index.definitions().pathFor(source.facts().key()), request);
                    ResourceSnapshot resources = resourceCapture.capture(player, definition, needed, request.sourceSlot());
                    if (computations.get() >= 16) { fail(job, "backend-busy"); return; }
                    computations.incrementAndGet();
                    try { platform.async("itemupgrader-quote", () -> evaluate(job, definition, index, request, source, access, resources)); }
                    catch (RuntimeException rejected) { computations.decrementAndGet(); throw rejected; }
                } catch (IllegalArgumentException invalid) { fail(job, "quote-source-invalid"); }
                catch (RuntimeException error) { report(job, error); }
            });
        } catch (RuntimeException error) { report(job, error); }
    }
    private void evaluate(PreviewRequestGate.Ticket job, RuntimeStore.Snapshot<UpgraderRuntime> definition, CatalogIndex index,
                          QuoteRequest request, ItemSnapshot source, CatalogAccess access, ResourceSnapshot resources) {
        try {
            if (!jobs.isCurrent(job) || !runtime.isCurrent(definition.revision())) { fail(job, "quote-stale"); return; }
            QuoteResult result = quotes.quote(request, source, index, access, definition.value().upgradeRules(), resources, Instant.now());
            platform.player(job.viewer(), online -> {
                if (!jobs.isCurrent(job)) return;
                if (!current(definition, index)) { fail(job, "quote-stale"); return; }
                if (!permitted(online)) { fail(job, "no-permission"); return; }
                try {
                    ItemSnapshot actual = captureSource(online, definition, request);
                    var needed = definition.value().upgradeRules().resourcesFor(index.definitions().pathFor(actual.facts().key()), request);
                    var actualResources = resourceCapture.capture(online, definition, needed, request.sourceSlot());
                    if (!source.fingerprint().equals(actual.fingerprint()) || !source.facts().equals(actual.facts())
                            || !access.equals(accessSnapshots.capture(online, definition.value())) || !resources.equals(actualResources)
                            || result.quote().filter(quote -> !Instant.now().isBefore(quote.selection().expiresAt())).isPresent()) {
                        fail(job, "quote-stale"); return;
                    }
                    if (!jobs.finish(job)) return;
                    render(online, result);
                } catch (RuntimeException error) {
                    owner.getLogger().log(Level.FINE, "Quote input/provider changed before presentation", error);
                    if (jobs.isCurrent(job)) fail(job, "quote-stale"); else messages.send(online, "quote-failed");
                }
            });
        } catch (RuntimeException error) { report(job, error); }
        finally { computations.decrementAndGet(); }
    }
    private boolean current(RuntimeStore.Snapshot<UpgraderRuntime> definition, CatalogIndex index) {
        return runtime.isCurrent(definition.revision()) && cache.current(index);
    }
    private ItemSnapshot captureSource(Player player, RuntimeStore.Snapshot<UpgraderRuntime> definition, QuoteRequest request) {
        if (player.getInventory().getHeldItemSlot() != request.sourceSlot()) throw new IllegalArgumentException("held slot changed");
        var held = player.getInventory().getItemInMainHand();
        if (held.getType().isAir() || held.getAmount() < 1) throw new IllegalArgumentException("empty source");
        var identity = identities.identify(held, definition);
        if (identity.key().isEmpty()) throw new IllegalArgumentException("source identity unverified");
        return snapshots.capture(held, identity.key().orElseThrow());
    }
    private static boolean permitted(Player player) { return player.hasPermission("ledatitemupgrader.use") && player.hasPermission("ledatitemupgrader.admin.quote"); }
    private void render(Player player, QuoteResult result) {
        if (result.quote().isEmpty()) {
            messages.send(player, "quote-denied-" + result.status().name().toLowerCase(Locale.ROOT).replace('_', '-')); return;
        }
        UpgradeQuote quote = result.quote().orElseThrow();
        messages.send(player, "quote-header", Map.of("target", quote.request().targetId(), "profile", quote.terms().profileId(),
                "source_value", Decimals.display(quote.sourceTotal()), "target_value", Decimals.display(quote.targetTotal())));
        messages.send(player, "quote-chance", Map.of("chance", Decimals.display(quote.chance().probability().percent()),
                "tickets", String.valueOf(quote.chance().probability().winningTickets())));
        messages.send(player, "quote-failure-" + quote.terms().failure().name().toLowerCase(Locale.ROOT));
        quote.terms().output().ifPresent(output -> {
            messages.send(player, "quote-output-policy", Map.of("policy", output.transfer().id()));
            switch (output.failure()) {
                case vn.ledat.itemupgrader.failure.FailurePolicy.Damage loss -> messages.send(player, "quote-damage-detail",
                        Map.of("percent", Decimals.display(java.math.BigDecimal.valueOf(loss.basisPoints(), 2)), "behavior", loss.breakBehavior().name()));
                case vn.ledat.itemupgrader.failure.FailurePolicy.Downgrade loss -> messages.send(player, "quote-downgrade-detail",
                        Map.of("item", loss.key().value(), "amount", String.valueOf(loss.amount()), "policy", loss.transfer().id()));
                default -> { }
            }
        });
        if (!quote.terms().boosts().isEmpty()) messages.send(player, "quote-boosts", Map.of("boosts", String.join(", ", quote.terms().boosts())));
        if (!quote.terms().permissionBonuses().isEmpty()) messages.send(player, "quote-bonuses", Map.of("bonuses", String.join(", ", quote.terms().permissionBonuses())));
        if (quote.chance().clamped()) messages.send(player, "quote-clamped");
        if (quote.terms().costs().lines().isEmpty()) messages.send(player, "quote-no-extra-cost");
        for (var line : quote.terms().costs().lines().stream().limit(8).toList())
            messages.send(player, "quote-cost", Map.of("resource", line.resource().canonical(), "reserve", Decimals.display(line.reserve()),
                    "attempt", Decimals.display(line.onAttempt()), "success", Decimals.display(line.onSuccess()), "failure", Decimals.display(line.onFailure())));
        if (quote.terms().costs().lines().size() > 8) messages.send(player, "quote-cost-overflow", Map.of("amount", String.valueOf(quote.terms().costs().lines().size() - 8)));
        messages.send(player, "quote-resources-" + quote.resources().status().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        for (var issue : quote.resources().issues().stream().limit(8).toList()) messages.send(player, "quote-resource-issue", Map.of("resource", issue.resource().canonical(),
                "status", issue.reason().name(), "required", Decimals.display(issue.required()), "available", Decimals.display(issue.available())));
        messages.send(player, "quote-readonly");
    }
    /** Paginated config discovery; path eligibility and cost affordability are still enforced when quoting. */
    public void list(Player player, boolean profiles, int page) {
        var current = runtime.snapshot();
        if (current.isEmpty()) { messages.send(player, "not-ready"); return; }
        var rules = current.orElseThrow().value().upgradeRules();
        if (!platform.acquire(player.getUniqueId(), "quote-lists", Duration.ofMillis(current.orElseThrow().value().commandCooldownMillis()))) { messages.send(player, "cooldown"); return; }
        var access = accessSnapshots.capture(player, current.orElseThrow().value());
        List<String> rows = profiles ? rules.profiles().values().stream().filter(value -> value.enabled() && access.allows(value.permission(), value.requiredConditions())).map(value -> value.id()).sorted().toList()
                : rules.boosts().values().stream().filter(value -> value.enabled() && access.allows(value.permission(), value.requiredConditions())).map(value -> value.id()).sorted().toList();
        int pages = Math.max(1, (rows.size() + 7) / 8);
        if (page < 1 || page > pages) { messages.send(player, "quote-invalid-page", Map.of("pages", String.valueOf(pages))); return; }
        messages.send(player, profiles ? "quote-profiles-header" : "quote-boosts-header", Map.of("page", String.valueOf(page), "pages", String.valueOf(pages)));
        for (String id : rows.subList((page - 1) * 8, Math.min(page * 8, rows.size()))) {
            if (profiles) {
                var profile = rules.profiles().get(id);
                messages.send(player, "quote-profile-row", Map.of("id", id, "formula", profile.formulaId(), "multiplier", Decimals.display(profile.chanceMultiplier()),
                        "failure", profile.failure().name(), "fee_multiplier", Decimals.display(profile.feeMultiplier())));
            } else {
                var boost = rules.boosts().get(id);
                messages.send(player, "quote-boost-row", Map.of("id", id, "multiplier", Decimals.display(boost.chanceMultiplier()), "points", Decimals.display(boost.bonusPercentagePoints()),
                        "protection", boost.protection().name()));
            }
        }
        messages.send(player, "quote-list-readonly");
    }
    private void report(PreviewRequestGate.Ticket job, RuntimeException error) {
        owner.getLogger().log(Level.WARNING, "Quote preview failed; no inventory/currency effects exist in this phase", error);
        fail(job, "quote-failed");
    }
    private void fail(PreviewRequestGate.Ticket job, String key) {
        if (!jobs.finish(job) || runtime.state() == RuntimeStore.State.STOPPED) return;
        try { platform.player(job.viewer(), player -> messages.send(player, key)); }
        catch (RuntimeException rejected) { owner.getLogger().log(Level.FINE, "Quote callback retired/rejected", rejected); }
    }
    public void forget(UUID viewer) { jobs.forget(viewer); }
    public void close() { jobs.close(); }
}
