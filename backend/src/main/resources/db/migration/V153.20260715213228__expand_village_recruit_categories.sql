-- =====================================================================
-- F17.1 P1: 村ごと募集カテゴリマスタ — Stage 1 / Expand（後方互換・DDL 追加のみ）
-- =====================================================================
-- 設計書: docs/features/F17.1_village_headman_console_and_recruit_categories.md
--          §4.2（新テーブル）/ §4.3（category_id 追加）/ §5.4（Expand/Migrate/Contract）
--          §5.5（プリセット seed 方針）/ §5.6（match_date NOT NULL 緩和）
--
-- 目的: 募集カテゴリの値域がスポーツ語彙（PRACTICE_MATCH/REFEREE/VENUE/OTHER）に固着している
--       課題を根治するため、村ごとに値域そのものを定義できるマスタへ全面移行する（設計書 §5.3 案B）。
--
-- 本マイグレーションは Expand 段であり、後方互換を保つ:
--   - category_id は NULL 可のまま（NOT NULL 化は P6 / Contract）
--   - 旧 category（enum 文字列）列は温存（DROP は P6 / Contract）
--   → この時点では BE/FE は従来どおり category を読み書きし続ける。
--
-- ---------------------------------------------------------------------
-- 【最重要】論理削除の罠（設計書 §5.4）— deleted_at で絞ってはならない箇所がある
-- ---------------------------------------------------------------------
-- P6 は category_id を NOT NULL 化するが、NOT NULL 制約は「論理削除済みの行」にも適用される
-- （DB は deleted_at を知らない）。したがって seed 導出・バックフィル・番人のいずれかを
-- deleted_at IS NULL で絞ると、論理削除済みの募集・村の category_id が NULL のまま残り、
-- P6 の ALTER TABLE ... MODIFY ... NOT NULL が確実に失敗する（時限爆弾）。
-- 併せて、CASCADE FK は物理削除でしか発火しないため、論理削除済みの村の募集行は生き残っている。
--
--   | 処理                          | deleted_at の扱い                                  |
--   |-------------------------------|---------------------------------------------------|
--   | seed の導出（DISTINCT category）| 絞らない（論理削除済みの募集が使っていた値も seed）|
--   | 対象の村                       | 論理削除済みの村も含む（募集行を1件でも持つ村すべて）|
--   | バックフィル UPDATE            | 絞らない（全行）                                   |
--   | 番人の COUNT                   | 絞らない（全行が category_id IS NOT NULL であること）|
--
-- 例外は「募集行を1件も持たない村」への汎用プリセット seed のみ（下記 3-b）。
-- こちらはバックフィル対象が無く参照完全性に影響しないため、論理削除済みの村には配らない
-- （消えた村にゴミを作らない）。この非対称は意図的である（設計書 §5.5）。
--
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 村は organization_id を持たない全テナント横断ドメイン
--               （V9.147__create_village_match_recruits.sql:4 の先例と同じ根拠）
-- 原則1/2: village_id の FK は村ドメイン内のため許可（CASCADE も同一ドメイン内）。
--          created_by は user ドメインへのクロスドメイン参照のため FK を張らない。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) 村ごと募集カテゴリマスタ
-- ---------------------------------------------------------------------
-- 同名重複防止に UNIQUE 制約を張らない理由: 論理削除と両立する partial unique
-- （WHERE deleted_at IS NULL）は MySQL で表現不可のため Service 層で判定する
-- （V19.007__create_todo_status_labels.sql:4 の先例に従う）。
CREATE TABLE village_recruit_categories (
    id            BINARY(16)      NOT NULL                COMMENT 'UUIDv7 PK',
    village_id    BINARY(16)      NOT NULL                COMMENT 'FK → villages.id（同一ドメイン CASCADE）',
    name          VARCHAR(40)     NOT NULL                COMMENT 'カテゴリ名（村長/長老の自由入力・i18n対象外のユーザーデータ）',
    description   VARCHAR(200)    NULL                    COMMENT '補足説明（任意）',
    color         VARCHAR(7)      NULL                    COMMENT '表示色 #RRGGBB（任意）',
    display_order INT             NOT NULL DEFAULT 0      COMMENT '表示順（10刻み推奨）',
    is_preset     BOOLEAN         NOT NULL DEFAULT FALSE  COMMENT '自動投入された既定プリセット由来か（由来の記録のみ。変更・削除は禁じない）',
    preset_key    VARCHAR(30)     NULL                    COMMENT 'プリセット識別子。旧 enum 値（PRACTICE_MATCH/REFEREE/VENUE/OTHER）または汎用プリセットキー（PARTICIPANT/HELPER/OTHER）。移行時のバックフィル結合キー兼トレーサビリティ。村長が作ったカスタムは NULL。表示には使わない',
    created_by    BIGINT UNSIGNED NULL                    COMMENT '作成者ユーザーID（FK 張らない・原則1）',
    created_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at    DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    deleted_at    DATETIME(6)     NULL                    COMMENT '論理削除',
    version       BIGINT          NOT NULL DEFAULT 0      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    KEY idx_vrc_village_order (village_id, display_order),
    KEY idx_vrc_village_preset (village_id, preset_key),
    CONSTRAINT fk_vrc_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='村ごと募集カテゴリマスタ（F17.1）';

-- ---------------------------------------------------------------------
-- 2) village_match_recruits へマスタ参照を追加（この時点では NULL 可）
-- ---------------------------------------------------------------------
-- ON DELETE は指定しない（= RESTRICT）。使用中カテゴリの削除は Service 層で
-- RECRUIT_CATEGORY_IN_USE（VILLAGE_086）として弾くため、DB 側の CASCADE/SET NULL は
-- 不要かつ有害（募集のカテゴリが黙って消えるのは症状隠しに当たる）。
ALTER TABLE village_match_recruits
    ADD COLUMN category_id BINARY(16) NULL COMMENT 'FK → village_recruit_categories.id（同一ドメイン）' AFTER category,
    ADD KEY idx_vmr_category_id (category_id, status),
    ADD CONSTRAINT fk_vmr_recruit_category
        FOREIGN KEY (category_id) REFERENCES village_recruit_categories(id);

