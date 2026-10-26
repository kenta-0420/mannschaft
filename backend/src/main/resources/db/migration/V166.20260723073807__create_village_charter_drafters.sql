-- F17.3 村憲章: village_charter_drafters（策定者・複数可・表示順あり）
-- 「誰が村を興し・約束を定めたか」を村の史料として残す。制定/追加時の村ニックネームを
-- nickname_snapshot に焼き付け、退村しても・アカウント退会しても名前が残る（設計書 §5・§2決定5）。
-- 退会時は user_id のみ NULL 化して個人へのリンクを切断し、仮名文字列(nickname_snapshot)は残置する
-- （原則4「投稿・履歴は保持し個人情報のみ消去」・実名は元々保存しない＝§10 G4）。
-- 原則6 適用: PK = UUIDv7 BINARY(16)
--
-- 採番根拠: V164〜V167（origin/main 最大 V163 の次・秒ずらし）。
--
-- 設計判断（docs/features/F17.3_village_charter.md §13.1.3）:
--   - charter_id は同一ドメイン内アグリゲート → FK CASCADE（原則2）
--   - user_id は別ドメイン(users) → FK非付与（原則1）。NULL 許容（退会時 NULL 化・原則4）
--   - UNIQUE(charter_id, user_id) で二重登録防止。MySQL は UNIQUE 上 NULL を相異扱いするため
--     退会後の複数 NULL 行は共存可（§5.4）

CREATE TABLE village_charter_drafters (
    id                  BINARY(16)      NOT NULL                                COMMENT 'UUIDv7 PK',
    charter_id          BINARY(16)      NOT NULL                                COMMENT '→ village_charters.id（同一ドメイン・FK CASCADE）',
    user_id             BIGINT          NULL                                    COMMENT '策定者（FK非付与・退会時 NULL 化・原則1/4）',
    nickname_snapshot   VARCHAR(40)     NOT NULL                                COMMENT '制定/追加時の村ニックネーム焼付（退会後も残置・§5.2）',
    sort_order          INT             NOT NULL                                COMMENT '表示順（0始まり・末尾追加）',
    created_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at          DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_vcd_charter_user (charter_id, user_id),
    KEY idx_vcd_charter_sort (charter_id, sort_order),
    CONSTRAINT fk_vcd_charter FOREIGN KEY (charter_id)
        REFERENCES village_charters(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='村憲章の策定者（村ニックネーム焼付・退会後も残置）（F17.3）';
