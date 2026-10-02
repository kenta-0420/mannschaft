-- Issue #2657: payment_items.type ENUM に TERM を追加する（期別課金の根治）
-- 事象: PaymentItemType.java（Java）には ANNUAL_FEE/MONTHLY_FEE/ITEM/DONATION/TERM の5値があるが、
--   DB の ENUM は V80.20260610194300 で term_starts_on/term_ends_on 列を追加した際に
--   ENUM 値自体の追加を怠っていたため 'ANNUAL_FEE','MONTHLY_FEE','ITEM','DONATION' の4値のまま。
--   TERM 型の項目を作成しようとすると JpaSystemException（Data truncated for column 'type'）で必ず失敗する。
-- 対応: MODIFY COLUMN で既存4値の順序・NOT NULL 制約を維持したまま TERM を追加する（列定義は
--   SHOW CREATE TABLE payment_items で実測確認済み。DEFAULT なし・COMMENT なし）。
--   既存行のデータは変更しない（値の再割当ては発生しないため MODIFY で安全に拡張できる）。
ALTER TABLE payment_items
    MODIFY COLUMN type ENUM('ANNUAL_FEE','MONTHLY_FEE','ITEM','DONATION','TERM') NOT NULL;
