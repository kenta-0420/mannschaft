-- F09.15 居住者死亡管理 / 相続支援
-- resident_registry に死亡関連の 5 カラムを追加する。
-- F09.1 と同一ドメイン内の拡張であり、主キーは BIGINT を維持する（UUIDv7 化はしない）。
-- death_status_changed_by はクロスドメイン弱参照のため FK を張らず INDEX のみ。
ALTER TABLE resident_registry
    ADD COLUMN death_status VARCHAR(30) NOT NULL DEFAULT 'ALIVE'
        COMMENT '死亡状態: ALIVE / SUSPECTED / CONFIRMED / CANCELLED_FALSE_ALARM',
    ADD COLUMN death_status_changed_at DATETIME(6) NULL
        COMMENT '死亡状態の最終変更日時',
    ADD COLUMN death_status_changed_by BIGINT UNSIGNED NULL
        COMMENT '死亡状態を変更した user_id（弱参照・FKなし）',
    ADD COLUMN presumed_death_score SMALLINT UNSIGNED NULL
        COMMENT '居住実態推定スコア 0〜100（F09.16 が更新・本人非開示）',
    ADD COLUMN activity_last_seen_at DATETIME(6) NULL
        COMMENT '直近アクティビティ日時のキャッシュ（F09.16 ActivitySnapshotAggregator が更新）';

CREATE INDEX idx_resident_registry_death_status
    ON resident_registry (death_status, deleted_at);

CREATE INDEX idx_resident_registry_presumed_score
    ON resident_registry (presumed_death_score);

CREATE INDEX idx_resident_registry_death_changed_at
    ON resident_registry (death_status_changed_at);
