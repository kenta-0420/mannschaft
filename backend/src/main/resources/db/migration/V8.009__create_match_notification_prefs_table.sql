-- F08.1: マッチング推薦通知設定テーブル
CREATE TABLE match_notification_preferences (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    team_id         BIGINT UNSIGNED NOT NULL,
    prefecture_code CHAR(2)         NULL,
    city_code       CHAR(5)         NULL,
    activity_type   ENUM('COMPETITION','PRACTICE','EXCHANGE','RECRUIT','OTHER') NULL,
    category        ENUM('ELEMENTARY','JUNIOR_HIGH','HIGH_SCHOOL','UNIVERSITY','ADULT','SENIOR','ANY') NULL,
    is_enabled      BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_mnp_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
    CONSTRAINT fk_mnp_prefecture FOREIGN KEY (prefecture_code) REFERENCES prefectures(code),
    CONSTRAINT fk_mnp_city FOREIGN KEY (city_code) REFERENCES cities(code),
    UNIQUE KEY uq_mnp_team (team_id),
    INDEX idx_mnp_match (is_enabled, prefecture_code, activity_type, category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