-- ---------------------------------------------------------------------
-- 3-a) プリセット seed その1 — 募集実績のある村
--      「その村で実際に使われている category の値のみ」を seed する。
--      未使用の値まで配ると課題A（スポーツ固着）を再生産するため配らない。
--      村・募集の論理削除は問わない（上記「論理削除の罠」）。
-- ---------------------------------------------------------------------
-- 名称は現行 ja ラベル（frontend/app/locales/ja/village.json:421-426）を踏襲する。
-- 想定外の category 値が万一存在した場合は、値そのものを名称として温存する（ELSE 節）。
-- 名称を NULL にして migration を失敗させるより、参照完全性を守って村長に改名させる方が安全。
-- id は UUID_TO_BIN(UUID()) で発番（V69.002__seed_village_categories.sql:3 /
-- V150.20260710030428__bridge_team_subscriptions_to_entitlements.sql:23 の先例）。
INSERT INTO village_recruit_categories
    (id, village_id, name, description, color, display_order, is_preset, preset_key,
     created_by, created_at, updated_at, deleted_at, version)
SELECT
    UUID_TO_BIN(UUID()),
    u.village_id,
    CASE u.category
        WHEN 'PRACTICE_MATCH' THEN '練習試合'
        WHEN 'REFEREE'        THEN '審判'
        WHEN 'VENUE'          THEN '会場'
        WHEN 'OTHER'          THEN 'その他'
        ELSE u.category
    END,
    NULL,
    NULL,
    CASE u.category
        WHEN 'PRACTICE_MATCH' THEN 10
        WHEN 'REFEREE'        THEN 20
        WHEN 'VENUE'          THEN 30
        WHEN 'OTHER'          THEN 40
        ELSE 50
    END,
    TRUE,
    u.category,
    NULL,
    NOW(6),
    NOW(6),
    NULL,
    0
FROM (SELECT DISTINCT village_id, category FROM village_match_recruits) u
WHERE NOT EXISTS (
    SELECT 1 FROM village_recruit_categories c
    WHERE c.village_id = u.village_id AND c.preset_key = u.category
);

-- ---------------------------------------------------------------------
-- 3-b) プリセット seed その2 — 募集実績が「1件も無い」生きている村
--      汎用プリセット3件を配る。スポーツ語彙は配らない（設計書 §5.5 の御裁可）。
--      語彙・preset_key・display_order はマスター御裁可により確定。変更してはならない。
--      論理削除済みの村は対象外（バックフィル対象が無く、消えた村にゴミを作らない）。
-- ---------------------------------------------------------------------
INSERT INTO village_recruit_categories
    (id, village_id, name, description, color, display_order, is_preset, preset_key,
     created_by, created_at, updated_at, deleted_at, version)
