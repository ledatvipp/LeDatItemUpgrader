# Checklist kiểm thử và trạng thái

## Phase 9A — bằng chứng hiện tại và gate riêng

- [x] Core47 nhóm/147 assertions mới; tổng954/61.407. Model/futures/clock, không phải server.
- [x] 33 repository tests/183 assertions qua Java → SQLite test-only bridge; không production driver.
- [x] Init/verify 12 bảng/4index, version/data loss/PK/unique/partial index/view/column errors fail closed.
- [x] Hooked bundle completion update statistics; recovery compact pagination/lock comparison; poisoned terminal flag không xóa active.
- [x] Single-flight timeout/reload/close, late SQL error reporting, explicit retry, one maintenance batch/interval.
- [x] Resource1480/22YAML; `'OFF'` là string; defaults không mở live/history/schema/retention.
- [x] Native preflight đã chạy và báo đúng thiếu Gradle/API, exit1; không gọi đó là build thành công.
- [ ] Full Paper/API compile/link với artifact thật và check JAR không chứa dependency bị shade.
- [ ] Production JDBC metadata/transaction semantics, MySQL/InnoDB, pool/autoCommit/platform ownership.
- [ ] Enable/reload/off/verify/init/failure/timeout/disable trên server; startup fail giữ feature unavailable.
- [ ] `/upgrader storage` no-permission, console/player, unknown sender, UUID/cooldown/completion/messages.
- [ ] History menu và PAPI thực sự đóng khi readiness thay đổi, reopen dùng session/cache mới.
- [ ] Maintenance opt-in query index/performance, không xóa active/receipts; rollback và crash ngoài process test.
- [ ] Inventory exploit suite và tất cả native custody/economy/ledger/output/delivery gate chưa hoàn tất.

Các mục lịch sử bên dưới giữ đúng phạm vi của từng phase; report hiện tại là nguồn số liệu tổng.

## Đã chạy tự động trong môi trường bàn giao

- [x] Compile core bằng Java 21, `-Xlint:all -Werror`.
- [x] 954 nhóm core test, 61.407 assertion, không lỗi (Phase0–9A). Các con số trong mục lịch sử bên dưới thuộc từng đợt bàn giao.
- [x] Canonical key validation và giữ case custom ID.
- [x] Defensive copy item facts/payload; hash thay đổi khi payload/key/amount đổi.
- [x] Manual/rule/provider/recipe/rarity resolver order.
- [x] Equal-priority ambiguous rules bị từ chối.
- [x] Decimal bounds, zero/negative/exponent rejection, unit/total separation.
- [x] Unknown custom item không fallback sang Vanilla trong domain model.
- [x] Recipe quantity, cycles, unknown alternatives, depth/node budget.
- [x] Metadata risk/enchant/amount/snapshot limits ở core.
- [x] 1.000 trường hợp tách/gộp stack giữ cùng giá trị.
- [x] Durability modifier đơn điệu qua 101 mức damage.
- [x] Valuation concurrent 256 requests trên 8 threads dùng immutable inputs.
- [x] Atomic RuntimeStore: failed reload, concurrent reload, stale/late commit, disable.
- [x] Menu compiler: width/rows/symbol/action/source uniqueness/model/CMD guards.
- [x] SQL identifier whitelist và idempotent DDL string.
- [x] CLI demo value chạy thật.

Lưu ý: unit tests dùng payload byte giả để test core; không chứng minh full NBT/ItemStack round-trip của Paper.

## Phase 2 đã kiểm thử core tự động

- [x] Target/path limits, refs, duplicate ID/item-quantity/source-priority.
- [x] LOCKED/OPEN, denied priority không fallback, condition missing fail closed.
- [x] Snapshot target missing/mismatch/provider failure/risk/unknown value bị loại.
- [x] Range tổng source/target, boundary exact decimal, cùng key/equal-value bị chặn.
- [x] Metadata modifier định giá target; payload cache ceiling 16 MiB.
- [x] Filter category/provider/tags/search literal, permission trước pagination.
- [x] Overflow page, sorting ties, 128 shuffled config orders.
- [x] 250 catalog ngẫu nhiên đối chiếu oracle độc lập; 256 concurrent queries.
- [x] Selection identity/revision/generation/source facts/bytes/target/expiry/access revoke.
- [x] Request timeout/capacity/quit-rejoin token/late callbacks/disable, nano-time wrap.
- [x] Catalog CLI demo với ba flow; resource crossrefs/message keys bổ sung.
- [x] Java syntax-only của 87 source files (không compile/link Paper).

