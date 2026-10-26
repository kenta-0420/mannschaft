-- F13.1 Phase 13.1.2: QR チェックイン／アウト用トークンテーブル
-- 設計書 §2.3.1 / §5.2 参照。
-- リプレイ攻撃対策のため nonce は UNIQUE、使い捨て（used_at 記録後は再利用不可）。
-- TTL デフォルト 60 秒（expires_at = issued_at + 60s）。
-- 手動入力フォールバック用の short_code（6 文字）も併せて保持。
CREATE TABLE job_qr_tokens (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  job_contract_id BIGINT UNSIGNED NOT NULL,
  type ENUM('IN','OUT') NOT NULL,
  nonce VARCHAR(36) NOT NULL,
  kid VARCHAR(32) NOT NULL,
  short_code VARCHAR(6) NOT NULL,
  issued_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  expires_at TIMESTAMP(3) NOT NULL,
  used_at TIMESTAMP(3) NULL,
  issued_by_user_id BIGINT UNSIGNED NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT uq_jqt_nonce UNIQUE (nonce),
  CONSTRAINT fk_jqt_contract FOREIGN KEY (job_contract_id) REFERENCES job_contracts (id) ON DELETE CASCADE,
  CONSTRAINT fk_jqt_issuer FOREIGN KEY (issued_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
  INDEX idx_jqt_contract_type_expires (job_contract_id, type, expires_at),
  INDEX idx_jqt_short_code (short_code, expires_at),
  INDEX idx_jqt_used_at (used_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F13.1 Phase 13.1.2: QR チェックイン／アウト用短命トークン';
