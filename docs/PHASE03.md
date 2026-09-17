# Phase 3 — Chance / Profile / Cost / Boost / Quote Contract

Source `0.3.0-phase03`, tiếp nối catalog/value trong Phase2. Tài liệu mô tả **implementation thực tế**, không hứa các feature chưa có. Core compile/test; Paper adapter chưa type-check/link/server test.

## 1. Boundary và file sở hữu

| Package / service | Trách nhiệm |
|---|---|
| `core/chance` | Exact formula, modifier stages, probability threshold, breakdown |
| `core/profile`, `core/boost` | Declarative risk, permission bonus, consumable definitions |
| `core/condition` | Typed comparison trên evidence đã tách khỏi PAPI |
| `core/cost` | Resource/precision/cost theo outcome, kế hoạch allocation snapshot |
| `core/quote` | Cross-validation rules, request, quote, revalidation/reconfirmation |
| `paper/config/UpgradeConfigLoader` | Parse strict 4 YAML vào core definitions |
| `paper/hook/PapiConditionHook` | Optional PAPI String API, owner-thread only |
| `paper/service/AccessSnapshotService` | Permission + satisfied condition set bất biến |
| `paper/service/CostResourceCapture` | Balance và item supply read-only |
| `paper/service/QuotePreviewService` | Command preview async + owner recheck + messages |

Command không tính chance/cost, core không import Bukkit/Paper/PAPI/Platform. Không dùng preview state làm ledger. Không có external debit/refund/reward/roll draw/SQL attempt trong phase này.

## 2. Xác suất chính xác

`ExactFraction` chuẩn hóa tử/mẫu BigInteger bằng gcd. Giá có representation BigDecimal nhưng ratio/formula/modifier tính dạng hữu tỉ để không lệch ngưỡng do làm tròn giữa các stage. Target total phải lớn hơn source total; giá phải dương và qua bounds.

`RATIO`: p = ratio × formulaMultiplier. `POWER`: p = ratio^integerExponent × multiplier, exponent1..8. `TABLE`: chọn ngưỡng ratio lớn nhất <= input; điểm đầu0, ratio tăng nghiêm ngặt, percent không giảm. `CURVE`: nội suy tuyến tính giữa hai điểm bao quanh, cần endpoint0 và1, percent không giảm. Point maximum64. Không dùng floating point exponent hoặc thư viện script.

Pipeline tỷ lệ (p trong [0,1] trước clamp theo policy):

```text
p0 = formula(sourceTotal / targetTotal)
p1 = p0 × profileMultiplier
p2 = p1 × product(permissionMultipliers)
p3 = p2 + sum(permissionPercentagePoints) / 100
p4 = p3 × product(boostMultipliers)
p5 = p4 + sum(boostPercentagePoints) / 100
p6 = clamp(p5, minPercent / 100, maxPercent / 100)
```

Việc permission points bị nhân bởi boost multiplier là explicit. Không sắp xếp cộng/nhân theo thứ tự click. IDs từng stage unique; tổng boost selections tối đa8 và permission groups tối đa16.

Probability cuối có denominator cố định1e9. Ngưỡng dưới được ceil lên ticket grid, ngưỡng trên floor xuống. p6 floor thành ticket, rồi giữ trong interval ticket đã validate. Bounds không chứa ticket hợp lệ bị reject; không lén nới max. Granularity = `0.0000001` điểm phần trăm. Breakdown decimals có thể làm tròn để giải thích; `winningTickets` là threshold duy nhất cho sampler Phase4.

`Probability.succeeds(sample)` chỉ là predicate test sample `[0,1e9)`. Phase3 không lấy sample. Phase4 phải sample uniform bounded ở server, giữ sample/outcome cùng journal, không suy ngược kết quả từ animation hoặc client timing. UUID quote/session chỉ là identifier.

Default min0.1%,max90%,ratio0.9; đây là config plugin, không phải claim đã clone mọi rule của mod gốc.

## 3. Profile, permission và restriction

Profile: enabled, formula ID, chanceMultiplier, feeMultiplier, failure terms, costs, permission, conditions. Default profile phải tồn tại/enabled. `PathProfileRule` restrict allowed/default theo path đã chọn từ Phase2; không bypass locked catalog. Default bị denied không tự chọn profile rẻ hơn/ít restriction hơn.

Permission bonus: group + priority + permission + conditions; trong mỗi group lấy priority lớn nhất đã granted. Hai enabled entry cùng group/priority bị reject. Groups khác stack. Không cần direct LuckPerms dependency vì snapshot đọc Bukkit permission. Đây không phải LuckPerms context write integration.

Boost: group xung đột, allowedProfiles, chanceMultiplier/PP, protection, costs, access. Không silently bỏ boost bị thiếu tiền/item rồi quote chance mới; vẫn cho thấy terms của request nhưng resource assessment báo thiếu. Multiple protection hoặc protection trên profile đã KEEP bị reject để tránh thu nhầm scroll. No-op boost bị reject.

