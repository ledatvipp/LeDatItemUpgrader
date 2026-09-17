# LeDatItemUpgrader — Phase 0–3

**Source tiếp nối Phase 2: thêm Chance, Risk Profile, Cost, Boost, typed conditions và quote chỉ đọc.**

Version: `0.3.0-phase03`. Bàn giao: 2026-09-16. Baseline thực tế: `LeDatItemUpgrader-phase02-source.zip` do hội thoại cung cấp.
Target giữ nguyên **Paper 1.21.4 / Java 21 / LeDatPlatform API 2.10.0**. Không tự nâng version; chưa claim toàn bộ `1.21.4+` hoặc Folia.

**Core đã compile/test thật. Module Paper mới có source tích hợp và kiểm tra syntax, chưa compile/link hoặc chạy với SDK/server thật. Bundle không có plugin JAR đã xác minh để cài server.** API guide không thay thế JAR/source `ledat-platform-api`.

## Trạng thái kiểm chứng

| Phạm vi | Kết quả thực thi |
|---|---|
| Core production + test compile | Java 21, `--release 21 -Xlint:all -Werror` |
| Phase 0–1 regression | 126 nhóm / 3.643 assertion / 0 lỗi |
| Phase 2 regression | 123 nhóm / 17.897 assertion / 0 lỗi |
| Phase 3 mới | 182 nhóm / 15.730 assertion / 0 lỗi |
| **Tổng core** | **431 nhóm / 37.270 assertion / 0 lỗi** |
| Value/Catalog/Quote CLI demo | Chạy thật với dữ liệu giả lập, không phải server |
| Resource consistency bổ sung | 506 checks / 12 YAML / PyYAML 6.0.3 |
| Java syntax-only | 87 file trong `src/`, 0 syntax error; không resolve dependency/type |
| Paper, Platform, PAPI, economy integration | **Chưa compile/link/run thật** |
| Inventory GUI / packets / transaction / outcome draw | Chưa triển khai; không tiêu hao nguồn/tiền/booster |

Bằng chứng và giới hạn: `verification/BUILD_REPORT.md`. Các assertion trong vòng invariant không được gọi thành số lượng test server/benchmark độc lập.

## Phase 3 đã nối gì?

`UpgradeRules` compile formula/profile/boost/permission/condition/path-profile thành một bộ bất biến cùng revision với catalog. `UpgradeQuoteService` dùng lại target eligibility của Phase 2, áp profile và bonus, lập yêu cầu cost theo outcome, kiểm tra tài nguyên đã snapshot và trả quote. GUI/transaction phase sau không cần tự tính lại công thức riêng.

Bốn công thức: `RATIO`, `POWER` (mũ nguyên 1..8), `TABLE` và `CURVE` tuyến tính từng đoạn. Không expression engine, JS hoặc script. Core dùng phân số chính xác, cuối cùng chuẩn hóa thành `winningTickets / 1_000_000_000`; không có random roll kết quả ở Phase 3. `UUID.randomUUID()` chỉ tạo ID quote/session, không quyết định thành công.

Thứ tự phép toán:

```text
sourceTotal / targetTotal → formula → profile multiplier
→ tích permission multipliers → tổng permission percentage points
→ tích boost multipliers → tổng boost percentage points
→ min/max clamp → ticket grid
```

`+5 percentage points` khác `×1.05`: từ 9% lần lượt ra **14%** và **9,45%**. Các multiplier/points trong cùng nhóm được gom, nên thứ tự click booster không đổi kết quả. Permission points chịu multiplier của boost ở bước sau; đây là contract cố định, không có hai cách hiểu.

### Demo kết quả đã chạy

Fixture: source iron ×1 có value 90, target diamond ×1 có value 900. Đây là value giả lập nội bộ, không phải định giá thị trường hay kết quả provider thật.

