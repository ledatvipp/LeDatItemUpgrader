# Phase 7 — Inventory roulette / reveal

Version `0.7.0-phase07`, tiếp tục nguyên source Phase 0–6. Phạm vi của phase này là **presentation**, không mở tài chính hoặc custody.

## 1. Trạng thái bàn giao

Core Java đã compile và kiểm thử. Native Inventory animation, command, loader, listener và bootstrap đã được viết source; **chưa compile/type-check/link hoặc chạy trên Paper/LeDatPlatform thật**. Thiếu API artifact, Gradle và khả năng tải dependency trong môi trường này. Không có SDK giả hoặc plugin JAR đã xác minh.

Có hai loại request tách biệt:

| Origin | Nguồn kết quả | Wiring hiện tại |
|---|---|---|
| `ADMIN_PREVIEW` | Admin chọn rõ WIN hoặc LOSS | Command trình diễn native ở mức source, có nhãn thử giao diện |
| `JOURNAL_OUTCOME` | `CommittedAnimationReader` đọc attempt đã lưu outcome | Read-only core seam, chưa nối vào native/live bootstrap |

Nút UPGRADE của Phase 6 vẫn khóa. Preview không đọc túi làm source, không định giá, không lấy RNG, không trừ tiền/item và không phát thưởng. Không dùng preview để tạo transaction giả. `upgrades-enabled` và `packet-renderer-enabled` vẫn false.

## 2. Boundary với transaction

`CommittedAnimationReader` chỉ gọi `AsyncTransactionJournal.find(attemptId)`. Nó kiểm tra attempt ID, player UUID, `TransactionMachine.validate(...)` và state `OUTCOME_COMMITTED`, `SETTLING` hoặc `COMPLETED`. Absent/wrong viewer/uncommitted/reconciliation trả empty; dữ liệu sai hoặc I/O lỗi không được chuyển thành LOSS.

Request giữ viewer/reference/version/probability/outcome, không giữ raw RNG sample, item payload, phí hoặc output candidates. Factory live không public; chỉ journal reader tạo request live qua package boundary. Đây là ràng buộc kiến trúc cho code tin cậy của plugin, **không phải chữ ký chống giả mạo đối với mã Java có toàn quyền trong server**.

`SETTLING` có outcome chưa đồng nghĩa đã trả vật phẩm. WIN trong animation chỉ mô tả outcome, không xác nhận `DELIVER_OUTPUT` thành công. Nội dung config nhắc rõ điều này. Animation không là delivery receipt; close/skip/disconnect không được gọi settlement hoặc recovery. Việc actual delivery có tiếp tục khi đóng animation hay không thuộc Transaction Engine và adapter, không do phase này chứng minh.

## 3. Timeline và hình thức roulette

`AnimationTimeline` thuần Java, nhận preset + route length + request bất biến. Sampling O(1) theo elapsed monotonic time, không loop các tick đã bỏ lỡ:

```text
INTRO → ACCELERATE → SPIN → DECELERATE → LAND → REVEAL → DONE
```

Acceleration tuyến tính theo velocity; deceleration dùng velocity `(1-u)^2`. Tích phân chuyển động ra ordinal, giới hạn để không vượt endpoint. Cùng thời gian không đổi vị trí, clock lùi không làm session quay ngược; arithmetic nanoTime hỗ trợ wrap trong khoảng thời gian bounded.

| Preset mẫu | Intro | Accel | Cruise | Decel | Land | Hold | Tổng danh nghĩa |
|---|---:|---:|---:|---:|---:|---:|---:|
| roulette | 250 | 450 | 900 | 1.700 | 250 | 900 | 4.450 ms |
| quick | 100 | 100 | 150 | 400 | 150 | 700 | 1.600 ms |
| none | 0 | 0 | 0 | 0 | 0 | 700 | 700 ms |

Tổng danh nghĩa không là cam kết wall-time cố định: callback/scheduling có thể đến muộn; hold bắt đầu sau acknowledged reveal. Không thay tick lag bằng phát bù hàng trăm packet/sound.

