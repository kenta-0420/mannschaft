-- F03.11 募集型予約: 固定カテゴリマスタ (Phase 1)
CREATE TABLE recruitment_categories (
    id                          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    code                        VARCHAR(50)     NOT NULL,
    name_i18n_key               VARCHAR(100)    NOT NULL,
    icon                        VARCHAR(50),
    default_participation_type  VARCHAR(20)     NOT NULL DEFAULT 'INDIVIDUAL',
    display_order               INT UNSIGNED    NOT NULL DEFAULT 0,
    is_active                   BOOLEAN         NOT NULL DEFAULT TRUE,
    created_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rc_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 初期データ: 15 種類の固定カテゴリ (設計書 §3.1)
INSERT INTO recruitment_categories (code, name_i18n_key, icon, default_participation_type, display_order, is_active) VALUES
    ('futsal_open',     'recruitment.category.futsal_open',     'pi-circle-fill', 'INDIVIDUAL',  10, TRUE),
    ('soccer_open',     'recruitment.category.soccer_open',     'pi-circle-fill', 'INDIVIDUAL',  20, TRUE),
    ('basketball_open', 'recruitment.category.basketball_open', 'pi-circle-fill', 'INDIVIDUAL',  30, TRUE),
    ('yoga_class',      'recruitment.category.yoga_class',      'pi-heart',       'INDIVIDUAL',  40, TRUE),
    ('swimming_class',  'recruitment.category.swimming_class',  'pi-heart',       'INDIVIDUAL',  50, TRUE),
    ('dance_class',     'recruitment.category.dance_class',     'pi-heart',       'INDIVIDUAL',  60, TRUE),
    ('fitness_class',   'recruitment.category.fitness_class',   'pi-heart',       'INDIVIDUAL',  70, TRUE),
    ('match_opponent',  'recruitment.category.match_opponent',  'pi-users',       'TEAM',        80, TRUE),
    ('practice_match',  'recruitment.category.practice_match',  'pi-users',       'TEAM',        90, TRUE),
    ('tournament',      'recruitment.category.tournament',      'pi-trophy',      'TEAM',       100, TRUE),
    ('referee',         'recruitment.category.referee',         'pi-flag',        'INDIVIDUAL', 110, TRUE),
    ('staff',           'recruitment.category.staff',           'pi-user-plus',   'INDIVIDUAL', 120, TRUE),
    ('event_meeting',   'recruitment.category.event_meeting',   'pi-calendar',    'INDIVIDUAL', 130, TRUE),
    ('workshop',        'recruitment.category.workshop',        'pi-book',        'INDIVIDUAL', 140, TRUE),
    ('other',           'recruitment.category.other',           'pi-tag',         'INDIVIDUAL', 999, TRUE);
