-- F06.5 アクティブリコール学習機能: recall_attempts（想起テスト記録）
-- 1エントリにつき複数 recall（1/3/7/14日後…）が積み上がる。保存＝開示で revealed_at 記録。
-- entry_id は同一 reflection ドメインゆえ FK＋CASCADE 可。soft delete なし（履歴保持）。
CREATE TABLE recall_attempts (
    id                BINARY(16)  NOT NULL,
    entry_id          BINARY(16)  NOT NULL,                         -- 同一ドメイン FK 可
    user_id           BIGINT      NOT NULL,                         -- 所有者非正規化（FK なし）
    recall_date       DATE        NOT NULL,                         -- 想起を行った日
    recalled_content  JSON        NOT NULL,                         -- 思い出して書いた内容
    self_rating       VARCHAR(12) NOT NULL,                         -- REMEMBERED/PARTIAL/FORGOT
    revealed_at       DATETIME    NULL,                             -- 開示時刻（保存＝開示で記録・AC-7）
    created_at        DATETIME    NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_recall_attempts_entry
        FOREIGN KEY (entry_id) REFERENCES reflection_entries(id) ON DELETE CASCADE,  -- 同一ドメイン
    CONSTRAINT chk_recall_attempts_self_rating
        CHECK (self_rating IN ('REMEMBERED','PARTIAL','FORGOT')),
    INDEX idx_recall_attempts_entry (entry_id, recall_date),
    INDEX idx_recall_attempts_user_date (user_id, recall_date)
);