## Chưa chạy — dependency/build gate

- [ ] Có API JAR/source chính xác và Platform playbook.
- [ ] Gradle resolve Paper/API dependencies thành công.
- [ ] `paper:compileJava` qua với API thật; kiểm tra compiler warnings.
- [ ] Plugin JAR chứa main/descriptor/core; không chứa `vn/ledat/platform/**` hay Paper classes.
- [ ] Jar enable được trên Paper target và Platform thật.
- [ ] Xác minh accessor/equality/canonicalization của CustomItemKey; thay bridge tạm nếu cần.

## Chưa chạy — server integration

- [ ] Enable tạo config/messages/value/menu files không có I/O nặng ở main thread.
- [ ] `help/no-permission/player-only/status/reload` hoạt động theo permissions.
- [ ] Default message generator/fallback đúng với MiniMessage trên runtime thật.
- [ ] Reload sai material/enchant/model/decimal/matrix/reference/text -> giữ config cũ.
- [ ] Missing message key -> bundled fallback, warning không spam.
- [ ] Snapshot Paper đầy đủ metadata, PDC, enchant, durability; không sửa source.
- [ ] Provider Vanilla/MMOItems/ItemsAdder/Oraxen/Nexo hoạt động đúng version server.
- [ ] Thiếu provider/unknown/alias ambiguous -> từ chối, không chuyển thành Material fallback.
- [ ] `/value` và `/inspect` không tiêu hao hoặc phát item, không động economy.
- [ ] Quit rồi reconnect không nhận callback cũ; cooldown/pending cleanup đúng.
- [ ] Reload trong lúc inspect -> stale quote bị bỏ qua.
- [ ] Schema initializer qua Platform SQL SQLite và MySQL nếu bật; version cao hơn bị từ chối.
- [ ] Disable trong load/query/inspection -> không publish runtime mới sau stop.
- [ ] Flush timeout/connection ownership đúng với Platform thực tế.
- [ ] Spam command và large metadata có measurement trên staging, không chỉ unit test.

## Phase 2 còn phải staging

- [ ] `/upgrader catalog/recommend/paths` help, permission, player-only và completion.
- [ ] Cold template capture thực sự chạy owner-thread, workers không gọi Bukkit item API.
- [ ] Source đổi lúc prepare/query -> preview stale; không inventory mutation.
- [ ] Callback bị drop/quá hạn/quit-rejoin không khóa request vĩnh viễn.
- [ ] Một cold build; bounded capture và queue dưới concurrency thực tế.
- [ ] Custom template tạo/identify đúng key, alias/quantity/risk/missing provider fail closed.
- [ ] Provider enable/disable invalidate cache; provider hot-template reload cần reload Upgrader.
- [ ] Reload path mơ hồ/unknown target giữ definitions cũ và generation an toàn.
- [ ] Condition missing PAPI/expansion -> denied; unknown condition ID -> reject candidate reload.
- [ ] Không coi target snapshot random-stat cached là fresh target authorization.

## Chưa có side effects trong bản Phase 0–3; bắt buộc trước khi mở gacha

- [ ] GUI holder/session/cursor/input ownership.
- [ ] Shift/double/drag/number-key/offhand/collect cannot extract preview/filler.
- [ ] Crash-safe reservation/debit/roll/delivery/reconciliation.
- [ ] Repeat-click/reconnect/restart không retry side effect chưa có bằng chứng idempotency.
- [ ] Fee/protection/booster behavior đúng; packet/animation không quyết định RNG.
- [ ] Metadata transfer không copy identity/UUID sai.
- [ ] User data/history/pity save/retention/migration/recovery.
- [ ] Load/concurrency tests, packet traffic và MSPT/TPS measurements có số liệu thực tế.