Bounds theo code: profiles<=64,boosts<=128,bonuses<=128,formulas<=32,conditions<=32; ID 1..64 `[a-z0-9][a-z0-9_.-]*` giữ tương thích target/path Phase2. Custom item IDs giữ case qua ItemKey, không dùng rule-ID validator để lowercase item provider IDs.

`DESTROY`/`KEEP` mới là terms quote, chưa effect handler. Không cấu hình DAMAGE/DOWNGRADE/LOSE_PERCENT_VALUE như thể đã hoạt động. Các mode này cùng provider-safe metadata transfer thuộc Phase5.

## 4. Cost planning — không có side effect

Resources: currency `vault`/`playerpoints`, hoặc item key canonical. Mỗi CostEntry có amount dương và consumeWhen. Zero/negative/overflow/raw precision sai reject. Vault policy scale2,cap1e12; PlayerPoints integral <=2^31-1; item integral <=65536. Currency policy này cố định của quote engine, không phải mô tả mọi economy provider.

Profile feeMultiplier chỉ áp profile costs. Boost costs giữ nguyên. Gộp theo resource + outcome timing trước làm tròn CEILING tới precision resource. Ví dụ hai fee0.01 nhân0.5 thành tổng0.01, không làm tròn từng line thành0.02. Item3 nhân0.5 thành2; raw fractional item/PlayerPoints không được nhận.

Mỗi cost line giữ a=ON_ATTEMPT,s=ON_SUCCESS,f=ON_FAILURE:

```text
reserve = a + max(s,f)
success consumption = a+s
failure consumption = a+f
```

Không cộng cả hai nhánh outcome. Tổng reserve cũng phải qua bounds, không chỉ từng entry. Bảo hộ ON_FAILURE vẫn cần có nguồn trả trước khi biết kết quả, nhưng chỉ tiêu ở nhánh thua.

`CostPlan.hasFailureLoss()` kiểm tra a+f dương. KEEP không loss khi thất bại bị chặn, trừ opt-in `allow-free-protection`. ON_SUCCESS-only không chặn retry miễn phí. Có fee dương không đồng nghĩa balance kinh tế đã cân bằng: admin vẫn phải tính expected value, quyền bonus, token farm và thương mại server.

## 5. Resource evidence và source exclusion

`ResourceSnapshot` gồm viewer UUID, optional provider balances, verified item matcher keys, per-slot supplies+fingerprints và excluded slots. Defensive-copy maps/lists/sets. Missing provider khác balance0. Balance âm/sai dạng/PlayerPoints fractional hoặc overflow bị từ chối ở adapter, chuyển capability unavailable, không quote miễn phí.

Một physical slot chỉ được đưa vào snapshot một lần và supply chỉ thuộc một verified key. Storage slot0..35; không offhand40, armor hoặc cursor. Toàn bộ source slot bị loại, kể cả số item dư của cùng stack. Phase3 chưa hỗ trợ split source stack để trả phí; đó phải là ownership protocol explicit ở phase sau.

`ResourceAssessor` kiểm tra tất cả resources, sắp slot tăng dần để lập allocation ổn định, không phát partial allocations nếu có issue. Hai cost/profile/boost trùng key được gộp trước nên không đếm đôi cùng stack. `AVAILABLE_PREVIEW` không giữ item, không khóa inventory, không gọi withdraw.

Paper exact matcher: create template1 → identify canonical candidate qua bridge hiện có → risk/payload check → `isSimilar` + identity khớp actual supply. Đồ renamed/enchanted/PDC khác template không dùng trả phí dù cùng Material. Provider random UUID/stat có thể làm match thất bại; future matcher adapter phải explicit và tested, không fallback Material/display name.

Budget32 resource keys/capture,1 MiB tổng serialized evidence,36 storage slots. Không scan người chơi khác. Provider API create/identity/balance có thể block; chỉ budget số lượng không chứng minh giới hạn wall-clock của một call. Cần staging và diagnostics với provider thật.

## 6. Quote và revalidation

`UpgradeQuoteService.quote` kiểm tra cùng catalog/rules instance, resource owner, source slot đã exclude, target selection đủ điều kiện, path/profile/access, booster conflict, cost limit và protection. Sau đó tạo chance + cost terms và resource assessment.

Quote chứa request và Phase2 selection: viewer/session/revision/catalog generation/source fingerprint+facts/target fingerprint/expiry. Terms còn có profile ID, boost IDs, permission-bonus IDs, probability, failure mode và cost plan. Không giữ Player/ItemStack native trong core.

`revalidate`:

```text
viewer/session/expiry -> Phase2 selection validity
-> quote hiện tại với access/resource evidence mới
-> terms khác: RECONFIRM_REQUIRED + replacement
-> terms như cũ: giữ quoteId/expiry cũ, refresh resource evidence
-> thiếu: INSUFFICIENT_RESOURCES / RESOURCES_UNAVAILABLE
-> đủ: VALID_PREVIEW
```

