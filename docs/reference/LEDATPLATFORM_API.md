# LeDatPlatform API Usage Guide

Tài liệu này dành cho plugin con dùng `ledat-platform-api` ở compile time và
`LeDatPlatform` ở runtime. Phiên bản API hiện tại là **2.10.0**.

Khi giao việc cho AI viết hoặc sửa plugin, hãy đưa kèm
[AI Implementation Playbook](./LEDAT_PLATFORM_AI_PLAYBOOK.md). File này là API
reference đầy đủ; playbook là contract/rule để AI chọn đúng service và không viết
code vi phạm Folia, lifecycle hoặc dependency boundary.

Nguyên tắc quan trọng: chỉ import `vn.ledat.platform.api.*`; không import
`vn.ledat.platform.core.*` và không shade API/core vào jar plugin con.

## 1. Cài đặt và bootstrap

Trong monorepo:

```groovy
dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly(project(":ledat-platform-api"))
    testImplementation(project(":ledat-platform-api"))
}
```

Plugin con cần hard-depend vào core:

```yaml
name: MyLeDatPlugin
version: ${version}
main: com.example.myledat.MyLeDatPlugin
api-version: '1.21'
folia-supported: true
depend:
  - LeDatPlatform
commands:
  myplugin:
    description: My command
```

Lấy API trong `onEnable()` sau khi dependency đã bật:

```java
public final class MyLeDatPlugin extends JavaPlugin {
    private LeDatPlatformApi platform;

    @Override
    public void onEnable() {
        platform = LeDatPlatformProvider.get();
        getLogger().info("LeDatPlatform API " + platform.apiVersion());
    }

    public LeDatPlatformApi platform() {
        return platform;
    }
}
```

## 2. Cleanup đúng ownership

LeDatPlatform có safety-net tự cleanup khi plugin owner bị disable. Plugin con vẫn
nên chủ động flush dữ liệu cần durability và dọn state riêng:

```java
@Override
public void onDisable() {
    if (platform == null) return;

    try {
        platform.userCaches().flush(this).get(5, TimeUnit.SECONDS);
        platform.database().flush(this).get(5, TimeUnit.SECONDS);
    } catch (Exception error) {
        getLogger().warning("Could not flush data: " + error.getMessage());
    }

    platform.commands().unregisterPlugin(this);
    platform.placeholders().unregisterPlugin(this);
    platform.hooks().unregisterPlugin(this);
    platform.guis().cleanup(this);
    platform.dialogs().cleanup(this);
    platform.forms().cleanup(this);
    platform.languages().unregisterPlugin(this);
    platform.executors().shutdown(this);
    platform.scheduler().cancelAll(this);
    platform.database().closePlugin(getName());
}
```

Không tạo scheduler/executor raw nếu không cần. Dùng `platform.scheduler()` và
`platform.executors()` để Folia ownership, diagnostics và shutdown được quản lý.

## 3. Scheduler và thread safety

- JDBC, file, network, CPU nặng: chạy async.
- Player/entity: quay lại entity/player scheduler.
- Block/chunk/world: dùng region/location scheduler.
- Không chạm Bukkit world state từ callback async.

```java
LeDatScheduler.ScopedScheduler scheduler = platform.scheduler().scoped(this);

scheduler.runAsync("load-profile", () -> {
    UserData data = loadFromDatabase(playerId);
    scheduler.runOnlinePlayer(playerId, player -> applyProfile(player, data));
});

scheduler.teleportAsync(player, target).thenAccept(success -> {
    if (!success) getLogger().warning("Teleport failed");
});
```

`runOnlinePlayer(...)` resolves the UUID on the platform/global scheduler and then uses the
retirement-safe entity operation path. If the player retires, disconnects, or the owner is
disabled before execution, the callback is discarded; it is never run as a global fallback.
Use `runEntityOperation(...)` directly when the caller must distinguish `EXECUTED`, `RETIRED`,
`REJECTED`, and `FAILED`.

## 4. Database, cache và audit

### Shared SQL và per-plugin storage

Không giữ `Connection` ngoài lambda của provider:

