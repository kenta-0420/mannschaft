-- F03.4.3 機能G: reservations へ予約グループ（group_id 兄弟行方式・案(b)）と選択メニューを追加する。
--
-- 後方互換（§3.2）:
--   group_id / menu_id は NULL 許容・デフォルトなしのため既存行は NULL のまま
--   （NULL = 単枠予約 = 既存互換。backfill しない）。
--   is_group_primary は NOT NULL DEFAULT TRUE — 既存行（単枠）は DEFAULT で自動充足され、
--   一覧・統計の「代表行絞り（is_group_primary = TRUE）」追加後も従来と同件数を返す（挙動後退ゼロ）。
--
-- group_id はアプリ層で UUIDv7 を採番する論理グループ（専用親テーブルは作らない = 案(b)・§3.1）。
-- menu_id の FK は同一 reservation ドメイン内（アーキ原則1の対象外）:
--   menu_id → reservation_menus ON DELETE RESTRICT
--   （メニューは論理削除運用のため実質発火しない。F03.4.1 §4「参照中メニューは物理削除しない」と整合）
--
-- 不変条件（§3.2）: 同一 group_id の行集合には is_group_primary=TRUE がちょうど 1 行存在する。
-- 作成トランザクション（ReservationGroupService.createGroup）が構造的に成立させる（DB 制約では表現しない）。

ALTER TABLE reservations
    ADD COLUMN group_id BINARY(16) NULL AFTER user_id,
    ADD COLUMN menu_id BINARY(16) NULL AFTER group_id,
    ADD COLUMN is_group_primary BOOLEAN NOT NULL DEFAULT TRUE AFTER menu_id,
    ADD CONSTRAINT fk_rv_menu
        FOREIGN KEY (menu_id) REFERENCES reservation_menus (id) ON DELETE RESTRICT,
    ADD INDEX idx_rv_group (group_id),
    ADD INDEX idx_rv_user_primary (user_id, is_group_primary, status);