| Trường hợp | Chance | Yêu cầu phí / bảo hộ |
|---|---:|---|
| Standard | 9% | Chỉ nguồn |
| Safe | 5,4% | Vault 43,75 ON_ATTEMPT; KEEP khi thất bại |
| Risky | 11,7% | Vault 7,5 ON_ATTEMPT; DESTROY khi thất bại |
| Standard + VIP ×1.05 | 9,45% | Permission bonus trong fixture |
| Standard + lucky +5PP | 14% | 2 emerald ON_ATTEMPT |
| Standard + VIP + lucky | 14,45% | 2 emerald ON_ATTEMPT |
| Standard + protection | 9% | 1 scroll ON_FAILURE; KEEP khi thất bại |
| Safe, không có balance evidence Vault | 5,4% | **UNAVAILABLE**, không coi là miễn phí |

**Demo tự bật bonus/boost trong fixture. File YAML phát hành để cả hai booster và VIP bonus `enabled: false`**, tránh tự kích hoạt ví dụ PAPER/emerald trên server.

### Cost và bảo hộ

Ba thời điểm: `ON_ATTEMPT`, `ON_SUCCESS`, `ON_FAILURE`. Với mỗi resource:

```text
cần giữ trước = ON_ATTEMPT + max(ON_SUCCESS, ON_FAILURE)
tiêu khi thắng = ON_ATTEMPT + ON_SUCCESS
tiêu khi thua  = ON_ATTEMPT + ON_FAILURE
```

Ví dụ 10/20/30 → cần giữ 40, thắng mất 30, thua mất 40; không cộng cả hai kết quả thành 60. Đây là **kế hoạch reservation**, chưa giữ/trừ bất kỳ item/currency thật nào.

Gộp cost trùng resource/thời điểm trước CEILING rounding. Profile fee multiplier chỉ nhân phí profile, không nhân giá booster. Chính sách của plugin: Vault 2 chữ số thập phân, PlayerPoints/item số nguyên. Không suy chính sách giá từ `double` balance provider.

Core allocation loại toàn bộ slot nguồn; không dùng armor/offhand/cursor. Cost source và booster cùng key không dùng lại một stack. Paper source chỉ match template đã xác minh identity + `isSimilar`, không dựa vào Material/display name. Template có random stat/UUID có thể không match: không tự hạ chuẩn rồi ăn nhầm đồ quý.

KEEP/bảo hộ không có mất mát khi thất bại mặc định bị chặn. `allow-free-protection` là opt-in rõ của admin; fee chỉ ON_SUCCESS không đủ bảo vệ khỏi retry miễn phí. Protection không giữ nguồn khi thành công. `DESTROY`/`KEEP` ở đây là terms; mutation thật và các mode DAMAGE/DOWNGRADE/transfer thuộc Phase 5.

### Quote không phải transaction

Quote pin player/session, source slot/fingerprint/facts, target/fingerprint, catalog generation, config revision, odds, costs, profile/boost/bonus và expiry. Đổi nguồn/reload/mất quyền/hết hạn → từ chối. Revalidation ra terms khác → `RECONFIRM_REQUIRED`, không âm thầm dùng giá mới. Revalidate terms không đổi giữ nguyên ID/expiry, không gia hạn quote cũ vô hạn.

`AVAILABLE_PREVIEW` chỉ chứng minh snapshot đủ ở lúc đọc; không phải khóa tài nguyên. Mọi thao tác thật sau này phải claim journal/ledger, giành ownership, kiểm tra lại thực tế và xử lý crash gap. Không dùng quote UUID làm bằng chứng thanh toán.

### PAPI và quyền

Core condition so sánh typed NUMBER/TEXT/BOOLEAN; không evaluate biểu thức. Missing/unresolved/error/invalid/budget exceeded luôn denied, kể cả operator NE. Referenced condition ID chưa định nghĩa → reject candidate reload.

Paper source có `PapiConditionHook`, `softdepend: [PlaceholderAPI]`, compile-only compatibility baseline `2.11.6`; dùng String API chính thức, không shade PAPI và không đăng ký lại expansion `ledat`. PAPI chỉ cần khi thực sự cấu hình condition. Toàn bộ resolve ở owner thread; tối đa 32 definitions, deduplicate placeholder, 5 ms kiểm tra **giữa** call; không ngắt được expansion đang block bên trong. Adapter này chưa link/test trên PAPI thật.

