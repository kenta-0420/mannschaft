-- F17.2 Wave2 ③お祭りの参加レイヤー: 実況投稿の紐付け（village_festival_live_posts）
-- ACTIVE 期間中に村人が付けた「この祭の実況」タグ投稿を、村ドメイン側の中間テーブルへ記録する。
-- timeline 本体は無改造とし、紐付けを村ドメインに閉じる（案B採用・設計書 §5.4）。
--
-- 主キー方針: 原則6の例外＝複合自然キー（festival_id + timeline_post_id）。
--   独立発番の代理キーを必要とせず「参照2本の組」が一意で足りるため（設計書 §5.4・§13.1）。
-- 原則7 適用外: 全テナント横断ドメイン
--
-- 設計判断（docs/features/F17.2_village_events_activation.md §5.4）:
--   - festival_id は同一 village ドメインだが原則1に従い FK 非付与
--   - timeline_post_id は別ドメイン timeline の BIGINT PK・ID参照のみ・FK非付与（原則1）

CREATE TABLE village_festival_live_posts (
    festival_id             BINARY(16)      NOT NULL                                COMMENT '→ village_festivals.id（同一ドメイン・FK非付与）',
    timeline_post_id        BIGINT UNSIGNED NOT NULL                                COMMENT '→ timeline_posts.id（別ドメイン timeline・ID参照のみ・FK非付与・原則1）',
    created_at              DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (festival_id, timeline_post_id),
    KEY idx_vflp_festival (festival_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='お祭りの実況投稿の紐付け（F17.2 Wave2 ③・自然キー例外）';
