-- F17.2 追補: 寄合の任意定員（capacity）列を追加（village_meetups ALTER）。
-- capacity は「GOING（行く）出欠の受け入れ上限」。NULL=無制限。
-- MAYBE/ABSENT は定員制約の対象外（capacity は GOING のみに効く・設計確定仕様）。
-- 満席で新規 GOING は VILLAGE_103（MEETUP_CAPACITY_FULL・409）で拒否する。
-- 採番根拠: origin/main 現最大 major=162（V162 beta_grants 系）の次として major=163 を採る
--   （minor はタイムスタンプ必須・連番禁止。FlywayTimestampNamingGuardTest が機械的に拒否）。
-- Expand方針: 既存行への影響なし（NULL 許容の列追加のみ）。既存寄合は capacity=NULL=無制限として扱う。

ALTER TABLE village_meetups
  ADD COLUMN capacity INT NULL COMMENT 'GOING出欠の定員（NULL=無制限・GOINGのみに効く）' AFTER decisions_note;
