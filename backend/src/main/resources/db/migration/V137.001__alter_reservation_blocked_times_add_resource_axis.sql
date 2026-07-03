-- F03.4 機能B（§3.B / §7）: 予約不可枠（reservation_blocked_times）に対象軸カラムを追加する ALTER。
--
-- resource_type: 対象軸。TEAM（全 slot）/ STAFF（resource_id の担当スタッフの slot のみ）。
--   NOT NULL DEFAULT 'TEAM' により既存行は自動的に TEAM（全 slot 対象）へ後方互換フォールバックする。
--   将来 LINE / RESOURCE を enum 拡張点として確保（VARCHAR で保持し、アプリ層の enum で検証）。
-- resource_id: STAFF 時の対象スタッフ user_id（users ドメイン参照・クロスドメインFKなし＝INDEX のみ）。
--   型は reservation_slots.staff_user_id（BIGINT UNSIGNED）に厳密一致させる。
--
-- 予約不可枠は slot を CLOSED に永続化せず runtime overlap（§5.B）で判定するため、本 ALTER で
-- reservation_slots には一切手を触れない。既存行の挙動後退はゼロ。
--
-- ⚠ 採番はマージ時点の origin/main 全体の最大 major + 1 で確定する（暫定 V137・観測時最大 V136）。
ALTER TABLE reservation_blocked_times
    ADD COLUMN resource_type VARCHAR(20)     NOT NULL DEFAULT 'TEAM' AFTER reason,
    ADD COLUMN resource_id   BIGINT UNSIGNED NULL                    AFTER resource_type;

-- 対象軸込みの予約判定ルックアップ用インデックス（§3.B）。
CREATE INDEX idx_bt_lookup
    ON reservation_blocked_times (team_id, blocked_date, resource_type, resource_id);
