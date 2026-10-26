-- F03.10 代理出席: event_checkins テーブルに代理チェックイン委任参照カラムを追加する。
-- 設計書: docs/features/F03.10_proxy_attendance.md §3.5
--
-- delegation_id は代理チェックイン時の event_delegations.id を保持する（通常チェックイン時は NULL）。
-- クロスドメインではなく event ドメイン内の参照だが、event_delegations は UUIDv7 主キーのため
-- delegation_id は BINARY(16)。二重チェックイン防止はアプリ層 + idx_ec_delegation での存在チェックで保証する
-- （MySQL では NOT NULL 行のみの部分ユニークが表現しづらいため。§3.5 / §4.2 / §5.7）。
ALTER TABLE event_checkins
  ADD COLUMN delegation_id BINARY(16)
    COMMENT '代理チェックイン時の event_delegations.id（通常チェックイン時は NULL）',
  ADD INDEX idx_ec_delegation (delegation_id);