## Giữ nguyên core Phase 0–2

Item identity/facts/snapshot defensive-copy, SHA-256 fingerprint, BigDecimal unit/total valuation, manual → rules → provider → recipe → rarity → UNKNOWN. Unknown custom item không fallback sang material sạch. Recipe whitelist với quantity/alternatives/cycle/budget; chưa native recipe registry import.

Path `LOCKED`/`OPEN`: ưu tiên theo source/priority trước access, denied priority không fallback. Catalog chỉ định giá snapshot template xác minh, filter trước pagination, ranking ổn định. Source total phải thấp hơn target total; recommendation không phải RNG. `docs/PHASE02.md` giữ contract chi tiết Phase 2; các đoạn nói “chưa PAPI” ở đó là trạng thái lịch sử, được Phase 3 này bổ sung.

`RuntimeStore` giữ config cũ nếu candidate lỗi, không late-publish sau stop. Menu matrix/action compiler còn nguyên; **chưa có holder/listener/cursor/input GUI thật**.

## Chạy backend đã kiểm chứng

JDK 21, Linux/macOS:

```bash
chmod +x scripts/*.sh
./scripts/test-core.sh
./scripts/run-demo.sh
./scripts/run-catalog-demo.sh
./scripts/run-quote-demo.sh
python scripts/verify-resources.py       # cần PyYAML, chỉ checks bổ sung
java scripts/ParseJavaSources.java .     # chỉ syntax, không compile Paper
```

Windows có `scripts/test-core.ps1`, đã cập nhật để chạy 3 suite nhưng chưa thực thi trên Windows trong môi trường bàn giao. Script demo chỉ dùng class đã compile; khi sửa source, chạy `test-core.sh` trước để tránh dùng class cũ.

## Build plugin khi có dependency thật

Cài JDK 21, Gradle 8.x tương thích (gợi ý baseline 8.14.3) và cung cấp **API JAR thật** của Platform đang chạy. Repo không đóng gói Gradle wrapper JAR và không có SDK stub.

```bash
gradle backendCheck
gradle :paper:build -PplatformApiJar=/absolute/path/ledat-platform-api-2.10.0.jar
```

Hoặc đặt artifact tại `libs/ledat-platform-api-2.10.0.jar`. `verifyPlatformApi` từ chối build nếu file vắng. Full build cần resolve Paper, Adventure/transitive, SnakeYAML và PAPI compile-only từ repo thật. Khi thành công và qua review/staging, output dự kiến là `paper/build/libs/LeDatItemUpgrader-0.3.0-phase03.jar`.

**Lệnh full build trên chưa chạy thành công trong lần bàn giao này.** Môi trường thiếu Gradle executable, DNS tới Gradle distribution thất bại, và chưa có Platform API artifact. Không đổi sang mock API để tạo log build giả. `core/build/libs/*backend-NOT-A-PLUGIN*.jar` không phải plugin Bukkit.

Sau build: dùng server staging có Paper 1.21.4/Java 21/Platform đúng bản; chỉ copy plugin jar đã build vào `plugins`, không copy core jar riêng. PAPI/economy/item providers chỉ cài khi feature tương ứng cần. Kiểm tra `docs/TEST_CHECKLIST.md`; không bật gacha flags.

## Command và permission trong Paper source

| Command | Quyền bổ sung sau `ledatitemupgrader.use` |
|---|---|
| `/upgrader`, `/upgrader help` | Không |
| `/upgrader status` | `ledatitemupgrader.admin.status` |
| `/upgrader value` / `/upgrader inspect` | `.admin.value` / `.admin.inspect` dưới prefix `ledatitemupgrader` |
| `/upgrader reload` | `ledatitemupgrader.admin.reload` |
| `/upgrader catalog [page] [category\|all] [sort]`, `/upgrader recommend`, `/upgrader paths` | `ledatitemupgrader.admin.catalog` |
| `/upgrader quote target-id [profile\|default] [boost1,boost2\|none]` | `ledatitemupgrader.admin.quote` |
| `/upgrader profiles [page]`, `/upgrader boosts [page]` | `ledatitemupgrader.admin.quote` |

