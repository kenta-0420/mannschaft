-- シフトの楽観ロック列を Java Long / Hibernate @Version の BIGINT と一致させる。
-- 既存 INT UNSIGNED の全範囲を保ち、非負制約も CHECK で維持する。
ALTER TABLE shift_assignments
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_shift_assignments_version_nonnegative CHECK (version >= 0);

ALTER TABLE shift_assignment_runs
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_shift_assignment_runs_version_nonnegative CHECK (version >= 0);

ALTER TABLE shift_change_requests
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_shift_change_requests_version_nonnegative CHECK (version >= 0);

ALTER TABLE shift_swap_requests
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_shift_swap_requests_version_nonnegative CHECK (version >= 0);
