-- F00.6: ベータ登録制限設定テーブル
CREATE TABLE beta_restriction_config (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  is_enabled  BOOLEAN      NOT NULL DEFAULT FALSE,
  max_team_id BIGINT       NULL COMMENT 'このID以下のチームが招待可能（NULL=制限なし）',
  max_org_id  BIGINT       NULL COMMENT 'このID以下の組織が招待可能（NULL=制限なし）',
  updated_by  BIGINT       NULL,
  updated_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

INSERT INTO beta_restriction_config (is_enabled, updated_at)
VALUES (FALSE, NOW(6));
