-- F22.1 市（Market）: confirmable_notifications に source_id 列を追加（01_data_model §5）
--
-- 背景（乖離A の根治）:
--   source_type は V13.006 で VARCHAR(40) として既に存在する（ENUM ではない）。
--   そのため MARKET_FINALIZE 追加に source_type の ALTER は不要（乖離B）。
--   一方 source_id 列は DB に存在しなかったため、発生元レコードID（source_type='MARKET_FINALIZE'
--   のとき recruitment_listings.id）を保持できるよう本マイグレーションで追加する。
--
-- ポリモルフィック参照（発生元ドメインをまたぐ）のためクロスドメイン FK は張らない（CLAUDE.md 原則 1）。

ALTER TABLE confirmable_notifications
    ADD COLUMN source_id BIGINT UNSIGNED NULL
        COMMENT '発生元レコードID（ポリモルフィック参照・FKなし）。MARKET_FINALIZE では recruitment_listings.id'
        AFTER source_type;

-- 発生元種別×IDでの逆引き（市の最終認証通知の重複発火防止・状態照会用）
CREATE INDEX idx_cn_source ON confirmable_notifications (source_type, source_id);
