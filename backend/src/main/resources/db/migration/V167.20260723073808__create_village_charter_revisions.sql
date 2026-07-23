-- F17.3 村憲章: village_charter_revisions（改定履歴・軽量・append-only）
-- 「改正を確定」時に 1 行追記する軽量履歴。日付(revised_at)＋任意メモ(note)のみで、
-- そのときの条文全文スナップショットは持たない（版管理はスコープ外・設計書 §8.3）。
-- append-only（論理削除列を持たない）。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
--
-- 採番根拠: V164〜V167（origin/main 最大 V163 の次・秒ずらし）。
--
-- 設計判断（docs/features/F17.3_village_charter.md §13.1.4）:
--   - charter_id は同一ドメイン内アグリゲート → FK CASCADE（原則2）
--   - note は改定主旨のメモ（≤200・DDL VARCHAR(200) と @Size(max=200) 一致）

CREATE TABLE village_charter_revisions (
    id                  BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    charter_id          BINARY(16)      NOT NULL                                COMMENT '→ village_charters.id（同一ドメイン・FK CASCADE）',
    revised_at          DATETIME(6)     NOT NULL                                COMMENT '改定日時（「改正を確定」時刻・§8.2）',
    note                VARCHAR(200)    NULL                                    COMMENT '任意メモ（条文スナップショット無し・§8.3）',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_vcr_charter_revised (charter_id, revised_at),
    CONSTRAINT fk_vcr_charter FOREIGN KEY (charter_id)
        REFERENCES village_charters(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村憲章の改定履歴（軽量・append-only）（F17.3）';
