-- F03.4 残ギャップMVP 予約ポリシー基盤:
--   (1) チーム単位の予約既定設定テーブル reservation_policies を新設する。
--   (2) reservation_slots.approval_mode を NULL 許容化し、既存行を全て NULL へ backfill する。
--
-- 承認モード(approval_mode)の解決ルール（マスター御裁可）:
--   「枠(slot)の値があればそれ／無ければチーム設定(reservation_policies)／それも無ければ AUTO」。
--   = チーム既定 ＋ 枠で上書き。slot.approval_mode = NULL は「チーム設定に従う」を意味する。
--
-- 主キーは UUIDv7 (BINARY(16)) — アーキ原則6に従い時刻順ソート可能UUID。
-- team_id は teams ドメインへのクロスドメイン参照なので FK なし、インデックスのみ（アーキ原則1）。
-- reservation_team_settings（allow_public_reservation 専用）とは別テーブルとして分離維持する。

-- ===== (1) reservation_policies 新設（1チーム1行）=====
CREATE TABLE reservation_policies (
    id                   BINARY(16)   NOT NULL,
    team_id              BIGINT       NOT NULL,
    -- 承認モード既定値。AUTO=自動承認 / MANUAL=管理者の手動承認。
    approval_mode        VARCHAR(10)  NOT NULL DEFAULT 'AUTO',
    -- キャンセル受付の締切（予約開始の何時間前まで）。MVP では保持のみ（配線は後続）。
    cancel_deadline_hours INT         NOT NULL DEFAULT 24,
    -- リマインド送信タイミング（予約開始の何時間前か）の CSV。⑥リマインドで使用（保持のみ）。
    remind_before_hours  VARCHAR(64)  NOT NULL DEFAULT '24,1',
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),
    UNIQUE KEY uq_reservation_policies_team_id (team_id),
    INDEX idx_reservation_policies_team_id (team_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='チームごとの予約既定ポリシー（1チーム1行・承認モード/キャンセル締切/リマインド既定）';

-- ===== (2) reservation_slots.approval_mode を NULL 許容化 ＋ 既存行を全て NULL へ backfill =====
-- 旧定義: approval_mode ENUM('AUTO','MANUAL') NOT NULL DEFAULT 'AUTO'（V3.151）。
-- ENUM のため CHECK 制約は無く、DROP CHECK → UPDATE → 再付与の順序は不要。
-- MODIFY で NOT NULL/DEFAULT を外し NULL 許容にしてから、既存行を NULL へ backfill する。
--
-- 既存行の値（死蔵の初期値 AUTO）は一度も自動確定に使われていない（slot.approval_mode を
-- 設定する作成 API・参照ロジックは現状存在せず、全行が初期値 AUTO のまま）。
-- 全て NULL へ寄せても「チーム設定に従う＝チーム未設定なら AUTO」となり、従来の挙動（AUTO 相当・
-- ただし実際には自動確定処理は未配線）から後退しない（挙動後退ゼロ）。
ALTER TABLE reservation_slots
  MODIFY COLUMN approval_mode ENUM('AUTO', 'MANUAL') NULL DEFAULT NULL;

-- 既存行を全て NULL へ backfill（NULL = チーム設定に従う）。
-- ※ from-scratch（空テーブル）では 0 行更新で素通りするため、既存データ番人テストで担保する。
UPDATE reservation_slots SET approval_mode = NULL;
