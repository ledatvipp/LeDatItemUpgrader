# Build & Verification — LeDatItemUpgrader Phase 0–3

Bàn giao: 2026-09-16. Version source: `0.3.0-phase03`.
Baseline thật: `LeDatItemUpgrader-phase02-source.zip`; giữ core Phase 0–2 và thực thi Phase 3, không tạo plugin thay thế.

## Kết luận

**Core Phase 0–3 compile và kiểm thử thực tế thành công. Paper adapter mới có source + syntax parse, chưa compile/type-check/link/chạy server với Paper, LeDatPlatform hoặc PAPI thật. Không có plugin JAR đã xác minh trong bundle.**

Phase 3 tạo chance/profile/cost/boost quote chỉ đọc. Chưa reserve/debit/consume/refund/reward, chưa random outcome hoặc inventory input/packet. Hai feature flags vẫn false; storage mode không đổi. Không kết luận toàn plugin production-ready từ kết quả core.

## Phạm vi source

63 file Java core production, 21 file Java Paper production, 3 self-test harness = **87 file Java trong `src/`**. `scripts/ParseJavaSources.java` là công cụ kiểm tra riêng, không nằm trong số này. 12 YAML resource. Gradle multi-module đóng core/Paper thành một plugin khi đủ release gates, không thành hai plugin.

Dependency-free core không import Bukkit/Paper/Platform/PAPI. Không có stub SDK, reflection fallback hoặc NMS. PAPI compile-only 2.11.6 là compatibility baseline nguồn, không là tuyên bố latest hoặc runtime compatibility đã test.

## Các lệnh đã chạy trên source cuối

```bash
./scripts/test-core.sh
./scripts/run-demo.sh
./scripts/run-catalog-demo.sh
./scripts/run-quote-demo.sh
python scripts/verify-resources.py
java scripts/ParseJavaSources.java .
git diff --check
```

| Kiểm tra | Kết quả | Evidence trong thư mục này |
|---|---|---|
| Core production compile | PASS Java 21; `--release 21 -encoding UTF-8 -Xlint:all -Werror` | script và `all-core-tests.log` |
| Core test compile | PASS với classpath core vừa build | script và `all-core-tests.log` |
| Phase 0–1 regression | **126 nhóm / 3.643 assertion / 0 lỗi** | `core-self-test.log`, `.xml` |
| Phase 2 regression | **123 nhóm / 17.897 assertion / 0 lỗi** | `catalog-self-test.log`, `.xml` |
| Phase 3 quote/chance/cost/condition | **182 nhóm / 15.730 assertion / 0 lỗi** | `phase03-self-test.log`, `.xml` |
| **Tổng core** | **431 nhóm / 37.270 assertion / 0 lỗi** | `all-core-tests.log` |
| Value CLI | PASS | `demo.log` |
| Catalog CLI | PASS | `catalog-demo.log` |
| Quote CLI | PASS, chỉ detached fixture | `quote-demo.log` |
| Supplemental resource consistency | **506 checks / 12 YAML**, PyYAML 6.0.3 | `resources-check.log` |
| Java syntax-only | **87 files / 0 syntax error** | `java-syntax-only.log` |
| Whitespace diff review | PASS `git diff --check` trước đóng gói | lệnh thực thi; `.git` không phát hành |

Môi trường JDK: OpenJDK `21.0.11`. Test harness tự chứa và XML report không có nghĩa dùng JUnit/MockBukkit. Các test dùng byte payload và provider evidence giả lập; không chứng minh native ItemStack/PDC/NBT round-trip.

## Hành vi được kiểm thử ở core Phase 3

Exact RATIO/POWER/TABLE/CURVE; invalid inputs/limits/point ordering; nguồn tổng thấp hơn target; minceil/maxfloor trên ticket grid; sample predicate boundary; không sample outcome trong quote. Phân biệt percentage-points và multiplier, modifier order độc lập, duplicate adjustment IDs bị reject.

Profile/path default restrictions; enabled/condition/permission gates; highest-priority bonus mỗi group, duplicate priority reject; selected boost/profile mismatch, exclusive-group/protection conflict; free KEEP chống retry miễn phí, cost ON_SUCCESS-only không vượt guard.

Currency/item positive amounts/precision/overflow; grouped rounding; profile multiplier không nhân boost cost; reserve=a+max(s,f); deterministic item allocation; excluded source slot; physical-slot uniqueness; same resource cost+boost không đếm hai lần; no partial allocation khi còn thiếu resource; missing provider/matcher phân biệt balance0.