```java
platform.database().query("myplugin-load-user", connection -> {
    try (PreparedStatement statement = connection.prepareStatement(
            "SELECT coins FROM " + platform.database().tableName("myplugin", "users") + " WHERE uuid=?")) {
        statement.setString(1, userId.toString());
        try (ResultSet result = statement.executeQuery()) {
            return result.next() ? result.getLong("coins") : 0L;
        }
    }
});
```

Khi không muốn dùng shared SQL, dùng `yamlStore(...)` hoặc `sqliteStore(...)` theo
plugin owner. Platform đã xử lý WAL/checkpoint/health-check cho SQLite và pool cho
MySQL; vẫn cần gọi `flush` trước disable nếu dữ liệu quan trọng.

### User cache write-behind

`UserCacheService` coalesce load trùng UUID, giới hạn memory, hỗ trợ negative cache
và không cho DB result cũ ghi đè dữ liệu dirty mới hơn.

```java
UserCacheService.UserCache<UserData> users = platform.userCaches()
        .cache(this, "users", UserData.class)
        .maximumSize(10_000)
        .expireAfterAccess(Duration.ofMinutes(15))
        .refreshAfterWrite(Duration.ofMinutes(5))
        .cacheMisses(Duration.ofSeconds(30))
        .writeBehind(Duration.ofSeconds(2))
        .loader(uuid -> loadUserAsync(uuid))       // CompletableFuture<Optional<UserData>>
        .writer((uuid, data) -> saveUserAsync(uuid, data)) // CompletableFuture<Void>
        .build();

users.get(playerId).thenAccept(optional -> optional.ifPresent(data -> {
    // Route về player/entity scheduler trước khi dùng Bukkit state.
}));
users.put(playerId, changedData);
```

Dùng `putAndSave(...)` khi cần durability ngay. `platform.userCaches().flush(this)`
chỉ flush các cache do plugin hiện tại sở hữu.

### Audit và ledger

`audit()` dùng để ghi sự kiện; `ledger()` dùng idempotency cho transaction có retry
hoặc double-click. `recordAll(...)` ghi SQL theo transaction/batch (mặc định 250).
Với dữ liệu nhạy cảm cần fail-closed, dùng `recordVerified(...)`: future chỉ trả `true`
khi audit đang bật, ghi được vào sink bền vững và đọc lại đúng event; implementation cũ
không hỗ trợ contract này sẽ trả `false` theo mặc định.

```java
platform.audit().recordAll(List.of(
        new AuditRecord("myplugin", "economy", "pay", actorId, targetId,
                "success", "money", amount.toPlainString(), Map.of("currency", "vault"), Instant.now())
));

platform.audit().recordVerified(sensitiveAccessRecord).thenAccept(verified -> {
    if (verified) {
        // Chỉ tiếp tục đọc/hiển thị dữ liệu nhạy cảm sau bước này.
    }
});
```

## 5. Command tree, typed arguments và completion

Command root vẫn nên có trong `plugin.yml`. Builder hỗ trợ subcommand, alias,
permission từng nhánh, typed argument và completion theo argument:

```java
platform.commands().command(this, "reward")
        .aliases("rewards")
        .permission("myplugin.reward")
        .usage("/reward give <player> <item> [amount]")
        .subCommand("give", give -> give
                .aliases("grant")
                .permission("myplugin.reward.give")
                .playerArgument("player")
                .customItemArgument("item")
                .optionalIntegerArgument("amount", 1, 64)
                .suggestions("item", context ->
                        platform.items().suggestions(context.arg(1, "")).stream().toList())
                .minArgs(2)
                .maxArgs(3)
                .executor(context -> {
                    Player target = context.onlinePlayerArg(0).orElseThrow();
                    ItemStack item = platform.items().create(context.arg(1), context.intArg(2, 1))
                            .orElseThrow(() -> new IllegalArgumentException("Unknown item"));
                    target.getInventory().addItem(item);
                }))
        .register();
```

Các helper gồm player/offline-player, material, world, UUID, boolean, duration
(`15s`, `10m`, `2h`, `1d`, `1w`), number range, enum, choices và greedy string.

