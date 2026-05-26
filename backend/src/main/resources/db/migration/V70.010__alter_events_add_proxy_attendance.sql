-- F03.10 代理出席: events テーブルに代理出席許可フラグを追加する。
-- 設計書: docs/features/F03.10_proxy_attendance.md §3.2
--
-- allow_proxy_attendance = TRUE のイベントのみ代理出席機能が有効になる。
-- is_proxy_auto_accept = TRUE の場合、代理指定時に即 ACCEPTED となり代理人の承認は不要。
ALTER TABLE events
  ADD COLUMN allow_proxy_attendance BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT '代理出席を許可するか',
  ADD COLUMN is_proxy_auto_accept   BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT '代理人の承認不要（TRUE = 指定時に即 ACCEPTED）';
