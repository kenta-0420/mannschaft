-- F08.1: マッチング成立後の相互レビューテーブル
CREATE TABLE match_reviews (
    id               BIGINT UNSIGNED   NOT NULL AUTO_INCREMENT,
    proposal_id      BIGINT UNSIGNED   NOT NULL,
    reviewer_team_id BIGINT UNSIGNED   NOT NULL,
    reviewee_team_id BIGINT UNSIGNED   NOT NULL,
    rating           TINYINT UNSIGNED  NOT NULL,
    comment          VARCHAR(1000)     NULL,
    is_public        BOOLEAN           NOT NULL DEFAULT TRUE,
    created_at       DATETIME          NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_mrev_proposal FOREIGN KEY (proposal_id) REFERENCES match_proposals(id) ON DELETE CASCADE,
    CONSTRAINT fk_mrev_reviewer FOREIGN KEY (reviewer_team_id) REFERENCES teams(id),
    CONSTRAINT fk_mrev_reviewee FOREIGN KEY (reviewee_team_id) REFERENCES teams(id),
    CONSTRAINT chk_mrev_rating CHECK (rating >= 1 AND rating <= 5),
    UNIQUE KEY uq_mr_proposal_reviewer (proposal_id, reviewer_team_id),
    INDEX idx_mr_reviewee (reviewee_team_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