## 6. GUI và dialog

GUI mới hỗ trợ Adventure title, character pattern, `row` và `border`, nhưng API slot
cũ vẫn dùng được:

```java
GuiService.PlatformGui menu = platform.guis().gui(this, "rewards")
        .title(Component.text("Rewards"))
        .pattern(
                "#########",
                "#..III..#",
                "#########")
        .button('#', borderItem, click -> {})
        .button('I', rewardItem, click -> claimReward(click.player()))
        .build();

platform.guis().open(player, menu);
```

For delayed timeout/reload cleanup, keep the returned `GuiSession.sessionId()` and call
`closeSession(playerId, expectedSessionId)`. The implementation rechecks identity on the
player owner so late cleanup cannot close a newer replacement GUI.

Dialog dùng native Paper khi khả dụng, nếu không sẽ dùng fallback theo config:

```java
platform.dialogs().confirm(player, this, "purchase",
        Component.text("Confirm purchase"),
        List.of(Component.text("Buy this item?")),
        click -> purchase(click.player()),
        click -> {},
        close -> cleanupPendingPurchase(close.player()));
```

Dùng `tryOpen(...)` khi UI là optional flow; dùng `notice(...)` cho thông báo một
nút. Luôn dọn temporary transaction state ở `onClose`.

## 7. Custom items và integrations gộp trong core jar

Bạn chỉ cài một jar LeDatPlatform. Các bridge đã có sẵn trong core và tự bật khi
server có provider tương ứng:

| Provider | Key |
| --- | --- |
| Vanilla | `minecraft:diamond` |
| ItemsAdder | `itemsadder:<namespace>:<id>` |
| Oraxen | `oraxen:<id>` |
| Nexo | `nexo:<id>` |
| MMOItems | `mmoitems:<type>:<id>` |

```java
ItemStack item = platform.items().create("mmoitems:sword:flame_blade", 1)
        .orElseThrow(() -> new IllegalStateException("Provider or item unavailable"));

Optional<CustomItemKey> key = platform.items().identify(item);
```

ItemsAdder/Oraxen/Nexo/MMOItems đều fail-closed: thiếu plugin hoặc API provider đổi
thì trả `Optional.empty()`, không crash core. Dùng `platform.items().stats()` và
`platform.items().adapters()` cho diagnostics.

## 8. GameProfile, skin và player heads

Profile lookup là async, coalesce request trùng, có positive/negative cache. Không
block main thread chờ Mojang profile service.

```java
platform.profiles().head(playerId, playerName).thenAccept(head ->
        platform.scheduler().scoped(this).runOnlinePlayer(playerId,
                player -> player.getInventory().addItem(head)));

ItemStack customHead = platform.profiles().texturedHead(new URL(skinUrl), "Custom head");
```

Dùng `invalidate(uuid, name)` khi dữ liệu skin thay đổi; dùng `stats()` để xem hit,
miss và failure cache.

## 9. Player-placed block tracker

Tracker lưu trạng thái trong PDC của chunk, không dùng database. Core tự theo dõi
place/break/explosion khi `player-block-tracker.auto-track=true`.

```java
if (platform.playerBlocks().isPlayerPlaced(block)) {
    // Ví dụ: chỉ drop item đối với block người chơi đặt.
}

platform.playerBlocks().markPlayerPlaced(block);
platform.playerBlocks().unmark(block);
```

Chỉ gọi API block/chunk trên đúng entity/region thread, đặc biệt trên Folia.

## 10. Economy, PlaceholderAPI, LuckPerms và Floodgate

Các integration này cũng nằm trong core jar:

- Vault được chọn làm `primary()` nếu khả dụng.
- PlayerPoints đăng ký provider `playerpoints`.
- PlaceholderAPI tự đăng ký `%ledat_<route>_<parameter>%`.
- LuckPerms có thể lấy qua `HookRegistry`.
- Floodgate/Geyser được dùng cho Bedrock form bridge.

