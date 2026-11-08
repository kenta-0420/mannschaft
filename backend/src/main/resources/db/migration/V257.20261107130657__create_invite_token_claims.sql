-- 招待の使用回数を消費した確保記録（CMP-260826-1457 W1 前段・招待承諾の分離）。
-- 承諾の入口を単一トランザクションから短いトランザクションの連鎖へ分けたため、
-- 「使用回数の消費」と「ロール付与」を別のTXで行う。使用回数の消費を1行の claim と結び付け、
-- 補償（release）をちょうど1回に限る。状態遷移は CLAIMED→GRANTED と CLAIMED→RELEASED のみ。
-- user_id は auth ドメインのため FK を張らない（クロスドメインFK禁止）。(token_id, user_id) の一意制約は
-- 付けない（退会後の同一共有リンクの再参加を残すため）。token_id は同ドメインの invite_tokens への FK（CASCADE なし）。
-- created_at / updated_at はアプリ側で UTC 壁時計を設定する（状態更新の native 文は UTC_TIMESTAMP を使う）。
CREATE TABLE invite_token_claims (
    id BINARY(16) NOT NULL,
    token_id BIGINT UNSIGNED NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_itc_token FOREIGN KEY (token_id) REFERENCES invite_tokens (id),
    CONSTRAINT chk_itc_status CHECK (status IN ('CLAIMED', 'GRANTED', 'RELEASED')),
    INDEX idx_itc_status_created (status, created_at),
    INDEX idx_itc_token (token_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
