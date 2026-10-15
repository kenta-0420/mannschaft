-- F08.1: 募集への応募テーブル
CREATE TABLE match_proposals (
    id                   BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    request_id           BIGINT UNSIGNED  NOT NULL,
    proposing_team_id    BIGINT UNSIGNED  NOT NULL,
    message              TEXT             NULL,
    proposed_venue       VARCHAR(200)     NULL,
    status               ENUM('PENDING','ACCEPTED','REJECTED','CANCELLED','WITHDRAWN') NOT NULL DEFAULT 'PENDING',
    status_reason        VARCHAR(500)     NULL,
    cancelled_by_team_id BIGINT UNSIGNED  NULL,
    cancellation_type    ENUM('UNILATERAL','MUTUAL_PENDING','MUTUAL') NULL,
    mutual_agreed_at     DATETIME         NULL,
    created_at           DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_mp_request FOREIGN KEY (request_id) REFERENCES match_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_mp_proposing_team FOREIGN KEY (proposing_team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_mp_cancelled_by FOREIGN KEY (cancelled_by_team_id) REFERENCES teams(id) ON DELETE SET NULL,
    UNIQUE KEY uq_mp_request_team (request_id, proposing_team_id),
    INDEX idx_mp_proposing_team (proposing_team_id, status),
    INDEX idx_mp_request_status (request_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- match_requests.matched_proposal_id への FK を後付け（循環FK回避）
ALTER TABLE match_requests
    ADD CONSTRAINT fk_mr_matched_proposal
    FOREIGN KEY (matched_proposal_id) REFERENCES match_proposals(id) ON DELETE SET NULL;
