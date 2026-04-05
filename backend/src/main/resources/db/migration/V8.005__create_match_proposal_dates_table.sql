-- F08.1: 応募時の複数日程候補テーブル
CREATE TABLE match_proposal_dates (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    proposal_id       BIGINT UNSIGNED NOT NULL,
    proposed_date     DATE            NOT NULL,
    proposed_time_from TIME           NULL,
    proposed_time_to  TIME            NULL,
    is_selected       BOOLEAN         NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    CONSTRAINT fk_mpd_proposal FOREIGN KEY (proposal_id) REFERENCES match_proposals(id) ON DELETE CASCADE,
    INDEX idx_mpd_proposal (proposal_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
