-- CMP-260901-1538 柱③-A 検分第4巡是正（P1-2）: teams 版。V201（organizations）と同型。
-- 生成列 name_trimmed（GENERATED ALWAYS AS (TRIM(name)) STORED）＋索引を追加し、
-- TeamRepository#findActiveByNormalizedName(ForUpdate) が索引を使えるようにする。
ALTER TABLE teams
    ADD COLUMN name_trimmed VARCHAR(100)
        GENERATED ALWAYS AS (TRIM(name)) STORED
        COMMENT '柱③-A 同名確認フロー用: TRIM(name)の生成列（索引対象）',
    ADD KEY idx_teams_name_trimmed (name_trimmed);
