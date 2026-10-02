-- =====================================================================
-- F09.19.4 リリース前提: DASHBOARD_TILE / IN_FEED のアフィリエイト初期シード
-- =====================================================================
-- 背景（設計 §8.2 リリース前提条件・番人 AC-4.6）:
--   現行ハードコードの広告タイル（WidgetAmazonAd/RakutenAd）は設定ゼロでも常に表示されていたが、
--   SpotlightSlot 化後は affiliate_configs に有効行が無いと HOUSE 候補ゼロ時に fallback まで
--   空になり広告枠が全消滅する。そのため DASHBOARD_TILE / IN_FEED の AMAZON・RAKUTEN 行を
--   is_active=TRUE で投入する（tag_id は SYSTEM_ADMIN が affiliate-settings 画面で上書きする前提の
--   プレースホルダ）。
--
-- 冪等性: affiliate_configs には UNIQUE 制約が無い（V9.001 / V9.057 裏取り済み・INDEX のみ）ため、
--   (provider, placement, deleted_at IS NULL) の非存在チェックで多重投入を防ぐ。
--   既に管理者が同一 (provider, placement) 行を作成済みの場合も二重にならない。
-- =====================================================================

-- ---------------------------------------------------------------------
-- §10.6 幽霊 placement 値の是正（幂等・該当行が無ければ 0 行 UPDATE の no-op）。
--   affiliate-settings 画面が過去に幽霊値 INLINE_CONTENT / BELOW_HEADER を選択肢として
--   提示していたため、それらで作成された行が存在しうる。実 AdPlacement enum へ寄せる。
--   （worktree からは本番 DB を照会できないため、該当ゼロでも無害な冪等 UPDATE として同梱する）
-- ---------------------------------------------------------------------
UPDATE affiliate_configs SET placement = 'IN_FEED'       WHERE placement = 'INLINE_CONTENT';
UPDATE affiliate_configs SET placement = 'BANNER_HEADER' WHERE placement = 'BELOW_HEADER';

-- ---------------------------------------------------------------------
-- DASHBOARD_TILE
-- ---------------------------------------------------------------------
INSERT INTO affiliate_configs (provider, tag_id, placement, description, is_active, display_priority)
SELECT 'AMAZON', 'PLACEHOLDER_AMAZON_TAG', 'DASHBOARD_TILE',
       'F09.19.4 初期シード（tag_id は管理画面で上書き）', TRUE, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM affiliate_configs
    WHERE provider = 'AMAZON' AND placement = 'DASHBOARD_TILE' AND deleted_at IS NULL
);

INSERT INTO affiliate_configs (provider, tag_id, placement, description, is_active, display_priority)
SELECT 'RAKUTEN', 'PLACEHOLDER_RAKUTEN_TAG', 'DASHBOARD_TILE',
       'F09.19.4 初期シード（tag_id は管理画面で上書き）', TRUE, 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM affiliate_configs
    WHERE provider = 'RAKUTEN' AND placement = 'DASHBOARD_TILE' AND deleted_at IS NULL
);

-- ---------------------------------------------------------------------
-- IN_FEED
-- ---------------------------------------------------------------------
INSERT INTO affiliate_configs (provider, tag_id, placement, description, is_active, display_priority)
SELECT 'AMAZON', 'PLACEHOLDER_AMAZON_TAG', 'IN_FEED',
       'F09.19.4 初期シード（tag_id は管理画面で上書き）', TRUE, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM affiliate_configs
    WHERE provider = 'AMAZON' AND placement = 'IN_FEED' AND deleted_at IS NULL
);

INSERT INTO affiliate_configs (provider, tag_id, placement, description, is_active, display_priority)
SELECT 'RAKUTEN', 'PLACEHOLDER_RAKUTEN_TAG', 'IN_FEED',
       'F09.19.4 初期シード（tag_id は管理画面で上書き）', TRUE, 1
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM affiliate_configs
    WHERE provider = 'RAKUTEN' AND placement = 'IN_FEED' AND deleted_at IS NULL
);