Admin permissions mặc định op, gom trong `ledatitemupgrader.admin`. Player preview cần item tay chính; console chỉ help/status/reload. Có cooldown, input validation, tab completion và message fallback. List profile/boost 8 dòng/trang, quote tối đa 8 dòng cost và 8 lỗi resource. Tab completion không evaluate PAPI hoặc query DB; ID gợi ý không có nghĩa đủ điều kiện.

Ví dụ sau khi build/staging đạt:

```text
/upgrader quote diamond
/upgrader quote diamond safe
/upgrader quote diamond standard lucky_shard
/upgrader profiles 1
/upgrader boosts 1
```

Lệnh booster mẫu bị từ chối cho tới khi admin chủ động enable và config đúng token. `/upgrader` vẫn chỉ báo framework, chưa mở Inventory.

## Config, reload và migration

```text
paper/src/main/resources/
├─ plugin.yml
├─ config.yml
├─ messages.yml
├─ menus/upgrader.yml
└─ upgrades/
   ├─ values.yml
   ├─ recipes.yml
   ├─ catalog.yml
   ├─ paths.yml
   ├─ chance.yml       # mới: formula, limits, permission bonus
   ├─ profiles.yml     # mới: risk, fee, path restriction
   ├─ boosts.yml       # mới: consumable chance/protection
   └─ conditions.yml   # mới: typed PAPI definitions
```

Các file mới có version 1 riêng, được ensureBundledYaml. Không overwrite giá cũ, không đổi storage mode, không bắt thay config main/messages version1. New message thiếu trong file người dùng dùng bundled fallback. **Thay đổi validate có chủ ý:** các condition ID đã khai trong Phase2 nay phải định nghĩa tại `conditions.yml`; không còn giữ unknown reference âm thầm denied mãi. Candidate sai → giữ runtime cũ, initial startup sai → unavailable.

Decimals phải có nháy. Toàn bộ messages trong `messages.yml`, UI text trong menu. Không parse player text/PAPI output thành MiniMessage. Không silently lấy fee 0 khi provider thiếu hoặc sai số.

```yaml
features:
  upgrades-enabled: false
  packet-renderer-enabled: false
```

Hai flag `true` vẫn bị reject. Schema metadata nền giữ `PLATFORM_SHARED`, `initialize-schema: false`; chưa có transaction/history/pity schema hoặc isolated SQLite mới.

## Performance và release gates

Catalog cache budget16 MiB, một cold build, owner capture batch theo config, async detached index/query. Quote có riêng 16 request và 16 computations đang chờ/chạy, timeout 30s; tổng concurrency còn gồm catalog jobs. Cost evidence1 MiB,32 resources; không global player scan/repeating task mới. Đây là giới hạn thiết kế, không có kết quả TPS/MSPT/load-test.

Native capture/permission/PAPI/balance/create/identity và render chạy owner thread; core math/config/DB async qua Platform. Provider có thể tự block trong API call: cần staging review provider và đo thời gian, không đưa unsafe provider API sang worker để che spike.

Chưa có SDK để xác minh CustomItemKey canonical accessor/opaque equality, provider random metadata, SQLite store, scheduler retirement; xem `docs/PLATFORM_CONTRACT_GAPS.md`. Provider hot-template reload cần `/upgrader reload` để invalidate cache.

Tiếp theo là **Phase 4 — Transaction**, nhưng chỉ mở side effects sau khi giải quyết ownership/idempotency/durable journal/reconciliation. Ledger, SQL, inventory và external economy không tự có distributed atomicity. Quote, core test và syntax pass không phải chứng nhận plugin production-ready.
