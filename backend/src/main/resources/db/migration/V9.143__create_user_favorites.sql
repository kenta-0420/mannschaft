CREATE TABLE user_favorites (
    id              BINARY(16)          NOT NULL                COMMENT 'UUIDv7 PK',
    user_id         BIGINT UNSIGNED     NOT NULL                COMMENT 'お気に入り登録者（FK 張らない / 退会時バッチ削除）',
    entity_type     VARCHAR(50)         NOT NULL                COMMENT 'TEAM | ORGANIZATION | KB_PAGE | BLOG_AUTHOR | VILLAGE',
    entity_id       VARCHAR(36)         NOT NULL                COMMENT 'エンティティID（BIGINT は十進数文字列 "123"、UUID は36文字ハイフン付き "018f-..."）',
    display_order   SMALLINT UNSIGNED   NOT NULL DEFAULT 0      COMMENT '表示順（低い値が先頭）',
    created_at      DATETIME            NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE  KEY uq_uf_user_entity  (user_id, entity_type, entity_id)  COMMENT '同一エンティティの重複登録防止',
    KEY     idx_uf_user_order      (user_id, display_order)           COMMENT 'ユーザー別一覧取得（表示順）',
    KEY     idx_uf_entity          (entity_type, entity_id)           COMMENT 'エンティティ削除時のバッチ逆引き'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='ユーザー横断お気に入り（ダッシュボードショートカット）';
