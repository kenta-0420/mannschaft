-- F14.3 住民ライフイベント（逝去・転出）アーカイブ Phase 1: resident_registry 拡張
--
-- 設計書: docs/features/F14.3_resident_life_events.md §5.2.0.1 / §14 M-3
--
-- 転出（move_out_date）には実行者・日時が残らないため、archived_by の再導出が
-- 原理的に不可能だった（版1.2 の「変更なし」を撤回）。逝去側の
-- death_status_changed_at / death_status_changed_by と対称に列を追加する。
--
-- クロスドメイン FK は張らない（move_out_changed_by は users への ID 参照のみ）。
-- 既存行は NULL のまま。バックフィルはしない（推測で埋めると記録として嘘になる）。

ALTER TABLE resident_registry
    ADD COLUMN move_out_changed_by BIGINT UNSIGNED NULL,
    ADD COLUMN move_out_changed_at DATETIME(3) NULL;