## Phase 3 đã chạy core / supplemental

- [x] Exact ratio/power/table/curve, monotonic points, formula limits, thresholds.
- [x] 4000 rational-oracle cases và 2000source steps×4formulas.
- [x] +percentage points khác multiplier;256 order shuffles.
- [x] Minceil/maxfloor ticketgrid, probability sample predicate bounds (không draw).
- [x] Profile/path restrictions, permission group priority, boost conflicts và limits.
- [x] Free protection denied trừ opt-in; ON_SUCCESS-only cost không chống free retry.
- [x] Cost grouping, scale, outcome reserve,1000 independent integer oracle cases.
- [x] Source excluded, không dùng offhand/armor/cursor, không duplicate physical slots.
- [x] Missingprovider/matcher khác zero balance, fractional/overflow points denied.
- [x] Typed conditions, missing/unresolved/invalid/error/NE fail closed.
- [x] Quote stale viewer/session/source/revision/catalog/access/TTL.
- [x] Terms đổi yêu cầu reconfirm; terms không đổi không gia hạn quote cũ.
- [x] 256 concurrent pure quote computations.
- [x] Quote CLI thật;506 supplemental checks / 12 YAML; không thay native loader tests.

## Phase 3 chưa chạy — Paper/Platform staging

- [ ] Full compile/link với SDK 2.10.0, PAPI2.11.6 compatibility artifact và Paper 1.21.4.
- [ ] Boot PAPI absent/present, expansion absent/present, unload/reload; không class linkage crash.
- [ ] `/quote /profiles /boosts`: help/permission/player-only/tab/page/cooldown.
- [ ] Missing messages fallback MiniMessage, untrusted text không parse tag.
- [ ] Source/target/provider/config đổi trong async -> stale, không silent reconfirm.
- [ ] Vault/PlayerPoints balance missing/zero/error/negative/precision/overflow.
- [ ] Exact costtemplate vanilla/custom, renamed/enchant/PDC khác bị loại, randomtemplate nofallback.
- [ ] Fee và booster chung resource không consume source; source whole slot excluded.
- [ ] Changed money/item between captures báo stale/insufficient; không đọc inventory trên worker.
- [ ] 5 ms between-call budget với realexpansion; một blockingcall được phát hiện, không gọi đó là timeout interrupt.
- [ ] LowTPS/highmetadata/providerlatency/concurrent requests đo thực tế; giới hạn queue không là TPS proof.
- [ ] Disable/quit/rejoin/reload concurrent: no old callbacks hoặc gate leak.
- [ ] Files mới auto-generate/migrate; unknownrefs giữruntimecũ; no storage schema drift.
- [ ] Không gọi debit/consume/refund/reward trong Phase3 (quan sát actual server integration).


## Phase 4 bổ sung

- [x] Attempt planner, exact fee allocation/source exclusion, pinned terms and expiry: core test.
- [x] Journal intent-before-effect, result-before-delivery, version/digest CAS: core test.
- [x] Known rejection compensation vs UNKNOWN quarantine: core test.
- [x] Durable outcome không re-roll; DRAW_INTENT chưa outcome yêu cầu review: core test.
- [x] Caller cancellation, stop/late callback, bounded admission, duplicate competing engine: core test.
- [x] Codec bounded, immutable plan/state envelope binding, payload checksum: core test.
- [x] JDBC call-contract using mocks; không đồng nghĩa driver test.
- [x] SQL SQLite thật qua Python: rollback/owner lock/CAS/process exit before-after outcome commit.
- [ ] Actual SQLite JDBC driver + MySQL/InnoDB + Platform pool integration.
- [ ] Native source/fee escrow and playerdata crash recovery.
- [ ] Platform Ledger and Vault/PlayerPoints adapters with operation evidence.
- [ ] Prepared output unique per attempt + provider-safe transfer + durable mailbox.
- [ ] Trusted-evidence reconciliation mutation + audit/fencing.
- [ ] GUI native/packet click/drag/cursor/death/quit/restart tests.
- [ ] Full Paper build, server integration and load/MSPT test.


