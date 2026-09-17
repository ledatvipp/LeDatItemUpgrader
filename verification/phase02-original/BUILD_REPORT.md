# Build & Verification — LeDatItemUpgrader Phase 0–2

Ngày bàn giao: 2026-09-16. Source version: `0.2.0-phase02`.
Baseline: `LeDatItemUpgrader-phase01-source.zip`, không thay stack hoặc tạo plugin khác.

## Kết luận

**Core backend Phase 0–2 đã compile và chạy kiểm thử thật. Paper/LeDatPlatform adapter mới có source và syntax parse; chưa compile/type-check/link/chạy server. Không có plugin JAR đã xác minh trong bundle.**

Phase 2 thực thi path/catalog/recommendation/selection bằng core độc lập và bổ sung source bridge/commands. Không mở gacha, không debit currency, không lấy/cho item. Chưa được gọi là production-complete.

## Phạm vi source hiện tại

35 file Java core production, 16 file Java Paper production, 2 self-test harness. `scripts/ParseJavaSources.java` là công cụ syntax-only riêng, không tính vào 53 file trong `src/`.

8 YAML resource: plugin/config/messages/menu/values/recipes/catalog/paths. Build Gradle hai module giữ API dependency gate. Không có fake SDK, reflection fallback hay Platform/Paper được nhúng vào source.

## Kiểm chứng đã chạy lại trên source bàn giao

| Kiểm tra | Kết quả | Bằng chứng |
|---|---|---|
| Core production compile | PASS; Java 21, `--release 21 -encoding UTF-8 -Xlint:all -Werror` | `scripts/test-core.sh` |
| Core test compile | PASS, cùng flags, classpath production vừa compile | `scripts/test-core.sh` |
| Phase 0–1 regression | **126 nhóm / 3.643 assertion / 0 lỗi** | `core-self-test.log`, `core-self-test.xml` |
| Phase 2 catalog/request suite | **123 nhóm / 17.897 assertion / 0 lỗi** | `catalog-self-test.log`, `catalog-self-test.xml` |
| Tổng | **249 nhóm / 21.540 assertion / 0 lỗi** | `all-core-tests.log` |
| Value CLI | PASS; 90, 1.440, 810 theo fixture | `demo.log` |
| Catalog CLI | PASS; iron1 → gold/diamond; gold1 → diamond/sword; iron10 → NO_TARGETS | `catalog-demo.log` |
| Resource check bổ sung | PASS; **276 checks / 8 YAML files**, PyYAML 6.0.3 | `resources-check.log` |
| Java syntax-only | **53 source files / 0 syntax error** | `java-syntax-only.log` |

JDK môi trường: OpenJDK `21.0.11`. XML là định dạng báo cáo của test harness tự chứa, không phải tuyên bố đã dùng JUnit/MockBukkit. Các vòng invariant 250 catalog, 128 shuffled order, 256 concurrent query nằm TRONG test groups, không cộng thêm thành test groups độc lập.

## Những hành vi core đã được kiểm tra

Path LOCKED/OPEN và precedence; không fallback khi path ưu tiên cao denied; dangling/duplicate/ambiguous definitions; verified target probe missing/mismatch/unsafe/value rejection; total-price ratio boundary; target cùng key hoặc bằng giá; filters và access trước pagination; sort ổn định, overflow page; metadata modifiers cho giá đích; snapshot budget 16 MiB.

Selection token player/session/revision/generation/source facts+payload/target fingerprint/expiry và access revoked. Preview request cap/timeout/old callback/quit-rejoin/disable/nano-time wrap. Kiểm tra core có oracle enumeration riêng cho 250 catalog random; không phải benchmark game server.

## Phần chưa kiểm chứng và thiếu dependency

1. Không có `ledat-platform-api-2.10.0.jar` hoặc source module API trong `libs/`. Guide là tài liệu, không thay binary/API signature proof. `PlatformAccess`, opaque CustomItemKey equality và source bridge vẫn phải đối chiếu với SDK thật.
2. Môi trường không có executable Gradle. Dependency probe tới Maven chạy lại trả `curl: (6) Could not resolve host: repo.maven.apache.org`. Xem `environment.log`. Không có log Gradle `BUILD SUCCESSFUL` cho Paper.
3. Syntax parse dùng `JavacTask.parse()` không resolve classes/generics/API signatures, không sinh plugin bytecode. **PASS syntax không có nghĩa Paper compile được.**
4. YAML checker dùng PyYAML và kiểm tra crossrefs/messages bổ sung; chưa chạy `ConfigLoader` với SnakeYAML/Adventure/registry thực.
5. Paper catalog warm-up/command/native snapshot/permission callbacks chưa staging. Chưa chứng minh exact batch cadence, retired-player behavior, provider enable/disable invalidation hay request timeout với runtime Platform thật.
6. Custom provider create/identify, canonical key accessor, MMOItems stats/rarity/random template và full NBT round-trip chưa integration-test. Core fixtures dùng synthetic keys/payloads, không thay việc tạo item native.
7. Script PowerShell đã cập nhật nhưng chưa chạy trên Windows. Gradle tasks, fallback generator và JAR assembly chưa được chạy trong môi trường này.
8. SQL schema repository nền vẫn chưa JDBC/SQLite/MySQL integration test. Không có thay đổi storage mode/dữ liệu trong Phase 2.
9. GUI inventory/packet/source ownership, chance/RNG/cost/protection, transaction/ledger/reconciliation, transfer/failure/reward/history/pity/animation chưa triển khai. Không benchmark TPS/MSPT/packet traffic hoặc tuyên bố exactly-once delivery.

## Release gate

Có SDK thật → kiểm tra bridge/accessor → full Gradle build/link → audit JAR content → staging checklist Phase 0–2 → mới kết luận plugin adapter đạt. Phase 3 core có thể tiếp tục độc lập theo plan nhưng vẫn phải khóa mọi side effect tới khi transaction/GUI safety gate hoàn thành.

Báo cáo Phase 0–1 gốc lưu trong `phase01-original/` để tránh nhầm kết quả cũ với evidence hiện tại.
