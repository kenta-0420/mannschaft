-- F08.7 Phase 9: tournament_divisions へエントリー人数制限カラムを追加
ALTER TABLE tournament_divisions
    ADD COLUMN min_entry_count SMALLINT UNSIGNED NULL
        COMMENT 'エントリー最少人数（NULL = 制限なし）'
        AFTER max_participants,
    ADD COLUMN max_entry_count SMALLINT UNSIGNED NULL
        COMMENT 'エントリー最大人数（NULL = 制限なし）'
        AFTER min_entry_count;