```java
platform.economy().primary().ifPresent(economy -> {
    BigDecimal balance = economy.balance(player);
    EconomyService.TransactionResult result = economy.withdraw(player, BigDecimal.TEN);
    if (!result.success()) getLogger().warning(result.error());
});

platform.placeholders().registerRoute(this, "wallet", (offlinePlayer, parameter) -> {
    if (offlinePlayer == null) return "";
    return "coins".equalsIgnoreCase(parameter) ? String.valueOf(balance(offlinePlayer)) : "";
}, "Wallet placeholders");
```

Không thêm `PlaceholderAPI`, Vault hoặc custom-item APIs vào dependency bắt buộc của
plugin con nếu feature của plugin không yêu cầu chúng trực tiếp. Kiểm tra
`platform.hooks().available("Vault")` hoặc `platform.economy().primary()` trước khi dùng.

## 11. Artifact phát hành

- `LeDatPlatform-<version>.jar`: bản production gọn; server tải SQLite/MySQL bằng
  `plugin.yml libraries`.
- `LeDatPlatform-<version>-offline-full.jar`: nhúng driver, dùng cho host offline.

Chỉ cài **một** trong hai artifact vào thư mục `plugins/`. Không cài các jar
`ledat-integration-*`; chúng không còn thuộc build phát hành.

## 12. Checklist tránh lỗi

- Không dùng `vn.ledat.platform.core.*` trong plugin con.
- Không chạy JDBC/Bukkit world operation sai thread.
- Không giữ `Connection` sau callback provider.
- Không dùng audit làm idempotency guard; dùng ledger cho transaction retry.
- Không quên flush cache/database khi có dữ liệu write-behind quan trọng.
- Không cài song song jar standard và offline-full.

## 13. LanguageService và locale

Plugin con có thể ship file locale trong jar:

```text
src/main/resources/languages/vi_VN.yml
src/main/resources/languages/en_US.yml
src/main/resources/languages/zh_CN.yml
```

Platform copy/load chúng vào `plugins/<PluginName>/languages/<locale>.yml`. Ví dụ:

```yaml
language-version: 1
messages:
  hello: "<green>Xin chào, {player}!"
  balance: "<gray>Số dư: <yellow>{amount}"
  help:
    - "<gold>/wallet"
    - "<gold>/wallet pay <player> <amount>"
```

Đăng ký và gửi message:

```java
platform.languages().registerPlugin(this);
platform.languages().reload(this);

platform.languages().send(player, this, "messages.hello", Map.of(
        "player", player.getName()
));
platform.languages().sendList(sender, this, "messages.help", List.of(), Map.of());

String locale = platform.languages().resolveLocale(player);
Component balance = platform.languages().component(this, locale, "messages.balance", Map.of(
        "amount", "1,000"
));
```

Lắng nghe đổi locale khi plugin có cache UI theo ngôn ngữ:

```java
AutoCloseable subscription = platform.languages().listenLocaleChanges(change ->
        getLogger().fine("Locale changed: " + change.playerId() + " -> " + change.newLocale()));
```

Chỉ dùng `componentTrusted(...)` hoặc `sendTrusted(...)` cho MiniMessage do admin
kiểm soát và có whitelist key. Không đánh dấu input người chơi là trusted.

## 14. ConfigService và migration YAML

Khai báo config version từ đầu để platform migrate file bundled an toàn:

```java
private static final int CONFIG_VERSION = 1;

platform.configs().ensureMainConfig(this, CONFIG_VERSION);
ConfigValidationReport report = platform.configs().validate(this);
report.warnings().forEach(warning -> getLogger().warning("[config] " + warning));
report.errors().forEach(error -> getLogger().severe("[config] " + error));
```

Copy hoặc migrate file YAML phụ:

```java
platform.configs().ensureBundledYaml(this, "menus/main.yml");
platform.configs().migrateBundledYaml(this, "menus/main.yml", "config-version", 2);

Path logFolder = platform.configs().pluginLogFolder(this, "myplugin");
```

Không overwrite thủ công config người dùng trong `onEnable()`. Dùng migration service để
giữ comment/dữ liệu cũ và chỉ thêm default còn thiếu.

