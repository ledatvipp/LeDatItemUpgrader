# Build & verification — 0.6.0-phase06

Baseline source: `LeDatItemUpgrader-phase05-source.zip`. Source thực được giải nén và sửa nối tiếp, không tạo project replacement. Target Paper1.21.4/Java21/PlatformAPI2.10.0 guide.

## 1. Đã thực thi

`./scripts/test-core.sh` compile **toàn bộ core main + tests** bằng OpenJDK21.0.11, `--release21 -Xlint:all -Werror`, rồi chạy sáu assertion suites. Không dependency internet cho core.

| Suite | Nhóm | Assertions | Failures |
|---|---:|---:|---:|
| CoreSelfTest (Phase0–1) |126|3643|0|
| CatalogSelfTest (Phase2) |123|17897|0|
| Phase03SelfTest |182|15730|0|
| Phase04SelfTest |105|9341|0|
| Phase05SelfTest |105|5318|0|
| Phase06SelfTest |88|1196|0|
| **Tổng** |**729**|**53125**|**0**|

XML reports và log thực trong `phase06/`. 13 nhóm mockJDBC Phase4 +10 nhóm mockJDBC output Phase5 đã nằm trong tổng, không phải additional native tests. Phase6 click tests chạy model enum/slot decisions, không Bukkit event mocks. Fixture byte payload không MinecraftNBT.

## 2. Phần Phase6 được kiểm chứng

Context/settings/compiler/reference bounds; immutable lists; full frame+dirty diff+blank lastpage; source index corruption reject; session/view/request identity; next-page old-close/late-response isolation; blank-only recommendation completion; cooldown/idle/pendingdeadline/nanowrap; capacity/disable/concurrent reservation1winner. Facade gọi engines thật: chance/boost/cost, lockedpath nofallback, access/typedcondition trước pagination, invalid target/profile/boost rejection, source excluded, wrongruntime/resource owner reject, snapshot không sửa.

Không có native click automation hay chạy rendering trong server. Native scheduler, PDC-free projection, holder events/cursor guard/close cleanup/ownership-token và command wiring đã viết source + syntax-parse, **chưa type-check hoặc integration**.

## 3. Regression khác đã chạy

- `python3 scripts/test-sqlite-journal.py`: **15 tests**, SQLite thực qua Python, SQLite library version3.46.1.
- `python3 scripts/test-sqlite-outputs.py`: **14 tests**, SQLite thực qua Python.
- `python3 scripts/verify-legacy-journal.py`: **18 history +1 competitor**, v1 bytes/digests giữ nguyên.
- `python3 scripts/verify-resources.py`: **1019 checks/17 YAML**, PyYAML6.0.3, duplicate keys/types/refs/assets/messages/architecture source scans.
- `java scripts/ParseJavaSources.java .`: **162 Java files/0 syntax errors**. Không symbol resolution/type checking.
- `./scripts/run-gui-demo.sh`: fixture theo golden output dưới.

SQLite scripts xuất SQL từ Java classes; không thay database statements bằng SQL test tự viết. Child process exit tests baseline quanh commit vẫn chạy. Đây không phải actual JDBC repository-driver integration, MySQL, Platform database pool, Minecraft process/power-loss, playerdata hoặc economy persistency.

```text
MAIN source=iron x1 value=90; recommended=gold; status=preview
CATALOG page=1/2; targets=[gold, emerald]
CATALOG page=2/2; targets=[diamond]
SELECT diamond: chance=9%; sourceSlot=0
BOOST lucky_shard: chance=14%; reserve emerald=2
READ-ONLY: preview is not escrow, RNG outcome, reward or native GUI/server verification.
```

## 4. Build gate chưa đạt

`phase06/environment.log`: OpenJDK21.0.11; Gradle không được cài; libs chỉ có README, không API JAR. `curl` tới repo.maven.apache.org thất bại DNS(error6). Không có artifact Platform thật trong input source/docs.

**Paper module chưa compile/typecheck/link/testboot.** Không có plugin JAR đã verify. Không thêm giả SDK, bỏ dependency checks hoặc bẻ sang reflection. Các chữ ký Paper native mới được đối chiếu tài liệu official target1.21.4, không thay cho compilation. Platform delayed scheduler thiếu trong guide nên dùng bridge Paper next-tick nhỏ, owner-tracked, công khai trong contract.

## 5. Gate tính năng

GUI source chỉ reference selection; không escrow/reserve native/debit/reward. Các native effect, ledger, durable mailbox/delivery của phases4–5 chưa được cài. Quote đủ tài nguyên chưa là reservation. UPGRADE luôn khóa. packet flag false; không có packet engine, worlddisplays hoặc gacha animation.

Còn thiếu native item/identity/projection roundtrip (customitem/head/leather/potion/etc), exact runtimeconfig/MiniMessage tests, exploit click/drag/cursor/client tests, reopen/foreignmenu/PAPI/provider/reload/retirement under load, durable reconciliation/native effects, JDBCdriver/MySQL/pool. Không đoMSPT/TPS hoặc hiệu quảpacket; không claimFolia. PowerShell script chưa thực thi trênWindows.

## 6. Release reproducibility

`SOURCE_SHA256SUMS.txt` được tạo lại trên source/tài liệu/evidence, loại buildoutput và chínhmanifest. ZIP không chứa JAR/classes/Paper/Platform stubs. Releasecheck artifact riêng ghi SHA256archive, kiểm chứng manifest, chạy lại tests từ ZIP giải nén. Các logsphase5 gốc giữ ở `phase05-original/`; con số trong đó là lịch sử, không kết quả hiện tại.
