-- F03.4.5 §5（予約対象の呼称チーム設定化）: reservation_team_settings に呼称カラムを追加する ALTER。
--
-- resource_name_type: 呼称プリセット（アプリ層 enum ReservationResourceNameType で検証）。
--   マスター確定プリセット STAFF/SEAT/COURT/BED/LANE ＋ 自由入力 CUSTOM の 6 種に加え、
--   NOT NULL DEFAULT 'DEFAULT' により既存行・未設定チームは自動的に「予約対象」表示の
--   後方互換フォールバックへ充足される（プリセット6種の変更ではない・設計判断）。
-- resource_name_custom: CUSTOM 選択時の自由入力呼称（30文字上限・UI幅由来）。
--   CUSTOM 以外では常に NULL に正規化する（アプリ層の Service で保証）。
--
-- ⚠ 採番はマージ時点の origin/main 全体の最大 major + 1 で確定する（暫定 V146・観測時最大 V145）。
ALTER TABLE reservation_team_settings
    ADD COLUMN resource_name_type   VARCHAR(10) NOT NULL DEFAULT 'DEFAULT' AFTER allow_public_reservation,
    ADD COLUMN resource_name_custom VARCHAR(30) NULL                       AFTER resource_name_type;
