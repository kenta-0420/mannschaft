-- F03.4 機能D: 予約通知メール宛先テーブル reservation_notification_recipients を新設する。
--
-- チーム単位で登録する「予約通知メール宛先」。メンバーの予約が成立するたびに、
-- ここに登録された任意のメールアドレス（非ユーザー＝店の代表アドレス等でも可）へ
-- 「日時＋メニュー＋予約者名」をメール送信する（ReservationRecipientEmailEventListener）。
-- フリーミアム（無料は最大3件・有料で最大10件）で件数をゲートする（BE 強制・ReservationNotificationRecipientService）。
--
-- 主キーは UUIDv7 (BINARY(16)) — アーキ原則6に従い時刻順ソート可能UUID（新規テーブル）。
-- team_id / created_by は teams / users ドメインへのクロスドメイン参照なので FK なし・INDEX のみ（アーキ原則1）。
-- 参照整合性はアプリ層で保証する。
--
-- 論理削除は持たない（宛先は物理削除 or is_enabled=FALSE で無効化）。
-- UNIQUE(team_id, email) により同一チーム内でのメール重複を DB レベルでも拒否する
--   （アプリ層でも事前に 409 = RESERVATION_030。DB は最終防御）。
--
-- ⚠ 採番: origin/main 現行最大 major は V136（観測時点 2026-07-03）。機能B（予約不可枠・別 PR）と
--   異なる major を採るため、D は V138 を使用する（B は V137 想定）。マージ直前に origin/main の最大
--   major を再確認し、衝突があればリネームすること（CLAUDE.md「Flyway 採番」）。

CREATE TABLE reservation_notification_recipients (
    id          BINARY(16)   NOT NULL,
    team_id     BIGINT       NOT NULL,
    -- 通知先メールアドレス（@Email 検証・非ユーザー可）。
    email       VARCHAR(255) NOT NULL,
    -- 宛先ラベル（例:「店代表」「予約担当」）。任意。
    label       VARCHAR(100) NULL,
    -- 有効/無効。FALSE の宛先には送らない。件数ゲートは有効・無効を問わず全登録行で数える。
    is_enabled  BOOLEAN      NOT NULL DEFAULT TRUE,
    -- 登録者 user_id（users テーブルへのクロスドメイン参照・FK なし）。
    created_by  BIGINT       NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    -- 同一チーム内でメール重複禁止（RESERVATION_030 の DB 最終防御）。
    UNIQUE KEY uq_rnr_team_email (team_id, email),
    -- チーム別宛先一覧・件数カウント。
    INDEX idx_rnr_team (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='予約通知メール宛先（機能D・チーム単位・非ユーザー可・フリーミアム件数ゲート付き）';