Ngay cả khi bonusID thay đổi mà effectivechance vẫn bị cùng maxclamp, terms hiện tại vẫn coi đó là thay đổi cần reconfirm; đây là cách bảo thủ. Expired/stale source/config/catalog không được silently đưa quote mới để thanh toán. GUI tương lai phải hiển thị replacement và yêu cầu confirm rõ, không gọi retry tự động cho người dùng.

Command source hiện chỉ render text, không lưu quote như authorization có thể redeem. Trước render, nó đọc lại source/access/resources và generation. Bất cứ snapshot khác thì báo stale; không tính lại math trên main để che thay đổi.

## 7. PAPI conditions

`ConditionDefinition` chỉ cho một placeholder `%expansion_identifier%`, tối đa128 chars, không relational, script, regex hoặc expression. Expected literals tối đa256, không control/percent. NUMBER so sánh plain decimal có dấu, tối đa18 integral/8 fractional; no exponent, NaN, locale separators/colors. TEXT case-sensitive exact; BOOLEAN chỉ true/false. TEXT/BOOLEAN chỉ EQ/NE.

Resolve status missing/unresolved/error/budget/invalid không đi qua comparison kể cả NE. Output có `%` coi unresolved theo strict policy. Raw placeholder text không parse MiniMessage, không log, không cache vào quote.

`PapiConditionHook`: optional direct String API theo docs chính thức; Platform guide chưa có entrypoint resolve neutral cho arbitrary incoming PAPI. Source thêm compileOnly PAPI2.11.6 compatibility baseline + softdepend, không coi là phiên bản mới nhất. Không tạo expansion duplicate `%ledat_...%`. Missing PAPI trả unavailable set, các feature không dùng condition vẫn quote được.

Max32 defined conditions, capture chỉ những ID được reference, de-duplicate placeholder calls; 5 ms budget GIỮA calls. Một expansion block trong một call không bị ngắt. Vì API được chạy đúng owner thread, không được chuyển sang unsafe worker hoặc hứa tránh mọi lag. Provider exceptions được warning throttle; raw result không log.

## 8. Lifecycle/config/integration

`ConfigLoader` đọc tất cả 4 file mới trong candidate cũ, crossvalidate refs với paths/catalog rồi atomically publish runtime. Failed candidate không thay live quote semantics. Đổi cấu hình nhưng reuse revision cũ bị cấm ở lifecycle; selection vừa dùng revision vừa catalog generation.

Unknown condition reference ở Phase2 từng fail closed khi thiếu evidence; Phase3 nay reject candidate ngay để admin thấy key chưa định nghĩa. Không đổi prices/main storage/messages schema; file mới version1; missing messages fallback.

Quote flow: owner-thread cache-ready/capture → Platform async detached quote → Platform player scheduler recheck/render. Jobs16,compute16,timeout 30s. Quit/disable dọn request; ticket identity ngăn callback cũ kết thúc request mới. Catalog cache dùng chung, không build duplicate index riêng cho quote.

Chưa full compile Link API: `economy().provider(id).balance(player)` dùng shape có trong guide; check actual API artifact trước release. Bridge identity equality tạm giữ từ Phase1; source guide không cho canonical accessor. Chưa native PAPI/economy/customitem tests. Không transaction table mới, chưa isolated SQLite, không Folia claim.

## 9. Test evidence và negative space

Phase3:182 nhóm/15.730 assertion. Có 4000 ratio cases đối chiếu BigInteger oracle độc lập,2000 source steps qua4formulas (8000 monotonic checks),256 modifier shuffles,1000 reservation oracle cases,256 concurrent pure quotes. Các vòng nằm TRONG test groups, không phải test server/TPS.

Regression Phase0–2 giữ249nhóm/21.540assertion. Tổng431/37.270,0 failure. Quote CLI với synthetic snapshot chạy thật. 506 resource checks là PyYAML supplementary;87 Java source syntax parse không typecheckPaper.

## 10. Handoff Phase4 bắt buộc

Tách read-only quote khỏi attempt state. Phải claim durable attempt/ledger trước side effect, lock đúng viewer/session/source ownership, giữ actual item payload và cost reservation allocation; kiểm tra fingerprint/item/native data ngay ở owner critical section. Pin quote/settings/outcome/failure plan cùng transaction để reload không đổi giao dịch đang chạy.

Tiêu tiền/item và SQL journal không có chung ACID. Crash giữa external effect và journal ack có thể không biết đã làm chưa. **Không auto-reward/auto-refund mọi unfinished row.** Adapter có idempotency/bằng chứng mới được replay; ambiguous phải `RECONCILIATION_REQUIRED`. Ledger claim không chứng minh đã debit, balance-after không tự chứng minh giao dịch của mình khi có plugins khác.

Sampler server uniform ticket → durable predetermined outcome → animation chỉ reveal. Skip/disconnect/reload không reroll. Storage không sẵn sàng phải fail closed trước side effects. Delivery/mailbox cũng cần handle crash gap. Chưa được mở real SOURCE input GUI cho tới ownership/close/quit/cursor/recovery tests có và đã chạy.