Typed NUMBER/TEXT/BOOLEAN, unresolved/missing/error/invalid value fail closed kể cả NE. Quote viewer/session/source/revision/catalog/expiry/access, missing resources, terms changed requires reconfirm; unchanged validation giữ nguyên ID/expiry, không extend TTL cũ.

Invariant Phase 3: 4.000 ratio cases đối chiếu BigInteger oracle độc lập; 2.000 source steps × 4 formulas đơn điệu; 256 adjustment shuffles; 1.000 reservation cases theo integer oracle; 256 concurrent pure quotes. Những vòng này nằm **trong 182 test groups**, không được cộng thành thêm test groups/server tests/load benchmark.

## Demo thực thi

Source iron value90 → diamond value900: standard9%; safe5,4% với Vault43,75; risky11,7% với Vault7,5; VIP-only9,45%; lucky +5PP14%; VIP+lucky14,45%. Protection chỉ mô tả KEEP khi fail, paper reserve1/success0/failure1. Safe không balance provider evidence trả UNAVAILABLE chứ không miễn phí.

Outcome-cost fixture10 ON_ATTEMPT +20 ON_SUCCESS +30 ON_FAILURE → reserve40, tiêu khi success30/failure40. Không thực hiện reservation/debit nào.

Fixture bật bonus/boost để minh họa. YAML thật giữ boosters và permission bonus disabled mặc định. Values/economy trong demo là dữ liệu giả, không phải balance/transaction từ server.

## Những gì chưa được xác minh

1. **Chưa có actual `ledat-platform-api-2.10.0.jar` hoặc source module.** Bridge cần đối chiếu canonical CustomItemKey accessor, equality, public class packages/signatures, economy provider generic types và scheduler/database lifecycle. Guide không chứng minh binary compatibility.
2. **Gradle chưa cài, DNS fetch distribution thất bại:** `curl: (6) Could not resolve host: services.gradle.org`. Xem `environment.log`, `dependency-access.log`. Không có full Gradle `BUILD SUCCESSFUL`; Gradle tasks/default-message generator/JAR assembly chưa chạy.
3. `JavacTask.parse()` chỉ syntax, không type-check Paper/generics/external API, không tạo bytecode plugin. 87 files pass không nghĩa Paper module compile được.
4. Resource verifier dùng PyYAML bổ sung, không chạy Java `ConfigLoader` với SnakeYAML/Adventure/native registry. Không thay config migration/reload/message fallback tests trên runtime.
5. PAPI adapter có optional source nhưng chưa native load/link/absent-plugin/unresolved/expansion/thread test. 5ms giữa calls không thể interrupt một expansion blocking bên trong; không có latency guarantee.
6. Read-only Vault/PlayerPoints balance và exact item-cost matching chưa provider integration. Random UUID/stat token có thể không match exact template. Unknown capability fail closed, không generic material fallback.
7. Quote/catalog native capture/thread/callback/quit/rejoin/stale/reload/provider disable tests chưa staging. `PreviewRequestGate` tests chỉ chứng minh core gate, không chứng minh Platform dispatch contract dưới tải.
8. SQL schema metadata nền chưa JDBC integration test; isolated SQLite, user/history/pity/transaction tables chưa có. Không đổi storage hoặc ghi attempt data trong Phase 3.
9. No live inventory GUI/cursor/shift/drag/hotbar protection tests vì GUI chưa triển khai. Chưa có packet renderer, visual gacha, RNG outcome persistence, transfer/failure mutations hoặc delivery/recovery.
10. Windows PowerShell runner đã sửa nhưng chưa chạy trên Windows. Không TPS/MSPT/packet benchmark, không tuyên bố exactly-once qua inventory/economy/SQL.

## Release gate và Phase tiếp theo

API artifact thật → review bridge/signature/capabilities → full Gradle build → kiểm tra nội dung JAR → Paper/Platform/PAPI/economy/custom-item staging và checklist. Không dùng SDK giả để vượt gate.

Phase 4 phải tách quote khỏi durable attempt, claim idempotency trước effect, có input ownership/cost journal, sampling + persisted outcome, và reconciliation cho crash gap không phân biệt được. **Không auto-refund/reward mọi unfinished row**; ledger không biến external provider thành distributed ACID.

Báo cáo/log cũ chỉ là lịch sử tại `phase01-original/`, `phase02-original/`. Các file ở root `verification/` là lần chạy hiện tại. Manifest SHA256 của toàn bộ bundle sẽ được tạo sau khi docs/evidence hoàn tất; source ZIP không chứa `.git`, build class/JAR hoặc dependency/font files.
