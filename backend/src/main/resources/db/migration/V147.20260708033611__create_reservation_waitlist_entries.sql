-- F03.4.5 §6.1 D群: キャンセル待ち（reservation_waitlist_entries）を新設する。
--
-- 満席（FULL）枠に対して会員が登録し、キャンセルで空きに転じた瞬間に一斉通知される。
-- 主キーは UUIDv7 (BINARY(16)) — アーキ原則6（UuidV7Entity 継承）。
-- team_id / user_id はクロスドメイン参照のため FK なし（アーキ原則1）。
-- slot_id は同一 reservation ドメイン内のため FK あり（ON DELETE CASCADE — 枠が物理削除されたら待ちも消滅が正・原則2）。
-- 重複登録ガードはアプリ層（existsBySlotIdAndUserIdAndStatus(WAITING)）で行い、DB UNIQUE は張らない
--   （取消→再登録を恒久に塞がないため・§6.1 設計判断）。
-- 「期限切れ」は行として持たず、枠開始時刻経過で導出しクリーンアップバッチが物理削除する。

CREATE TABLE reservation_waitlist_entries (
    id           BINARY(16)       NOT NULL,
    team_id      BIGINT UNSIGNED  NOT NULL,
    slot_id      BIGINT UNSIGNED  NOT NULL,
    user_id      BIGINT UNSIGNED  NOT NULL,
    status       VARCHAR(20)      NOT NULL DEFAULT 'WAITING',
    notified_at  DATETIME(6)      NULL,
    created_at   DATETIME(6)      NOT NULL,
    updated_at   DATETIME(6)      NOT NULL,

    PRIMARY KEY (id),
    CONSTRAINT fk_rwe_slot
        FOREIGN KEY (slot_id) REFERENCES reservation_slots (id) ON DELETE CASCADE,
    INDEX idx_rwe_slot_status (slot_id, status),
    INDEX idx_rwe_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F03.4.5 キャンセル待ち（満席枠への登録・空き復帰で一斉通知・ユーザー10/枠50上限）';
