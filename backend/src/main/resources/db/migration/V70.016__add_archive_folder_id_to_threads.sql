-- F05.1 掲示板: bulletin_threads に archive_folder_id（保管庫フォルダ所属）カラムを追加する。
-- 設計書: docs/features/F05.1_bulletin_board.md §3 bulletin_threads / §7
--
-- archive_folder_id は bulletin_archive_folders.id（BINARY(16) UUIDv7）を参照するが、
-- bulletin 慣習（FK 最小・参照整合性はアプリ層で保証）に揃え DB レベルの FK 制約は設けない。
-- インデックス idx_bulletin_threads_archive_folder のみ追加する。
--
-- NULL かつ is_archived = TRUE = 保管庫直下（未分類）。is_archived = FALSE のときは無視（NULL 運用）。
-- スレッドは 1 フォルダのみ所属。
--
-- 採番順序: V70.015（bulletin_archive_folders テーブル作成）の後に実行されること
--           （カラム値が参照する先のテーブルが存在している状態にしておく）。
ALTER TABLE bulletin_threads
    ADD COLUMN archive_folder_id BINARY(16) NULL
        COMMENT '保管庫フォルダ（bulletin_archive_folders.id）。NULL かつ is_archived=TRUE = 保管庫直下（未分類）。FK なし'
        AFTER is_archived;

-- 保管庫フォルダ別スレッド一覧の取得を高速化する
CREATE INDEX idx_bulletin_threads_archive_folder
    ON bulletin_threads (archive_folder_id);

