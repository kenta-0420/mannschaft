-- デジタルサイネージ 画面テーブル
CREATE TABLE signage_screens (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    scope_type             VARCHAR(50)  NOT NULL,
    scope_id               BIGINT       NOT NULL,
    name                   VARCHAR(100) NOT NULL,
    layout                 VARCHAR(20)  NOT NULL DEFAULT 'LANDSCAPE' COMMENT 'LANDSCAPE/PORTRAIT/STANDARD',
    default_slide_duration INT          NOT NULL DEFAULT 10 COMMENT '秒',
    transition_effect      VARCHAR(20)  NOT NULL DEFAULT 'FADE' COMMENT 'FADE/SLIDE/NONE',
    is_clock_shown         TINYINT(1)   NOT NULL DEFAULT 1,
    is_weather_shown       TINYINT(1)   NOT NULL DEFAULT 0,
    weather_location       VARCHAR(200) NULL COMMENT '天気予報の地名',
    background_color       VARCHAR(7)   NOT NULL DEFAULT '#000000',
    is_active              TINYINT(1)   NOT NULL DEFAULT 1,
    created_by             BIGINT       NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    created_at             DATETIME     NOT NULL,
    updated_at             DATETIME     NOT NULL,
    deleted_at             DATETIME     NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_ss_created_by FOREIGN KEY (created_by) REFERENCES users (id),
    INDEX idx_ss_scope (scope_type, scope_id, is_active)
);
