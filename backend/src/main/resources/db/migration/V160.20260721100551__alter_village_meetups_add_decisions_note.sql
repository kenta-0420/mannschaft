-- F17.2 Wave1 ②寄合後半戦: 「決まったこと」列追加（village_meetups ALTER）
-- 寄合1件につき1本の自由記述メモで足りるため、別テーブルより親列を採用
-- （docs/features/F17.2_village_events_activation.md §4.2.4・比較の結論）。
-- 同時編集は既存 village_meetups の楽観ロック（version）に相乗りする。
-- Expand方針: 既存行への影響なし（NULL 許容の列追加のみ）。

ALTER TABLE village_meetups
  ADD COLUMN decisions_note TEXT NULL COMMENT '決まったこと（幹事が記す自由記述メモ）' AFTER location;
