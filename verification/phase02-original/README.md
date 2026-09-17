# LeDatItemUpgrader — Phase 0–2

**Source tiếp nối Phase 0–1: thêm Upgrade Path, Catalog, Recommendation và selection validation. Chưa có plugin JAR cài server đã xác minh.**

Version source: `0.2.0-phase02`. Ngày bàn giao: 2026-09-16.
Giữ target **Paper 1.21.4 / Java 21 / LeDatPlatform API 2.10.0** theo baseline. Không tự nâng version, không claim Folia hoặc mọi bản `1.21.4+`.

## Trạng thái kiểm chứng

| Hạng mục | Kết quả |
|---|---|
| Core production | Compile thật JDK 21, `--release 21 -Xlint:all -Werror` |
| Regression Phase 0–1 | 126 nhóm, 3.643 assertion, 0 lỗi |
| Catalog/Path/Selection/RequestGate | 123 nhóm mới, 17.897 assertion, 0 lỗi |
| Tổng core | **249 nhóm, 21.540 assertion, 0 lỗi** |
| Value + Catalog CLI demo | Chạy thật, payload giả lập, không phải server |
| YAML resource checks | 276 checks / 8 file, dùng PyYAML, không phải runtime SnakeYAML |
| Java syntax-only | 53 file trong `src/`, 0 syntax error; không resolve type/dependency |
| Paper/LeDatPlatform adapter | **Có source, chưa compile/link/chạy với dependency thật** |
| GUI giao dịch / packet / RNG / cost / delivery | Chưa triển khai, feature flags vẫn khóa false |

`verification/BUILD_REPORT.md` phân biệt bằng chứng đã chạy với acceptance gate còn thiếu. Không lấy syntax/unit-test làm bằng chứng plugin enable được.

## Phase 2 đã thêm

`CatalogDefinitions` compile cấu hình thành index target/path/permission/category bất biến. `CatalogIndex` định giá snapshot template do adapter xác minh, lưu index theo tổng giá/category/provider và lý do loại target. `CatalogService` lọc/sắp xếp/phân trang; `TargetSelectionService` kiểm tra target selection theo player, session, revision, catalog generation, source facts/payload, target fingerprint, expiry và quyền hiện tại.

`PreviewRequestGate` giới hạn request chỉ đọc, có timeout và ticket identity để callback cũ không kết thúc request mới. Đây **không phải** transaction lock hoặc ledger.

Paper source bổ sung `CatalogConfigLoader`, `CatalogCacheService`, `CatalogPreviewService`; nối command, permission, message fallback và cleanup. Cold catalog chỉ có một lượt chuẩn bị tại một thời điểm. Template được capture theo batch ở owner thread, định giá/lập index và query trên worker. Cache được dùng lại khi revision chưa đổi; enable/disable plugin làm mất hiệu lực cache. Không lưu `Player` object trong cache/job.

## Quy tắc gameplay discovery

**Path:** `LOCKED` chỉ cho target liệt kê; `OPEN` ưu tiên target liệt kê rồi thêm catalog đủ điều kiện. Chọn path có priority cao nhất theo source key **trước** permission/condition. Denied path không rơi xuống path thấp hoặc catalog tự do. Hai path enabled trùng source/priority làm reload thất bại.

**Giá:** so sánh tổng source với tổng target; không lấy unit value thay total. Target phải đắt hơn source, khác item key và nằm trong range cấu hình. Path không bypass các kiểm tra này. Ví dụ cầm 10 iron có tổng 900 sẽ không được đổi sang diamond tổng 900.

**Recommendation:** thứ tự target trong path → target priority giảm dần → khoảng cách giá đến `sourceTotal × preferredRatio` → giá → ID. Không random target, không dùng weight, không thay đổi chance. Sort khác: `VALUE_ASC`, `VALUE_DESC`, `ID`.

**Filter:** category/provider/tags/literal search. Tags dùng AND; search theo ID/key/category/tag, không parse MiniMessage/name thành logic và không chạy regex do player cung cấp. Phân trang sau khi lọc quyền, kiểm tra page trước nhân offset.

**Fail closed:** thiếu template/provider/identity/giá hoặc item không an toàn thì target bị loại. Không dùng placeholder material làm reward thay thế. Condition ID chưa có evidence luôn bị từ chối. **PAPI evaluator chưa triển khai ở Phase 2.**

## Chạy backend đã xác minh

Linux/macOS với JDK 21:

```bash
chmod +x scripts/*.sh
./scripts/test-core.sh
./scripts/run-demo.sh
./scripts/run-catalog-demo.sh
```

