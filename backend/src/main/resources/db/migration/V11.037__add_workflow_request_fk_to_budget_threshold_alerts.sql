-- F08.7 Phase 10-α: budget_threshold_alerts.workflow_request_id への FK 追加
-- 設計書 F08.7 (v1.2 / v1.3 で履歴追記) §5.5 / §12.4 / §13 (Phase 10-α) に準拠。
--
-- 経緯:
-- - V11.033 で workflow_request_id カラムは既に作成済（NULL 許容）
-- - 当時は workflow_requests テーブル（V5.031）への FK 追加を「F05.6 完成度に応じて将来追加」と保留
-- - Phase 10-α で F05.6 ワークフロー本格起動を実装するに当たり、参照整合性を担保するため FK を追加
--
-- ON DELETE SET NULL:
-- - workflow_requests が論理削除（削除フラグ運用）の場合は影響なし
-- - 物理削除が発生しても警告履歴の他フィールド（threshold_percent, triggered_at 等）は残置し、
--   workflow_request_id のみ NULL に戻すことで監査履歴を保護する（設計書 §5.5 通り）

ALTER TABLE budget_threshold_alerts
    ADD CONSTRAINT fk_bta_workflow_request
        FOREIGN KEY (workflow_request_id)
        REFERENCES workflow_requests (id)
        ON DELETE SET NULL;