## 15. Cooldown và rate limit

Cooldown phù hợp cho một action đơn; rate limit phù hợp với cửa sổ nhiều action:

```java
UUID subject = player.getUniqueId();
if (!platform.cooldowns().tryAcquire(getName(), subject, "open-menu", Duration.ofSeconds(2))) {
    Duration remaining = platform.cooldowns().remaining(getName(), subject, "open-menu");
    platform.languages().send(player, this, "messages.cooldown", Map.of(
            "seconds", String.valueOf(remaining.toSeconds())
    ));
    return;
}

var result = platform.rateLimits().tryAcquire(getName(), subject, "chat-action", 5, Duration.ofSeconds(10));
if (!result.allowed()) {
    platform.languages().send(player, this, "messages.rate-limited", Map.of(
            "seconds", String.valueOf(result.retryAfter().toSeconds())
    ));
}
```

Namespace bằng `getName()` hoặc một tên ổn định của plugin. Dọn namespace khi disable
nếu plugin có state logic riêng; lifecycle service cũng có safety-net.

## 16. Bedrock Form Service

`forms()` dùng Floodgate/Geyser bridge khi khả dụng và khi người chơi là Bedrock
client. Luôn kiểm tra `canOpen(player)` trước flow chỉ có UI Bedrock:

```java
if (platform.forms().canOpen(player)) {
    platform.forms().simple(this, "main")
            .title(Component.text("Menu"))
            .content(Component.text("Choose an action"))
            .button("rewards", Component.text("Rewards"), click -> openRewards(click.player()))
            .button("settings", Component.text("Settings"), click -> openSettings(click.player()))
            .open(player);
}
```

Modal confirm:

```java
platform.forms().modal(this, "purchase-confirm")
        .title(Component.text("Confirm purchase"))
        .content(Component.text("Buy this item?"))
        .confirm(Component.text("Buy"), click -> purchase(click.player()))
        .cancel(Component.text("Cancel"), click -> {})
        .open(player);
```

Custom form:

```java
platform.forms().custom(this, "profile-filter")
        .title(Component.text("Filter"))
        .input("name", Component.text("Player name"), "Steve", "")
        .toggle("onlineOnly", Component.text("Online only"), true)
        .submit(Component.text("Apply"), click -> {
            String name = click.inputString("name", "");
            boolean onlineOnly = click.inputBoolean("onlineOnly", true);
            applyFilter(click.player(), name, onlineOnly);
        })
        .open(player);
```

Gọi `platform.forms().cleanup(playerId)` khi player quit nếu plugin có lifecycle
listener riêng; gọi `cleanup(this)` ở disable.

## 17. Sound Service

Core có các cue mặc định như `click`, `confirm`, `cancel`, `error`:

```java
platform.sounds().playCue(this, player, "click");
platform.sounds().playCue(this, player, "confirm",
        SoundCue.of(Sound.ENTITY_PLAYER_LEVELUP, 0.8F, 1.2F));
platform.sounds().playCountdownCue(this, player, "countdown", secondsLeft);
```

Đọc cue do plugin con cấu hình với fallback rõ ràng:

```java
SoundCue fallback = SoundCue.of(Sound.UI_BUTTON_CLICK, 0.8F, 1.0F);
SoundCue cue = platform.sounds().cue(this, getConfig(), "sounds.open", fallback);
if (cue.enabled() && cue.sound() != null) {
    platform.sounds().play(this, player, cue.sound(), cue.category(), cue.volume(), cue.pitch());
}
```

## 18. Placeholder Router và Hook Registry

Core tự expose canonical PlaceholderAPI expansion `%ledat_<route>_<parameter>%`
khi PlaceholderAPI có mặt. Plugin con chỉ đăng ký route:

```java
platform.placeholders().registerRoute(this, "wallet", (offlinePlayer, parameter) -> {
    if (offlinePlayer == null) return "";
    return switch (parameter.toLowerCase(Locale.ROOT)) {
        case "coins" -> String.valueOf(balance(offlinePlayer));
        case "name" -> Optional.ofNullable(offlinePlayer.getName()).orElse("");
        default -> "";
    };
}, "Wallet placeholders");
```