Windows PowerShell với JDK 21:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/test-core.ps1
```

Script PowerShell đã cập nhật nhưng chưa thực thi trên Windows trong môi trường bàn giao. Test runner là Java độc lập, không phải JUnit/MockBukkit; output có XML để công cụ CI đọc.

Kiểm tra bổ sung:

```bash
python scripts/verify-resources.py  # cần PyYAML; không thay parser thực tế của plugin
java scripts/ParseJavaSources.java .  # chỉ parse syntax Java, không compile Paper
```

Catalog demo đã chạy:

| Source mẫu | Kết quả recommendation |
|---|---|
| Iron ×1, value 90 | Gold 180, Diamond 900; path LOCKED |
| Gold ×1, value 180 | Diamond 900, Diamond Sword 1.800; path OPEN |
| Iron ×10, value 900 | NO_TARGETS; target bằng giá không phải upgrade |

Đây là số của fixture và config mẫu, không phải giá thị trường/Vault/PlayerPoints.

## Build Gradle và dependency gate

Chưa có Gradle Wrapper JAR trong bundle. Baseline dự kiến dùng Gradle 8.14.3; môi trường hiện tại chưa chạy Gradle, Maven download kiểm tra lại vẫn lỗi DNS.

```bash
gradle backendCheck
# Chỉ chạy sau khi có API thật và đối chiếu chữ ký bridge:
gradle clean build -PplatformApiJar=/absolute/path/ledat-platform-api-2.10.0.jar
```

Đường dẫn mặc định API: `libs/ledat-platform-api-2.10.0.jar`. Gate `verifyPlatformApi` từ chối build khi thiếu dependency; không dùng SDK giả, reflection hay shade Platform vào plugin.

Output **dự kiến sau khi build thật thành công**, chưa phải file đã có trong bundle:

```text
paper/build/libs/LeDatItemUpgrader-0.2.0-phase02.jar
```

Không dùng `core/build/libs/*backend-NOT-A-PLUGIN*.jar` như plugin Bukkit. Core và Paper đóng thành một plugin khi build đầy đủ, không phải hai plugin gameplay.

## Command và quyền trong Paper source

| Command | Permission bổ sung sau `ledatitemupgrader.use` |
|---|---|
| `/upgrader`, `/upgrader help` | Không |
| `/upgrader status` | `ledatitemupgrader.admin.status` |
| `/upgrader value` | `ledatitemupgrader.admin.value` |
| `/upgrader inspect` | `ledatitemupgrader.admin.inspect` |
| `/upgrader reload` | `ledatitemupgrader.admin.reload` |
| `/upgrader catalog [page] [category\|all] [recommended\|value_asc\|value_desc\|id]` | `ledatitemupgrader.admin.catalog` |
| `/upgrader recommend` | `ledatitemupgrader.admin.catalog` |
| `/upgrader paths` | `ledatitemupgrader.admin.catalog` |

Mặc định các quyền admin là `op`, gom trong `ledatitemupgrader.admin`. Preview command cần player cầm item tay chính; console hỗ trợ help/status/reload. Command có input validation/tab completion; không query DB để tab-complete.

`/upgrader` vẫn chỉ báo framework, **chưa mở inventory menu**. Catalog ở Phase 2 là backend và command preview, không phải Catalog GUI đã render.

Lần đầu cold cache warm template; source được capture khi cache đã sẵn sàng. Trước khi hiển thị lại kiểm tra source/access/revision. Preview không authorize phí hoặc phát reward.

## Config và tương thích source Phase 0–1

```text
paper/src/main/resources/
├─ plugin.yml
├─ config.yml
├─ messages.yml
├─ menus/upgrader.yml
└─ upgrades/
   ├─ values.yml
   ├─ recipes.yml
   ├─ catalog.yml       # Mới: target + discovery settings
   └─ paths.yml         # Mới: source -> target restrictions
```

Hai file mới có `config-version: 1` riêng và được thêm vào `ensureBundledYaml`. Không đổi schema `config.yml`, `values.yml`, `recipes.yml`, `menus/upgrader.yml`, không đổi storage mode, không overwrite giá do admin đã chỉnh. Message mới lấy bundled fallback khi file cũ thiếu key; `messages-version: 1` vẫn hợp lệ vì thay đổi chỉ bổ sung key.

Decimal luôn đặt trong nháy. Config unknown key, duplicate, material/item key sai, ref thiếu, path mơ hồ hoặc strict MiniMessage sai → reject candidate, giữ runtime cũ nếu có. Template provider không khả dụng là lỗi capability lúc warm-up, không thay đổi file config.

`name` trong target là text cấu hình chuẩn bị GUI; command preview hiện dùng ID/key/value, không parse tên để xác định action.

Hai flag vẫn bắt buộc:

```yaml
features:
  upgrades-enabled: false
  packet-renderer-enabled: false
```

## Giới hạn không được bỏ qua

Chưa có artifact LeDatPlatform API để kiểm chứng bridge. Guide chưa cho canonical accessor của `CustomItemKey`; bridge vẫn dùng opaque equality có self-check. Khi có API thật phải đối chiếu và thay bằng accessor chính thức nếu có. Không suy rarity/stat từ lore. Xem `docs/PLATFORM_CONTRACT_GAPS.md`.

Target snapshot cache giữ payload tối đa 16 MiB; 1 cold build, callback capture tối đa `identity-probe-batch`, 16 preview jobs, 16 query computations đang chờ/chạy, timeout 30 giây. Đây là giới hạn thiết kế, không phải benchmark TPS/MSPT. Query chạy trên detached data. Cold warm-up timeout không tự áp dụng target thiếu.

Reload Upgrader sau khi sửa template của provider; plugin khác tự reload template cùng key có thể không phát Bukkit enable/disable event. Item custom tạo stats ngẫu nhiên được định giá theo snapshot đã capture; transaction tương lai phải pin/revalidate snapshot, không tự regenerate một item có stats khác rồi giữ giá cũ.

Storage nền vẫn `PLATFORM_SHARED`, `initialize-schema: false`; chưa mở SQLite riêng, chưa có transaction/history/pity database. Không có RNG, chance profile, economy debit, booster/protection, transfer/failure, delivery/recovery, inventory input, packet renderer, animation hoặc Resource Pack asset.

## Tiếp tục

`plan.md` cập nhật trạng thái thực tế. `docs/PHASE02.md` mô tả contract cho GUI/transaction phase sau; `docs/TEST_CHECKLIST.md` giữ các release gate chưa chạy. Core Phase 2 có thể phát triển độc lập trong lúc chờ SDK, nhưng **không mở gacha hoặc coi Paper adapter đã production-ready** trước compile/link và staging tests thật.
