-- F18.1: 論理削除されていないPLATFORM会員証は、停止・無効を含め本人ごとに1枚。
-- 既存重複はこの単一atomic ALTERで失敗させ、旧行を修復・削除・復活させない。
ALTER TABLE member_cards
    ADD COLUMN active_platform_user_id BIGINT UNSIGNED
        GENERATED ALWAYS AS (
            CASE WHEN scope_type = 'PLATFORM' AND deleted_at IS NULL THEN user_id ELSE NULL END
        ) VIRTUAL,
    ADD UNIQUE KEY uq_member_cards_active_platform_user_id (active_platform_user_id);
