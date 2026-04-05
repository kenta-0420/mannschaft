CREATE TABLE safety_check_templates (
    id                        BIGINT UNSIGNED  NOT NULL AUTO_INCREMENT,
    scope_type                VARCHAR(20)      NULL,
    scope_id                  BIGINT UNSIGNED  NULL,
    template_name             VARCHAR(100),
    title                     VARCHAR(200),
    message                   VARCHAR(1000),
    reminder_interval_minutes INT,
    is_system_default         BOOLEAN          NOT NULL DEFAULT FALSE,
    sort_order                INT              NOT NULL DEFAULT 0,
    created_by                BIGINT UNSIGNED,
    created_at                DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),

    CONSTRAINT fk_sct_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='安否確認テンプレート';

-- Seed: システムデフォルトテンプレート4件
INSERT INTO safety_check_templates (template_name, title, message, reminder_interval_minutes, is_system_default, sort_order) VALUES
    ('地震', '【緊急】地震発生 安否確認', '地震が発生しました。安否状況を報告してください。', 30, TRUE, 1),
    ('台風', '【緊急】台風接近 安否確認', '台風が接近しています。安否状況を報告してください。', 60, TRUE, 2),
    ('火災', '【緊急】火災発生 安否確認', '火災が発生しました。安否状況を報告してください。', 15, TRUE, 3),
    ('不審者', '【緊急】不審者情報 安否確認', '不審者情報が報告されました。安否状況を報告してください。', 30, TRUE, 4);