Ví dụ sử dụng:

```text
%ledat_wallet_coins%
%ledat_wallet_name%
```

`HookRegistry` là registry trạng thái/adapters optional. Không assume plugin phụ
luôn tồn tại:

```java
if (platform.hooks().available("Vault")) {
    platform.economy().provider("vault").ifPresent(vault -> {
        // Dùng adapter trung lập của platform.
    });
}

platform.hooks().adapter("LuckPerms", Object.class)
        .ifPresent(adapter -> getLogger().fine("LuckPerms bridge ready"));
```

Không đăng ký lại `PlaceholderExpansion` riêng với identifier `ledat`; core đã quản
lý expansion này. Plugin con unregister route của chính mình khi disable.

## 19. Ledger service: idempotency cho giao dịch

Audit trả lời “đã có gì xảy ra”; ledger trả lời “transaction này đã được claim/
hoàn tất chưa”. Dùng ledger cho pay, reward, crate delivery, redeem và flow có retry:

```java
String transactionId = UUID.randomUUID().toString();
String idempotencyKey = "wallet:" + actor.getUniqueId() + ":" + requestId;

LedgerTransaction transaction = new LedgerTransaction(
        transactionId, idempotencyKey, "myplugin", "wallet_pay",
        actor.getUniqueId(), target.getUniqueId(), "vault", amount,
        before, after, "player-command", Map.of("target", target.getName())
);

platform.ledger().claim(transaction).thenCompose(claim -> {
    if (claim.duplicate()) return CompletableFuture.completedFuture(null);
    return performPayment(actor, target, amount).thenCompose(success -> success
            ? platform.ledger().success("myplugin", transactionId, idempotencyKey, Map.of())
            : platform.ledger().fail("myplugin", transactionId, idempotencyKey,
                    "payment-failed", Map.of()));
});
```

## 20. Managed executors, guards, server links và doctor

Tạo executor qua platform để owner được theo dõi và shutdown được:

```java
private ManagedExecutorService.ExecutorHandle worker;

private void startWorker() {
    worker = platform.executors().fixed(this, "profile-worker", 2, 1024);
    worker.runAsync("warm-cache", this::warmCache);
}

private void stopWorker() {
    if (worker != null) worker.shutdown();
    platform.executors().shutdown(this);
}
```

Guard feature giúp phát hiện operation có nguy cơ chạy nhầm main thread:

```java
platform.guards().runGuarded(this, "rebuild-index", this::rebuildIndex);
if (platform.guards().productionProfileEnabled() && platform.guards().checkMainThread()) {
    getLogger().warning("Move heavy work off the main/global thread.");
}
```

Server link và doctor:

```java
if (platform.serverLinks().enabled() && platform.serverLinks().available()) {
    platform.serverLinks().apply(player);
}

platform.doctor().check("storage").forEach(row ->
        getLogger().info(row.id() + " = " + row.state() + " " + row.detail()));
```

Admin commands hữu ích:

```text
/ledatplatform status
/ledatplatform doctor all
/ledatplatform scheduler owners
/ledatplatform commands list <plugin>
/ledatplatform storage verify <plugin> --deep
/ledatplatform caches
/ledatplatform items
/ledatplatform profiles
/ledatplatform blocks
/ledatplatform economy
/ledatplatform hooks
/ledatplatform reload
```

## 21. Anti-pattern đầy đủ

- Không import `vn.ledat.platform.core.*` trong plugin con.
- Không dùng Bukkit scheduler trực tiếp cho task mới khi platform scheduler đã có.
- Không chạm Player/Entity/block/world từ async callback.
- Không giữ JDBC `Connection` vượt khỏi callback provider.
- Không dùng MiniMessage trusted cho input người chơi.
- Không tạo raw thread pool mà không đăng ký qua `api.executors()`.
- Không quên unregister command/placeholder/hook khi reload/disable.
- Không coi audit là idempotency guard; dùng ledger cho retry/double-submit.
- Không cài cả standard jar và offline-full jar.
