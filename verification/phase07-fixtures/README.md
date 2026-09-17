# Legacy v2 golden fixture — actual Phase07 baseline

Baseline: `LeDatItemUpgrader-phase07-source.zip`.
SHA-256: `47155473cb5954ed41b1c524a792277e588158ffce8e6363e34683a7d618dc5c`.

Trong Phase8, ZIP Phase7 thật được giải nén vào thư mục độc lập và toàn bộ core main được compile bằng JDK21.
`core/src/main/java/vn/ledat/itemupgrader/demo/LegacyOutputContract.java` của bản Phase8 được compile **chống core
Phase7 cũ**, không chống implementation đã sửa. Helper xuất 4 pinned output-policy v2 plans và126 record/state
hash pairs, lưu thành `output-v2-hashes.txt` (130 dòng). Current helper phải xuất đúng cùng bytes/hash theo
`scripts/verify-legacy-output-v2.py`. Đây là fixture serialization, không chứng minh native NBT/migration.

V1 fixtures từ Phase4 tiếp tục được kiểm tra riêng; không sửa golden hashes để làm test pass.
