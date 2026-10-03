-- F04.3 ハイブリッド方式（2026-06-21 改訂）: 通知種別設定にチャネル個別（Dual）制御カラムを追加。
--
-- channel_override = FALSE（既定）: 単一モード（is_enabled で受信可否）。配信チャネルは
--   優先度自動配信（notification_settings.priority_auto_delivery）に従う。
-- channel_override = TRUE: Dual モード（in_app_enabled / push_enabled でチャネルを直接制御）。
--
-- 既存行は既定値（channel_override=FALSE / in_app_enabled=TRUE / push_enabled=TRUE）で無傷。
-- 現行の単一 ON/OFF 挙動と完全に後方互換。
--
-- ⚠️ 採番注意: V123 で作成。マージ前に origin/main 全体の最大 major + 1 へリネームが必要。

ALTER TABLE notification_type_preferences
    ADD COLUMN channel_override BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'チャネル個別（Dual）モードに展開しているか。FALSE=単一モード、TRUE=Dualモード' AFTER is_enabled,
    ADD COLUMN in_app_enabled   BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'Dualモード時のアプリ内（WebSocket）配信の可否' AFTER channel_override,
    ADD COLUMN push_enabled     BOOLEAN NOT NULL DEFAULT TRUE
        COMMENT 'Dualモード時のプッシュ（PWA Push）配信の可否' AFTER in_app_enabled;