## Phase 5 đã kiểm chứng (2026-09-17)

- [x] Core compile Java 21 `-Xlint:all -Werror`; 105 nhóm mới/5.318 assertion, tổng 641 / 51.929 / 0.
- [x] Policy full-content digest, v2 codec, legacy v1 fixture round-trip/digest giữ nguyên.
- [x] Transfer allow-list/type/caps/conflict/protected data, literal names, damage ratio.
- [x] DESTROY/KEEP/DAMAGE/DOWNGRADE, break/clamp-no-loss-loop, lower-value rejection.
- [x] Synthetic fresh UUID khác catalog, reject cloned ID, pure creation capability.
- [x] PREPARE_OUTPUT trước hold/debit; READY one-time, lost commit ack, cancelled caller, pending no replay.
- [x] 10 mock JDBC output contract cases, gồm rollback fail/autocommit/commit ack/checksum (nằm trong105).
- [x] 14 SQL output tests SQLitePython thực; 15 journal SQL regression.
- [x] Child process exit trước/sau READYcommit, payloaddecodeJava và identityatomicity.
- [x] Resource: 542 checks / 13 YAML, syntax-only: 146 file, không full type checking.
- [ ] Native Paper/Platform/helper/driver compile+staging.
- [ ] MMOItems/ItemsAdder/Oraxen/Nexo fresh identity/stat/socket/transfer/provider capabilities thật.
- [ ] Native source/fee escrow, ledger/economy, durable mailbox/entitlement and delivery.
- [ ] GUI re-quote cho valuechanged, holder/click/cursor/drag/close/quit ownership.
- [ ] Server crash/restart/disable/refund/delivery-no-replay fault injection.
- [ ] SQL MySQL, actual JDBC driver/pool bad-connection policy; metrics/load/MSPT.


## Phase6 — đã chạy core / supplemental

- [x] 88 nhóm core mới/1.196assertions; context/limits/compiled menu refs/entrybindings.
- [x] Session/view/request fence, close/reopen/page/asyncstale, timeout không gia hạn, capacity/stop/nanowrap và32 concurrent claims1winner.
- [x] Clickpolicy enum/rawslot/current/busy/cursor/creative/pre-cancel; drag policy. Chỉ model Java, KHÔNG Bukkit event.
- [x] Dirty-slot diff, completeframe, cleared lastpage, no mutableframe leak.
- [x] GuiPreviewService dùng catalog/quote thật, source exclusion, profile/boost/target/paging/access/condition, invalidowner/runtime, sourceimmutable.
- [x] GUI CLI fixture, no RNG/effects.
- [x] 1019 supplemental asset/source scans17YAML;162Java syntaxparse; SQLite15+14 regressions và legacy18+1.

## Phase6 — chưa chạy native acceptance

- [ ] Full Paper/Platform compile/link, runtimeconfig/MiniMessage/defaultmessages validation.
- [ ] `/upgrader`/menu help/permissions/player-only/creative/spectator/cursor opens.
- [ ] Real client shift/double/number/offhand/drag/collect/drop/creative spam cannot extract preview/filler; pre-cancelled event respected.
- [ ] Source left/right/whole stack/reference slot, empty source, changedsource duringquery and no inventorymutation.
- [ ] Page/sort/category/catalog/permission/boost revocation; actual native binding ID not displayname.
- [ ] Open/close deferred, cancelledopen, reopenednewpage, oldClose/newView, foreignGUI, pendingopen vs sweep.
- [ ] Quit/kick/death/teleport/gamemode/reload/disable callbacks and exactholder cleanup.
- [ ] Visual projection strips PDC/providerIDs/contents/attrs while keeping material/amount/model/CMD; externalantiDupeplugin integration.
- [ ] LargeNBT native serialization/deserialize bounds, cache/queue/backpressure/PAPI/providerlatency/MCMSPT profiling.
- [ ] Texture font/title/itemmodels with packaccepted/refused; unsupportedcosmetic fidelity documented.
- [ ] Actual gacha paths remain absent/locked; zero source/fee/debit/grant side effects.
- [ ] Native escrow/delivery integration and recovery before releasing SOURCE custody/live UPGRADE.


