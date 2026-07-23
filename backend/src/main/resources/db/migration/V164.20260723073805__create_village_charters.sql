-- F17.3 村憲章（Village Charter）: village_charters（親・1村1憲章）
-- 村ごとの「拠りどころ＝憲章」の親テーブル。制定日(enacted_at)は初回作成時に自動セットし不変、
-- 改定日(last_revised_at)は手動「改正を確定」で更新する（設計書 §8）。
-- version は層2 楽観ロック（全構造変更 POST/DELETE/PATCH order でバンプ・PATCH order は楽観検査・§7）。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
-- 原則7 適用外: 村ドメインは全テナント横断（村スコープは village_id で絞る）
--
-- 採番根拠: 本ブランチ作成時点(2026-07-23)の origin/main 最大 major は V163
--   (V163.20260723024012__alter_village_meetups_add_capacity.sql)。よって憲章4表は V164〜V167。
--   minor はタイムスタンプ必須(連番禁止・番人 FlywayTimestampNamingGuardTest)。4本を秒ずらしで採番。
--
-- 設計判断（docs/features/F17.3_village_charter.md §13.1.1）:
--   - village_id は同一ドメインだが村既存作法に倣い FK非付与＋UNIQUE（原則1・§13.1.1）
--   - UNIQUE(village_id) で「1村1憲章」＝§4.5 初回自動生成の一意性を担保
--   - 論理削除（deleted_at）で原則3 に準拠

CREATE TABLE village_charters (
    id                  BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    village_id          BINARY(16)      NOT NULL                                COMMENT '村スコープ（FK非付与＋UNIQUE・原則1/村既存作法）',
    enacted_at          DATETIME(6)     NOT NULL                                COMMENT '制定日（初回作成時に自動セット・不変・§8.1）',
    last_revised_at     DATETIME(6)     NULL                                    COMMENT '改定日（手動「改正を確定」・未改正はNULL・§8.2）',
    version             BIGINT          NOT NULL DEFAULT 0                      COMMENT '@Version（層2・PATCH order 楽観検査＋全構造変更でバンプ・§7）',
    deleted_at          DATETIME(6)     NULL                                    COMMENT '論理削除（原則3）',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_vc_village (village_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村憲章（親・1村1憲章）（F17.3）';
