# Phase 2 — Contract Path/Catalog/Recommendation

## Các file implementation chính

| Path | Trách nhiệm |
|---|---|
| `core/.../catalog/CatalogDefinitions.java` | Cross-reference validation; structural path selection; immutable config indexes |
| `core/.../catalog/CatalogIndex.java` | Value engine dùng verified target snapshot; indexes giá/category/provider, rejected diagnostics |
| `core/.../catalog/CatalogService.java` | Lọc quyền/range/path, sort, recommendation và pagination |
| `core/.../catalog/TargetSelectionService.java` | Selection token gắn identity/revision/generation/fingerprint/expiry |
| `core/.../runtime/PreviewRequestGate.java` | Giới hạn request preview, timeout và bỏ callback cũ |
| `paper/.../config/CatalogConfigLoader.java` | Parse hai YAML mới, validate keys/refs, collect custom identity candidates |
| `paper/.../service/CatalogCacheService.java` | Cold template capture theo batch → worker tạo CatalogIndex |
| `paper/.../service/CatalogPreviewService.java` | Snapshot source/access → worker query → owner recheck/render |

Dấu `...` lần lượt là package `vn/ledat/itemupgrader` và `vn/ledat/itemupgrader/paper` dưới `src/main/java/`.

## Định nghĩa authority

`TargetDefinition` chỉ là template ID do admin cho phép. `TargetProbe` do adapter cấp snapshot thực tế đã nhận diện; core kiểm tra lại key, amount, metadata safety và value. Snapshot giả trong CLI/test chỉ là fixture, không được đưa vào bridge production để thay target thật.

Source snapshot có total value từ engine Phase 1. Target price cũng qua cùng engine và cùng config revision, không khai báo giá riêng trong catalog. Damage/enchant/modifier trên template có thể đổi giá đích.

Catalog có `generation` UUID ngoài config revision: provider enable/disable hoặc rebuild cache cùng revision vẫn làm selection token cũ hết hiệu lực.

## Precedence và deny semantics

1. Tìm path enabled khớp exact source `ItemKey`, chọn priority cao nhất.
2. Nếu permission/condition của path đó thiếu: deny. Không thử path thấp hơn.
3. Nếu không có path và `allow-unpathed: false`: deny.
4. Chọn candidate có template ready, tổng giá nằm trong min/max, khác source key và giá lớn hơn source total.
5. Check allowed source, target permission, mọi target condition; LOCKED chỉ cho target IDs trong path.
6. Áp filter/query; sort rồi phân trang. Preview không tiêu hao input hoặc authorize transaction.

Disabled path không còn là restriction. Admin tắt path cần hiểu source có thể trở về catalog tự do khi `allow-unpathed: true`.

Path ID/target ID/category/tag là lowercase identifiers; custom provider item ID giữ case. Cùng item key + quantity không được khai báo nhiều target ID để tránh alias với access policy khác nhau. Khác quantity được định nghĩa riêng nhưng vẫn cấm source và target cùng key trong Phase 2.

## Recommendation và phép toán

Range: `sourceTotal × minimumRatio <= targetTotal <= sourceTotal × maximumRatio`, đồng thời `targetTotal > sourceTotal`. So sánh BigDecimal trực tiếp, không chia/làm tròn để quyết định eligibility.

Order RECOMMENDED:
`explicitTargetRank ASC -> target.priority DESC -> abs(targetTotal - sourceTotal * preferredRatio) ASC -> targetTotal ASC -> target.id ASC`.

Không có weight hoặc RNG trong index. `preferredRatio: '2'` không phải 2% hoặc success chance. Ratio command chỉ là display làm tròn 6 chữ số; không làm authority.

## Selection token

`select()` yêu cầu exact target ID đang eligible, UUID session hợp lệ, lifetime > 0 và <= 5 phút. Token giữ player/session/revision/generation/source fingerprint+facts/target fingerprint/expiresAt.

`validate()` trả `VALID_PREVIEW` hoặc lý do stale/denied. Không có SQL, debit hay reward trong class này. Token chỉ dùng in-memory; không có signature/HMAC, không gửi cho client như authorization token. Player không được tự tạo/sửa token từ packet.

Trước transaction Phase 4 vẫn phải kiểm tra actual input ownership, actual target snapshot/provider, chance/fee mới, condition, boosts, ledger/reservation; không gọi `validate()` xong là phát item. Quoted target stats ngẫu nhiên cần pin chính snapshot hoặc invalidate/reconfirm, không thay bằng item mới có giá khác.

## Paper source lifecycle

Config parse async → atomic runtime revision chứa definitions. Cold command request → warm identity theo batch → capture target theo batch trên player owner thread → copy detached probe map → worker build index → owner kiểm tra job/revision → cache → capture source/access → worker query → owner kiểm tra lại source/facts/permissions/cache/revision → messages.

Không giữ Player ngoài callback. `runOnlinePlayer` có thể bỏ callback khi owner/player retired; `PlayerQuitEvent`, request timeout và build timeout dọn state. Async hop giữa capture batches tránh recursive inline dispatch; không claim một batch tương đương đúng một tick vì chưa có scheduler runtime test.

Quyền runtime được chụp bằng các node cấu hình. Condition evidence của Paper Phase 2 luôn rỗng; nonempty condition locks feature. Không giả rằng PAPI đang được evaluate.

## Resource Pack/GUI boundaries

GUI Phase 6 gọi `browse/recommend/select` và giữ selection trong `GuiSession`. Button mapping dùng target ID, không dùng tên/lore/page index làm business identity. Page context/session vẫn phải kiểm tra riêng khi click; token không thay GUI holder validation.

`menus/upgrader.yml` giữ matrix/model/glyph-ready text. Phase này không mở inventory, không nhận source item, không update packet. Không cần thay Catalog/Value engines khi có renderer khác.
