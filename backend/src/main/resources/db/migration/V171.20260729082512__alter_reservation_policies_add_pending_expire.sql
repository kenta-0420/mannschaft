-- F03.4.5 §6.3（W2-6）: 仮押さえ(PENDING)の自動失効 — チーム設定カラムを追加する。
--
-- MANUAL 承認チームで承認されないまま放置された PENDING が予約枠を塞ぎ続ける問題への対処。
-- 「何時間で自動キャンセルするか」をチーム単位で持たせる。
--
-- 値の意味（マスター確定「チーム設定・既定24h」・設計書 §6.3）:
--   NULL          … 自動失効しない（管理者が明示的に無効化した状態）
--   1〜168        … その時間数を経過した PENDING を自動キャンセルする（アプリ層 @Min/@Max で検証）
--   DEFAULT 24    … 新規行・既存行とも既定 24 時間
--
-- 既存行の充足について:
--   本 ALTER は backfill UPDATE を行わない。MySQL の ADD COLUMN は「NULL 許容 + DEFAULT 付き」の場合、
--   既存行に DEFAULT 値を充填する（NULL ではなく 24 が入る）。これにより「既存チームも既定 24 時間で
--   自動失効が効く」というマスター確定の要件が ALTER 一発で満たされる。
--   この挙動は from-scratch 既存行番人テスト
--   （FlywayExistingDataReservationPoliciesPendingExpireMigrationTest）で機械的に検証する。
--
-- 範囲制約（1〜168）はアプリ層（UpdateReservationSettingRequest の @Min(1)/@Max(168)）を一次防御とし、
-- DB では CHECK を付けない。NULL（無効化）と共存させる CHECK は MySQL 8.0 で書けるが、
-- 既存の reservation_policies が cancel_deadline_hours 等で CHECK を持たない作法に合わせ、
-- 制約層を一箇所（アプリ層）に集約して二重管理を避ける。

ALTER TABLE reservation_policies
    ADD COLUMN pending_expire_hours INT NULL DEFAULT 24
        COMMENT '仮押さえ(PENDING)の自動失効までの時間数。NULL=自動失効しない。既定24時間（F03.4.5 §6.3）'
        AFTER remind_before_hours;