## Phase 7 — đã chạy backend (không native acceptance)

- [x] Core Java21 compile `-Xlint:all -Werror`, regression và Phase7 (809 nhóm/59.706 assertions/0 tổng).
- [x] Timeline boundary, monotonic motion, lag jump, deterministic neutral stop, NONE/QUICK/ROULETTE.
- [x] Không result trước reveal, không RNG/effect trong presentation; committed-reader từ chối foreign/uncommitted/corrupt.
- [x] First render ACK mới bắt đầu hold; late/double ACK, repeated skip, timeout, reopen cùng UUID/incarnation.
- [x] Capacity, concurrent same-player open, bounded round-robin, không catch-up queue, cue throttle, cleanup.
- [x] Matrix/route/role/palette/actions, slot diff, source-free click policy model.
- [x] 15 journal SQL +14 output SQL regression qua SQLite Python; legacy18+1 giữ bytes/digest.
- [x] Resource1175/18YAML; syntax-only177Java files; không tính hai bước này thành Paper build.
- [x] CLI demo dùng selectedWIN + fake monotonic clock, không Minecraft/provider/economy.

## Phase 7 — chưa chạy, cần native staging

- [ ] Full Gradle Paper/API compile, source signature verification và enable/bootstrap thật.
- [ ] Tự generate menus/animation.yml, fallback messages, permission/player-only/help/tab/cooldown/reload.
- [ ] win/loss/none/quick/roulette preview có badge rõ, không changed item/currency và UPGRADE vẫn locked.
- [ ] Tất cả click/shift/number/offhand/double/collect/drag/drop/creative/cursor không lấy cosmetic ra ngoài.
- [ ] Open/close hoãn nexttick, không đóng/reopen foreign/replacement inventory.
- [ ] Quit/kick/death/teleport/mode/reload/disable ở từng phase, callback bị drop/retire không rò session/view/task.
- [ ] Dừng GUI service không dừng animation ticker, và ngược lại; plugin disable dọn cả hai.
- [ ] ItemMeta CMD/itemmodel/Adventure/MiniMessage/sound registry trên Paper1.21.4 thật.
- [ ] Live journal-origin animation wiring chỉ sau native escrow/ledger/economy/delivery gates; closing không ảnh hưởng settlement.
- [ ] Stress/load thật, ghi concurrency/duration/framequeue/packet/MSPT; không suy hiệu năng từ assertion counts.

## Phase 8 — đã kiểm chứng core/SQL bridge, chưa kiểm chứng native

- [x] Pity percentage points/clamp, cap/reset/version, floor/scope, overlap validation, authoritative read error.
- [x] Quote expiry/session/owner và version đổi -> re-confirm; không gia hạn quote cũ.
- [x] Codec v1/v2 golden compatibility; v3 stamp roundtrip và truncated payload reject.
- [x] History state/outcome consistency, owner/filter/keyset/order/page limits, cache/session fencing.
- [x] Java SQL repositories qua test bridge + SQLite thật: atomic claim/CAS, rollback, duplicate completion, lock,
  pity version conflict, corrupted projection, lost COMMIT reply, concurrent claims và child process exit trước/sau terminalCOMMIT.
- [x] Retention không xóa tombstones/stats/pity/journal/unfinished; history-only backfill không replay thống kê.
- [ ] Type-check/build Paper/API thật; enable/config/messages/schema lifecycle trên staging.
- [ ] Driver xerial/MySQL/Platform transaction ownership, migration races, query/pool timeout behavior.
- [ ] Native history permissions/tab/player-only, cursor/shift/drag/double/number/offhand/creative/foreign view.
- [ ] Native read callback sau reload/quit/reopen; PAPI cold/TTL/unregister và source renderer asset/sound.
- [ ] Live writer + pity quote boot, native escrow/economy/ledger/delivery/outcome evidence.
- [ ] Scheduled retention/backfill orchestration khi đã có bounded/admin safety contract.
- [ ] Benchmark concurrency/cache/DB/MSPT trên cấu hình server thật; không dùng loop assertion thay load test.
