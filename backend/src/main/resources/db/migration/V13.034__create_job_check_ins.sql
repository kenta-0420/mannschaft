-- F13.1 Phase 13.1.2: QR チェックイン／アウト実績テーブル
-- 設計書 §2.3.1 / §5.2 参照。
-- IN / OUT は各契約につき 1 件のみ（UNIQUE）。
-- Geolocation は契約完了 90 日後のバッチで NULL 更新（`geolocation_deleted_at` を記録）。
-- qr_token_id は ON DELETE SET NULL（トークン物理削除後も監査ログとしてレコードを残す）。
CREATE TABLE job_check_ins (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  job_contract_id BIGINT UNSIGNED NOT NULL,
  worker_user_id BIGINT UNSIGNED NOT NULL,
  type ENUM('IN','OUT') NOT NULL,
  qr_token_id BIGINT UNSIGNED NULL,
  scanned_at TIMESTAMP(3) NOT NULL,
  server_received_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  offline_submitted BOOLEAN NOT NULL DEFAULT FALSE,
  manual_code_fallback BOOLEAN NOT NULL DEFAULT FALSE,
  geolocation_lat DECIMAL(9,6) NULL,
  geolocation_lng DECIMAL(9,6) NULL,
  geolocation_accuracy_m DECIMAL(8,2) NULL,
  geo_anomaly BOOLEAN NOT NULL DEFAULT FALSE,
  geolocation_deleted_at TIMESTAMP(3) NULL,
  client_user_agent VARCHAR(512) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uq_jci_contract_type UNIQUE (job_contract_id, type),
  CONSTRAINT fk_jci_contract FOREIGN KEY (job_contract_id) REFERENCES job_contracts (id) ON DELETE CASCADE,
  CONSTRAINT fk_jci_worker FOREIGN KEY (worker_user_id) REFERENCES users (id) ON DELETE RESTRICT,
  CONSTRAINT fk_jci_qr_token FOREIGN KEY (qr_token_id) REFERENCES job_qr_tokens (id) ON DELETE SET NULL,
  INDEX idx_jci_worker_scanned (worker_user_id, scanned_at),
  INDEX idx_jci_contract (job_contract_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F13.1 Phase 13.1.2: QR チェックイン／アウト実績';