SELECT UUID_TO_BIN(UUID()), v.id, p.name, NULL, NULL, p.display_order, TRUE, p.preset_key,
       NULL, NOW(6), NOW(6), NULL, 0
FROM villages v
CROSS JOIN (
              SELECT 'PARTICIPANT' AS preset_key, '参加者募集' AS name, 10 AS display_order
    UNION ALL SELECT 'HELPER'      AS preset_key, '協力者募集' AS name, 20 AS display_order
    UNION ALL SELECT 'OTHER'       AS preset_key, 'その他'     AS name, 30 AS display_order
) p
WHERE v.deleted_at IS NULL
  AND NOT EXISTS (
      -- 募集行を1件でも持つ村は 3-a の対象。deleted_at で絞らないこと
      SELECT 1 FROM village_match_recruits r WHERE r.village_id = v.id
  )
  AND NOT EXISTS (
      SELECT 1 FROM village_recruit_categories c
      WHERE c.village_id = v.id AND c.preset_key = p.preset_key
  );

-- ---------------------------------------------------------------------
-- 4) 既存行のバックフィル — category（enum 文字列）→ 同一村の同一 preset_key の id
--    deleted_at で絞らない（全行）。論理削除済みの募集・村の行も必ず埋める。
-- ---------------------------------------------------------------------
-- 結合先の c.deleted_at IS NULL は「3-a/3-b で今 seed したばかりの行（全て生存）」を指すため
-- 実質的に無条件であり、上記「論理削除の罠」（募集・村の deleted_at）とは別物である。
-- (village_id, preset_key) は seed 側の NOT EXISTS により一意なので、この結合は 1:1 になる。
UPDATE village_match_recruits r
JOIN village_recruit_categories c
  ON c.village_id = r.village_id
 AND c.preset_key = r.category
 AND c.deleted_at IS NULL
SET r.category_id = c.id
WHERE r.category_id IS NULL;

-- ---------------------------------------------------------------------
-- 5) 番人 — バックフィルの取りこぼしを黙って許さない
-- ---------------------------------------------------------------------
-- category_id が埋まらなかった行が1件でもあれば移行は不完全である。黙って進めると
-- P6（Contract）の NOT NULL 化が失敗する（時限爆弾）ため、ここで migration ごと停止させる。
-- deleted_at で絞らない（論理削除済みの行も NOT NULL 制約の対象になるため）。
--
-- SIGNAL / IF は stored program の外では使えないため、手続きを一時的に作って CALL し、
-- 直ちに破棄する。flyway-mysql プラグインは BEGIN ... END ブロックを自動認識するため
-- DELIMITER 命令は不要（V13.045__create_repair_simulation_scenarios.sql:36 の先例）。
DROP PROCEDURE IF EXISTS vrc_assert_backfill_complete;

CREATE PROCEDURE vrc_assert_backfill_complete()
BEGIN
    DECLARE orphan_count BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO orphan_count
      FROM village_match_recruits
     WHERE category_id IS NULL;

    -- MESSAGE_TEXT は 128 文字で切り詰められるため簡潔に保つ（詳細は本ファイル冒頭 §5.4 の注記参照）
    IF orphan_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'F17.1 P1: category_id backfill incomplete. Check seed/backfill are NOT filtered by deleted_at (design 5.4).';
    END IF;
END;

CALL vrc_assert_backfill_complete();

DROP PROCEDURE vrc_assert_backfill_complete;

-- ---------------------------------------------------------------------
-- 6) match_date のスポーツ固着を緩和（設計書 §5.6）
-- ---------------------------------------------------------------------
-- カテゴリを汎用化しても日付が必須のままでは「マネージャー募集」「引っ越し手伝い募集」等の
-- 日付を持たない募集が登録できない。NOT NULL → NULL は制約の緩和方向であり、
-- 既存データを一切壊さない（既存行は全て値を持つ）。
ALTER TABLE village_match_recruits
    MODIFY COLUMN match_date DATE NULL COMMENT '予定日（任意。日付を持たない募集もあるため NULL 可・F17.1 §5.6）';
