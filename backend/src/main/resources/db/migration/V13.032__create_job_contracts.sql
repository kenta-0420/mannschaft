-- F13.1 Phase 13.1.1 MVP: 求人契約テーブル
-- 論理削除なし（CANCELLED ステータスで表現）。MVP ではチャット自動作成のため chat_room_id を保持する。
-- 備考: 既存 DB には chat_rooms テーブルは存在せず、類似機能は chat_channels が担っている。
--       本カラムは Phase 13.1.2 以降の F04.2 連携（チャット自動作成）で chat_channels.id を格納する想定だが、
--       既存テーブル名との衝突回避と設計書 §5.2 の表記を尊重し、カラム名は `chat_room_id` のままとする。
--       chat_rooms テーブルが未導入のため FK 制約は付与せず、論理的な参照（nullable）にとどめる。
--       Phase 13.1.2 以降で chat_channels との接続 or chat_rooms テーブルの新設にあわせて FK を追加する。
CREATE TABLE job_contracts (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  job_posting_id BIGINT UNSIGNED NOT NULL,
  job_application_id BIGINT UNSIGNED NOT NULL,
  requester_user_id BIGINT UNSIGNED NOT NULL,
  worker_user_id BIGINT UNSIGNED NOT NULL,
  chat_room_id BIGINT UNSIGNED NULL,
  base_reward_jpy INT NOT NULL,
  work_start_at DATETIME NOT NULL,
  work_end_at DATETIME NOT NULL,
  status ENUM('MATCHED','CHECKED_IN','IN_PROGRESS','CHECKED_OUT','TIME_CONFIRMED','COMPLETION_REPORTED','COMPLETED','AUTHORIZED','CAPTURED','PAID','CANCELLED','DISPUTED') NOT NULL DEFAULT 'MATCHED',
  matched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completion_reported_at DATETIME NULL,
  completion_approved_at DATETIME NULL,
  cancelled_at DATETIME NULL,
  rejection_count INT NOT NULL DEFAULT 0,
  last_rejection_reason TEXT NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_jc_posting FOREIGN KEY (job_posting_id) REFERENCES job_postings (id) ON DELETE RESTRICT,
  CONSTRAINT fk_jc_application FOREIGN KEY (job_application_id) REFERENCES job_applications (id) ON DELETE RESTRICT,
  CONSTRAINT fk_jc_requester FOREIGN KEY (requester_user_id) REFERENCES users (id) ON DELETE RESTRICT,
  CONSTRAINT fk_jc_worker FOREIGN KEY (worker_user_id) REFERENCES users (id) ON DELETE RESTRICT,
  CONSTRAINT uk_jc_application UNIQUE (job_application_id),
  INDEX idx_jc_worker_status (worker_user_id, status),
  INDEX idx_jc_requester_status (requester_user_id, status),
  INDEX idx_jc_posting (job_posting_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='F13.1 MVP: 求人契約';
