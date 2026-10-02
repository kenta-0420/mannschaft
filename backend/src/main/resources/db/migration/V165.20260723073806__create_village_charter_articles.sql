-- F17.3 村憲章: village_charter_articles（条・1条1レコード）
-- 条文は自由文の1本メモではなく 1条=1レコードの構造化データ。条番号(第一条…)は保存せず、
-- 並び順(sort_order)から表示時に自動採番する（途中挿入・削除で振り直し不要・設計書 §2決定1/§6）。
-- version は層1 楽観ロック（本文/付則の in-place 更新・§7）。sort_order には UNIQUE を張らない
-- （並び替え中間状態で一時重複しうる＋論理削除行が同一空間に残るため・§6.2）。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
--
-- 採番根拠: V164〜V167（origin/main 最大 V163 の次・秒ずらし）。
--
-- 設計判断（docs/features/F17.3_village_charter.md §13.1.2）:
--   - charter_id は同一ドメイン内アグリゲート → FK CASCADE（原則2 が明示許可）
--   - village_id は村スコープの冗長列（IDOR 照合用・§AC-08）。villages へは FK非付与（村既存作法）
--   - 論理削除（deleted_at）で原則3。削除後は再連番から除外（§6.3）

CREATE TABLE village_charter_articles (
    id                  BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    charter_id          BINARY(16)      NOT NULL                                COMMENT '→ village_charters.id（同一ドメイン・FK CASCADE）',
    village_id          BINARY(16)      NOT NULL                                COMMENT '村スコープの冗長列（IDOR 照合用・§AC-08）',
    sort_order          INT             NOT NULL                                COMMENT '0始まり連番（表示採番の元・UNIQUE張らない・§6.2）',
    body                TEXT            NOT NULL                                COMMENT '条文（必須）',
    supplement          TEXT            NULL                                    COMMENT '付則（任意）',
    version             BIGINT          NOT NULL DEFAULT 0                      COMMENT '@Version（本文更新＝層1 楽観ロック・§7）',
    deleted_at          DATETIME(6)     NULL                                    COMMENT '論理削除（原則3・削除後は再連番から除外）',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_vca_charter_sort (charter_id, sort_order),
    CONSTRAINT fk_vca_charter FOREIGN KEY (charter_id)
        REFERENCES village_charters(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村憲章の条（1条1レコード・自動採番）（F17.3）';
