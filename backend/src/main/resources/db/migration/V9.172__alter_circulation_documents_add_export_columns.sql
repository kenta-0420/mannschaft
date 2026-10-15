-- F05.2 Phase 11 第四陣 4-C: 押印済み証跡 PDF エクスポート用カラム追加
--
-- 設計書: docs/features/F05.2_circular.md §3 / §4.8
-- 用途: COMPLETED 状態の回覧文書に対して押印済み証跡 PDF を非同期生成し、
--       R2 上のファイルキーと生成ステータスを circulation_documents に保持する。
--
-- カラム説明:
--   export_status         : エクスポート状態（NOT_GENERATED / PENDING / COMPLETED / FAILED）
--   export_file_key       : R2 オブジェクトキー（生成完了時にセット）
--   export_requested_at   : 生成リクエスト受付時刻
--   export_completed_at   : 生成完了時刻
--   export_error_message  : 生成失敗時のエラー要約（最大 1000 文字）

ALTER TABLE circulation_documents
    ADD COLUMN export_status VARCHAR(30) NOT NULL DEFAULT 'NOT_GENERATED' COMMENT 'エクスポート生成状態（NOT_GENERATED / PENDING / COMPLETED / FAILED）',
    ADD COLUMN export_file_key VARCHAR(500) NULL COMMENT 'R2 オブジェクトキー（生成完了時にセット）',
    ADD COLUMN export_requested_at DATETIME(3) NULL COMMENT 'エクスポート生成リクエスト受付時刻',
    ADD COLUMN export_completed_at DATETIME(3) NULL COMMENT 'エクスポート生成完了時刻',
    ADD COLUMN export_error_message VARCHAR(1000) NULL COMMENT '生成失敗時のエラー要約';
