# LeDatPlatform — Contract gaps trước build/runtime verification

Tài liệu được cung cấp: `LEDATPLATFORM_API.md`, tự ghi API version 2.10.0. Chưa có artifact `.jar`, source module `ledat-platform-api` hoặc file `LEDAT_PLATFORM_AI_PLAYBOOK.md` mà guide nhắc tới.

| Điểm | Guide có gì | Source hiện tại / việc phải xác minh |
|---|---|---|
| Provider/API class | Tên `LeDatPlatformProvider`, `LeDatPlatformApi`; namespace API | Bridge dùng các tên đó trong root API package; cần artifact kiểm tra package/signature, không coi như đã link thành công |
| Scheduling | `scoped(owner).runAsync`, `runOnlinePlayer` | Dùng những entrypoint này; source Paper-only, chưa test retirement/cancellation với runtime thật |
| Item identity | `identify(ItemStack) -> Optional<CustomItemKey>` | Không cho accessor canonical. Bridge dùng opaque equality self-check + configured-template index; cần thay bằng accessor thật sau khi kiểm tra artifact |
| Item create | `create(key, amount)` | Giới hạn probe theo batch. Cần test casing/canonical aliases với từng provider thật |
| Shared SQL | `query(label, connection -> ...)`, `tableName(...)` | Bridge sử dụng shape trong guide, giả định future generic cần compile xác nhận |
| SQLite store riêng | Chỉ nhắc `sqliteStore(...)` | Không đoán overload. Source dùng `PLATFORM_SHARED` optional, chưa thực hiện isolated SQLite |
| GuiService | Button-based examples | Chưa chứng minh hỗ trợ transactional source input/cursor ownership. Phase GUI phải xem API thật trước khi chọn Platform GUI hay native holder mỏng |
| Custom stats / rarity / transfer | Có identify/create, không có contract stat/rarity/sockets | Core mở extension point; Paper snapshot không parse lore hoặc invent provider stats |
| Ledger | Claim/success/fail examples | Chưa gọi trong Phase 0–3. Ledger không tự làm atomic external economy/inventory |
| Locale/messages | Có LanguageService locale | Phase 0 dùng `messages.yml` theo requirement; không tự đăng ký locale khi chưa yêu cầu. Có thể dùng locale adapter ở phase sau |

Không có SDK stub trong `src/`, không có reflection fallback, không shade core/API Platform. `verifyPlatformApi` chỉ là dependency gate; API thật và runtime tests mới là compatibility proof.

Việc cần nhận: JAR API hoặc source module chính xác của Platform đang chạy trên server. Playbook nếu có cần được đối chiếu trước khi mở user-data/transaction feature.


## Phase 2 bổ sung điểm cần staging

`CatalogCacheService` chỉ dùng API create/identify/scheduler đã có shape trong guide; chưa có binary để verify. Warm-up giả định opaque key equality ổn định như bridge Phase 1; cần thay/accessor canonical chính thức khi biết API thật.

Provider tạo item có stats random: cache giữ snapshot được định giá, không chứng minh fresh create sau này có cùng stats. Template reload không phát Bukkit enable/disable cần `/upgrader reload` để invalidate. Capture call có budget theo callback, chưa đo cadence per tick trên Platform.

Cold build/request bị drop callback phải kiểm tra timeout/quit/disable với scheduler thật. Unit `PreviewRequestGate` không phải test behavior của Platform scheduler.


## Phase 3 bổ sung

| Điểm | Hành vi source hiện tại / còn thiếu |
|---|---|
| Economy balance | `api.economy().provider(id).map(p -> p.balance(player))` theo shape guide; cần SDK compile và native Vault/PlayerPoints tests. Chưa withdraw/deposit. Provider lỗi/negative/fractional points -> unavailable. |
| Currency precision | Vault scale2 là chính sách quote của plugin; actual provider scale/rounding/error/idempotency cần adapter contract trước Phase 4. Không assume every provider dùng cùng precision. |
| PAPI input conditions | Guide có placeholder router register output nhưng không có neutral arbitrary resolve signature. Dùng optional direct String API theo docs chính thức, compileOnly PAPI 2.11.6/softdepend, không đăng ký expansion `ledat` khác. Linkage/plugin absent/expansion missing/thread budget còn phải staging. |
| Consumable template | Exact identity+isSimilar, không match Material-only. Template random stats/unique UUID có thể không match token thật; cần capability/provider-safe matcher có test riêng, không suy từ lore. |
| Quote lifecycle | Core pure quote/revalidate có test. Capture/readbalance/papi/changed hand/runtime callback có source nhưng chưa test actual Platform scheduler retirement/rejection. |
| SQL/ledger | Không thêm transaction schema/reservation/debit trong Phase3. Không coi current CostPlan/quoteId là SQL lock hay đã claim ledger. |

Không có shared economy API atomic hold/reserve signature trong guide đang có. Phase 4 không được bịa phương thức reserve/commit/refund; dùng adapter capability rõ và xử lý ambiguous side effect bằng reconciliation khi không thể chứng minh replay an toàn.


## Phase 4 bổ sung

`PlatformTransactionJournal` chỉ dùng public shape của `PlatformAccess.query(...)` đã có từ baseline. Chưa bootstrap bridge hoặc initialize bảng mới. Connection callback phải cho phép auto-commit → transaction → commit/rollback → restore; provider phải loại connection lỗi khi rollback/restore fail. Chưa xác minh JDBC/pool thực tế.

`EffectPort` chưa có implementation production cho Platform Ledger, Vault/PlayerPoints debit/refund, source escrow, fee ownership hoặc durable delivery. Không map `claim.duplicate()` thẳng thành APPLIED mà thiếu op/plan evidence. Không dùng `audit` thay idempotency guard. API guide không chứng minh player inventory/provider write được atomically commit cùng SQL.

Không tự tạo thread pool/raw scheduler để bypass thiếu chữ ký managed executor. Engine nhận Executor; tích hợp phải dùng owner-managed Platform executor thật, lifecycle drain và retirement-safe player scheduling.


## Phase 5 bổ sung

`PlatformOutputStore` dùng cùng public SQL shape với bridge Phase 4; source mới chưa bootstrap/link. Cần connection contract cho atomic READY+identity inserts và pool discard khi rollback/commit restore lỗi. Không đoán `sqliteStore` overload, không tạo raw pool khác.

`PaperVanillaOutputItems` là helper Paper owner-thread, có caller-provided identity verifier bắt buộc. Không phải implementation async ItemMutationPort sống trên server; phải dùng managed player scheduler với retirement result và no-global-fallback theo API thật. Paper helper không claim Folia. Native Material/create/serialize/PDC/enchant code chưa compile/test.

Custom provider guide chỉ identify/create, chưa có safe immutable metadata transfer/pure creation/template attestation/unique ID contract. Core `MutationCapabilities` không được tự điền tất cả true chỉ vì identify có kết quả. Các tên field stat/UUID phải do adapter version-specific được kiểm chứng cung cấp. PDC mutation Vanilla helper hiện không hỗ trợ.

PreparedDeliveryPort chỉ contract; READY output chưa là ledger entitlement hoặc Minecraft inventory delivery. Mailbox/escrow/economy/ledger native adapter còn phải tích hợp. Strict reprice rejection không phải UI re-quote implementation.
