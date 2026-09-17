# Build & Verification Report — LeDatItemUpgrader

Ngày bàn giao: 2026-09-16. Source version: `0.1.0-phase01`.

## Kết luận

**Core backend đã compile và test thực tế. Module plugin Paper/LeDatPlatform chưa được compile hoặc chạy trên server. Không có plugin JAR đã xác minh trong bản bàn giao.**

Đây là source triển khai đầu tiên của Phase 0–1, không phải đã hoàn thành tất cả acceptance gate của hai phase.

## Phạm vi source

- Core: 20 file Java production, dependency-free.
- Paper: 13 file Java production, phụ thuộc Paper API và LeDatPlatform thật.
- Self-test harness: 1 file Java; không dùng JUnit/MockBukkit hoặc SDK stub.
- Resource YAML: 6 file, gồm `plugin.yml`, `config.yml`, `messages.yml`, value/recipe/menu definitions.
- Có `build.gradle`, `settings.gradle`, docs, plan, script kiểm thử offline và script kiểm tra resource bổ sung.

## Kiểm chứng đã chạy

| Kiểm chứng | Lệnh/cách chạy | Kết quả |
|---|---|---|
| Core production compile | `javac --release 21 -encoding UTF-8 -Xlint:all -Werror` | PASS |
| Core test compile | Cùng compiler flags; classpath là core classes vừa compile | PASS |
| Core unit/invariant suite | `./scripts/test-core.sh` | 126 nhóm, 3.643 assertion, 0 failure |
| Read-only value demo | `./scripts/run-demo.sh` | PASS; Iron ×1 = 90; ×16 = 1.440; Iron block = 810 |
| Supplemental resource check | `python scripts/verify-resources.py` | PASS; 151 checks / 6 YAML files; PyYAML 6.0.3 |

JUnit-style XML: `core-self-test.xml`. Log console: `core-self-test.log`.
Các invariant 1.000 stack cases, 101 durability points, 256 concurrent valuations nằm trong 126 nhóm, không được cộng thêm thành 1.483 test nhóm khác.

## Chưa kiểm chứng / giới hạn

1. **Thiếu artifact API thật:** chỉ có `LEDATPLATFORM_API.md`; không có JAR/source `ledat-platform-api` để đối chiếu public class/package/signature. `PlatformAccess` có source dựa trên guide, nhưng compatibility chưa xác minh.
2. **Không chạy Gradle plugin build:** môi trường không có Gradle distribution/cache. Truy cập `downloads.gradle.org`, `repo.maven.apache.org`, `repo.papermc.io` qua container trả lỗi DNS `Could not resolve host`. Không có log `BUILD SUCCESSFUL` của module Paper.
3. **Không có live Paper/server tests:** chưa kiểm tra command registration, MiniMessage runtime, inventory metadata thực, CustomItemKey equality/accessors, provider adapters, scheduler retirement, async JDBC hoặc disable flush trên runtime thật.
4. Resource checker dùng **PyYAML**, không phải parser SnakeYAML/Adventure dùng ở module Paper. Nó chỉ bổ sung kiểm tra syntax/duplicate key/field consistency; không chứng minh runtime config loader đã chạy.
5. Snapshot unit tests dùng byte payload giả lập. Chưa round-trip item Paper/MMOItems thật. SHA-256 là fingerprint payload, không phải anti-dupe registry hoặc guarantee canonicalization xuyên version.
6. Schema tests chỉ xác minh SQL identifier/DDL shape. `SchemaRepository` compile qua JDK nhưng chưa chạy JDBC/SQLite/MySQL integration test.
7. Không benchmark TPS/MSPT/packet traffic. Các giới hạn queue/graph/config là thiết kế và unit invariant, không phải số liệu load trên server.
8. GUI mới có compiler/blueprint. Chưa có event handlers giữ cursor/input, chưa có inventory renderer, chưa có packet layer.
9. Gacha/cost/reward/ledger/recovery/history/pity chưa triển khai. Feature flags khóa false; source hiện không tiêu hao item hoặc currency.

## Những điều source cố ý không giả định

- Không giả lập API package `vn.ledat.platform.*` để tạo build xanh.
- Không đoán `CustomItemKey.asString()/getId()` hoặc parse `toString()`; bridge probe đang chờ accessor thật.
- Không đoán chữ ký `sqliteStore(...)`; shared Platform SQL ở bản nền là optional và được công khai trong config/docs.
- Không lấy rarity/stat bằng cách đọc lore; không copy metadata provider sang target ở bản chưa có transfer engine.
- Không coi ledger là distributed transaction giữa SQL, inventory và external economy.

## Release gate trước khi cài server

Nhận API artifact và playbook; kiểm tra bridge; build bằng dependency thật; audit JAR content; chạy checklist staging trong `docs/TEST_CHECKLIST.md`. Chỉ sau đó mới kết luận plugin adapter của Phase 0–1 đã hoạt động.

Gói source này không có `.jar` plugin phát hành. Mọi JAR backend tự build từ module core đều **không phải Bukkit plugin**.
