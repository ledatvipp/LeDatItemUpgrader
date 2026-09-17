# Build & Verification — Phase 7

Source **`0.7.0-phase07`**, ngày kiểm chứng **2026-09-17**. Baseline thật: `LeDatItemUpgrader-phase06-source.zip` đã giải nén và xác minh source manifest trước sửa. Báo cáo Phase6 giữ trong `phase06-original/BUILD_REPORT.md`, logs cũ trong `phase06/`; các số trong evidence cũ là lịch sử.

## 1. Kết luận kiểm chứng

**Core compile/tests đã chạy thật. Module Paper chưa compile/type-check/link hoặc boot trên server.** Không có plugin JAR đã xác minh trong bundle. Không dùng SDK stub, tải giả API, reflection hoặc tắt dependency verification để làm đẹp báo cáo.

Phase7 là presentation-only. Có timeline/session/committed-journal reader core và source native admin-preview. UPGRADE/live transaction/packets chưa mở. Core outcome reader không tạo giao dịch hoặc delivery receipt.

## 2. Java core — lệnh và số liệu

```bash
./scripts/test-core.sh
```

JDK21.0.11; cả main và test được `javac --release 21 -encoding UTF-8 -Xlint:all -Werror` compile. Gradle không được dùng cho bước này. Script chạy 7 self-contained Java suites, XML/log trong `phase07/`.

| Suite | Nhóm test | Assertion | Failures |
|---|---:|---:|---:|
| Core Phase0–1 | 126 | 3.643 | 0 |
| Catalog Phase2 | 123 | 17.897 | 0 |
| Phase3 | 182 | 15.730 | 0 |
| Phase4 | 105 | 9.341 | 0 |
| Phase5 | 105 | 5.318 | 0 |
| Phase6 | 88 | 1.196 | 0 |
| **Phase7 mới** | **80** | **6.581** | **0** |
| **Tổng** | **809** | **59.706** | **0** |

Phase7 bao gồm preset/range/matrix/route/palette checks; sampling nhiều thời điểm/route/preset; invariant outcome-independent motion; ACK/reveal hold/skip/timeout; incarnation/revision/sequence; fair budget/capacity/concurrent same-player open; model click policy; read-only journal reader; frame diff. Assertion loops không được tính là test độc lập hoặc load benchmark. Tất cả là pure core, không Bukkit inventory events/client.

13 nhóm mock JDBC Phase4 và 10 nhóm mock JDBC Phase5 đã nằm trong số trên. Journal fake của reader test cố ý fail tất cả write methods; nó kiểm tra boundary read-only, không xác minh Platform/JDBC driver. Không có fake Paper/Platform API classes trong bundle.

## 3. Regression SQL, codec, resources và syntax

Lệnh đã chạy trên current source:

```bash
./scripts/run-animation-demo.sh
./scripts/run-gui-demo.sh
python3 scripts/test-sqlite-journal.py
python3 scripts/test-sqlite-outputs.py
python3 scripts/verify-legacy-journal.py
python3 scripts/verify-resources.py
java scripts/ParseJavaSources.java .
```

| Phạm vi | Kết quả | Giới hạn |
|---|---|---|
| Journal SQL | 15 tests / 0 errors | SQLite thực qua Python; không JDBC/MySQL/Platform |
| Output SQL | 14 tests / 0 errors | SQLite thực qua Python; không native item/identity |
| Legacy v1 | 18 history +1 competitor fixtures giữ exact bytes/digests | Không tự authorize reward/recovery legacy |
| Resource bổ sung | 1.175 checks /18 YAML /PyYAML6.0.3 | Không chạy SnakeYAML/Adventure/registry loader thật |
| Java syntax-only | 177 main Java files /0 syntax errors | Không symbol/type resolution hoặc Paper compile |

SQL scripts xuất statements từ Java đã compile, không test bản SQL khác viết riêng. Regression có child-process abrupt exit trước/sau commit, **không phải kill Minecraft server, power-loss, provider persistency hoặc inventory recovery**. Phase7 không thêm schema/migration/database side effects.

Golden output animation demo dùng selected WIN và clock giả lập; chưa mở inventory thật:

```text
ADMIN UI SIMULATION. Selected outcome=WIN, no odds fabricated; no items, money, RNG or rewards.
0ms INTRO marker=0 outcome=HIDDEN cue=START
250ms ACCELERATE marker=0 outcome=HIDDEN cue=NONE
700ms SPIN marker=6 outcome=HIDDEN cue=NONE
1600ms DECELERATE marker=7 outcome=HIDDEN cue=NONE
3300ms LAND marker=0 outcome=HIDDEN cue=PULSE
3550ms REVEAL marker=0 outcome=WIN cue=WIN
4450ms CLOSE FINISHED
Active after completion=0, stale skip=false
```

Lag demo kiểm tra first frame sau20s vẫn REVEAL, skip sau reveal không reset hold, sau hold đóng. Xem exact output `phase07/animation-demo.log` và GUI regression `phase07/gui-regression-demo.log`.

## 4. Môi trường và build gate còn thiếu

`phase07/environment.log` ghi OpenJDK21.0.11, Gradle không có trong PATH, `libs/` không có API JAR, Maven repo request lỗi DNS. SDK guide do user cung cấp tự ghi2.10.0 nhưng chưa có binary/public source hoàn chỉnh. Artifact gốc thiếu là LeDatPlatform API thật, không phải một warning có thể ignore.

Không chạy `:paper:compileJava`, `:paper:build`, testboot hoặc client. Các method Paper1.21.4 mới đã đối chiếu primary docs, xem `docs/SOURCES.md`; docs không thay typecheck với jar. PowerShell scripts có source, chưa chạy trên Windows. Gradle task definitions có source, không claim đã chạy bằng Gradle.

## 5. Ranh giới tính năng và các test còn thiếu

Native `InventoryAnimationService` chỉ admin preview, có explicit badge/selected WIN/LOSS, không chance giả. Không gọi RNG gameplay hoặc transaction effect; UUID kỹ thuật của session/reference có thể được tạo mới. `CommittedAnimationReader` core chỉ read committed outcome, chưa nối native live. Thấy WIN khi SETTLING không bằng đã trả item.

Gui source và fee vẫn reference-only. Native escrow/economy debit/refund/ledger/entitlement/mailbox/delivery chưa bootstrap; buttonUPGRADE khóa, flagsfalse. Không đổi storage, giá hoặc cấu hình cũ. No packet/world display/RP assets.

Cần native tests cho config/messages, ItemMeta/models/sounds, click/drag/creative/shift/number/offhand/cursor/foreigncontainers, lost/delayed callback, lifecycle/scheduler ownership và retirement. Core ACK chỉ mô hình hóa callback render thành công, không chứng minh client đã nhìn thấy đúng packet. Cần item/provider/RNG/ledger/JDBC/MySQL/recovery integration và real load test riêng. Không đo FPS/packet/MSPT/TPS, không claim Folia hoặc production-complete.

## 6. Reproducibility và file provenance

`SOURCE_SHA256SUMS.txt` được tạo lại từ source/config/docs/evidence, không include chính manifest hoặc build outputs. Release ZIP chứa project root, không JAR/classes/SDK stubs. `docs/PHASE07_FILES.md` liệt kê code/config/build/scripts thay đổi so với ZIP Phase6.

Release-check artifact riêng ghi SHA256 ZIP, CRC/manifest count, lệnh và kết quả chạy lại từ chính bản giải nén. Chỉ release-check đó mới xác nhận archive retest; báo cáo này ghi kết quả working source. Không cộng lại archive retest thành thêm nhóm test.