Route mặc định trung tính, marker vàng chạy qua các ô xám. Stop index lấy từ reference UUID theo phép ánh xạ deterministic, **không phụ thuộc WIN/LOSS hoặc chance**, không gọi random. Đến reveal marker/status mới đổi sang WIN/LOSS. Không vẽ số ô xanh thành xác suất giả, không near-miss có chủ ý. Đây là roulette inventory dạng ô, chưa là vòng texture mượt như screenshot hoặc canvas tự do.

## 4. Session, acknowledgement và bỏ qua

`AnimationSessionStore` giữ UUID và dữ liệu hiển thị; không giữ Player/Inventory/transaction port. Token gắn viewer, session, reference, config revision và incarnation tăng dần. Dùng lại session UUID sau khi close vẫn không làm callback cũ hợp lệ.

Mỗi session tối đa một frame đang chờ owner callback. Update gắn sequence và exact frame. Native controller render thành công mới acknowledge; ack lặp không phát cue lần hai. Timeout được kiểm tra cả khi callback định apply, không chỉ đợi lượt sweep.

`skip` chỉ bỏ đoạn animation còn lại: invalidate frame cũ, đưa cùng outcome tới reveal. Lần skip tiếp theo không reset hold, không đổi outcome. Skip không cứu một callback đã timeout. Escape/Close dọn presentation; không hoàn phí và không cấp reward.

Nếu server lag qua toàn timeline trước lần render đầu, session vẫn phải render/ack REVEAL một lần rồi giữ theo `reveal-hold-ms`. Frame phát ra nhưng bị bỏ hoặc player không còn online không được tính là đã nhìn thấy reveal.

## 5. Scheduling và backpressure

Native service có một repeating ticker và queue round-robin cho active animation. Mặc định tối đa 64 sessions, visits-per-tick 16, callback timeout 5.000 ms. Core/config cho phép capacity 1–128 và visits 1–32 (không vượt capacity).

Budget là số session kiểm tra trong mỗi lượt, **không phải 20 FPS mỗi viewer**. Với 64 session và budget 16, một vòng cần tối thiểu bốn server ticks; frame khác nhau thực sự gửi còn tùy marker, phase và owner callback. Đây là thiết kế giới hạn công việc, chưa là benchmark MSPT/TPS hoặc packet count.

Mỗi phiên chỉ một outstanding callback, không queue catch-up. Revision sweep chạy khi runtime revision đổi, không quét toàn bộ animation mỗi tick chỉ để so revision. Pending open có expiry và capacity, được dọn trong ticker; chỉ quét map pending nhỏ đã bounded, không quét server players/entities.

Render cache tối đa 9 palette × 7 phase/view, không key theo elapsed hoặc tick. Dựng slot map từ template cache rồi `SlotDiff.changed`; native `setItem` chỉ cho slot đổi. Một bước marker trong cùng phase thường đổi hai slot; phase/result có thể đổi thêm. Không claim full computation dirty-only hoặc đếm packet thực tế.

`PaperGuiScheduler` nay giữ riêng task handles. Đóng service GUI không được `cancelTasks(owner)` làm tắt ticker animation và ngược lại. Bridge native next-tick vẫn cần vì guide Platform chưa cung cấp chữ ký delayed; không đoán API. Đây là Paper owner-thread model, **không claim Folia**.

## 6. Native inventory / lifecycle

`AnimationHolder` có owner token và exact inventory identity. Listener cancel mọi click/drag trong GUI sở hữu trước khi xử lý, kể cả bottom inventory. Chỉ top left-click SKIP hoặc CLOSE được dispatch sau kiểm tra cursor rỗng, session/revision, loại click và permission. Shift, number-key, swap-offhand, double/collect, drop, creative, unknown và drag không được dùng để lấy cosmetic item.

Open/skip/close action từ click hoãn sang tick kế tiếp. Callback open kiểm tra inventory đang xem vẫn là inventory lúc request, tránh mở đè một menu mới. Render và cleanup kiểm tra exact holder/token; close event cũ không dọn view mới. Quit/kick/death/teleport/gamemode/reload/disable dọn state; timeout không biến thành giao dịch lỗi.

Inventory icon tạo mới từ config. Không deserialize source/target, copy PDC/provider UUID hoặc phát `ItemStack` từ renderer như reward. Item model/CMD chỉ là cosmetic; texture RP phải khớp slot grid. Cache template được clone trước khi đưa vào inventory để Bukkit không sửa template đã cache.

Native scheduler/event/container behavior ở mục này **chưa được staging/client-test**. Nếu một callback đóng bị platform bỏ vì retirement/disable, không có fallback chạm player trên thread sai. Cần acceptance test tình huống này; không gọi nó là bảo đảm mọi GUI luôn đóng ngay khi timeout.

## 7. Command và cấu hình

Source command:

```text
/upgrader animation win [preset]
/upgrader animation loss [preset]
```

Permission: `ledatitemupgrader.use` + `ledatitemupgrader.admin.animation` (mặc định op). Chỉ online/alive Survival/Adventure, cursor trống. Console bị player-only. Prefix, help, lỗi, nhãn preview, phase, kết quả và sounds đều lấy từ resources. Command không dùng target/source/chance tùy người chơi để giả live result.

File mới: `paper/src/main/resources/menus/animation.yml`. Không overwrite config/menu/messages cũ; ensure file mới khi thiếu. Missing message dùng bundled fallback. Reload parse/validate toàn bộ rồi atomic swap; lỗi giữ runtime cũ, thành công invalidate session revision cũ.

```yaml
menu:
  matrix:
    - '#########'
    - '##TTTTT##'
    - '##T#I#T##'
    - '##TTTTT##'
    - '####B####'
    - '##K###X##'
  track-order: [11, 12, 13, 14, 15, 24, 33, 32, 31, 30, 29, 20]
```

Đây là trích đoạn, không thay file mẫu đầy đủ. T=TRACK, I=STATUS, B=BADGE, K=SKIP, X=CLOSE. Route gồm mỗi TRACK đúng một lần; không auto đoán theo vị trí sort. Size 9–54, 9 cột; STATUS/BADGE/SKIP/CLOSE mỗi role đúng một ô. Palette đầy đủ, không khai báo action giao dịch trong icon. Presets/ranges/material/sound/MiniMessage được validate bằng native loader source và snapshot registry; PyYAML checker chỉ là bổ sung.

`preview-enabled: true` chỉ bật admin demo, không bật nâng cấp thật. Menu/cue/model/text dễ thay; packet renderer, resource pack assets và world display không thuộc phase này. Không migration SQL hoặc thay storage mode.

## 8. Kiểm chứng thực thi và giới hạn

Phase 7: **80 nhóm / 6.581 assertion / 0 lỗi**; toàn bộ core **809 / 59.706 / 0**. Bao gồm boundaries/monotonicity/lag/skip/ack/timeout, config/route/palette, incarnation/foreign close, round-robin capacity, same-player concurrent opens, click policy model, read-only journal seam và dirty diff. Các vòng quét timestamps/assertions không được gọi là test TPS/server load.

SQL regression 15 journal + 14 output SQLite qua Python; legacy 18+1 fixtures không đổi bytes/digest. Không thêm JDBC integration claim. Supplemental 1.175 checks/18 YAML và syntax-only 177 Java files đều qua. Paper module chưa type-check, chưa chạy ItemMeta/MiniMessage/Platform scheduler thực.

CLI demo `./scripts/run-animation-demo.sh` chỉ in timeline/sound cue bằng clock giả lập, không mở Minecraft GUI. PowerShell tương đương có source nhưng chưa chạy Windows. Xem `verification/BUILD_REPORT.md` và `TEST_CHECKLIST.md`.

## 9. Điều kiện nối live sau này

Chỉ nối journal-origin khi Paper/API đã build/test và native transaction effects/delivery đã được kiểm chứng. Không nối button UPGRADE sang admin preview để giả plugin đã hoạt động. Query journal trên async port, dùng request display tối thiểu, owner render recheck view/revision. Không block transaction completion chờ animation ack và không dùng WIN cue như reward receipt.

History/Pity/Admin có thể tiếp tục backend ở Phase 8; vẫn phải giữ tách biệt cosmetic state và economic state. Mọi lời hứa exactly-once/native anti-dupe/server performance cần bằng chứng riêng.
