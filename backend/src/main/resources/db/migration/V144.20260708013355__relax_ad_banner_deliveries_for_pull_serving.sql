-- F09.19.2: ad_banner_deliveries の意味論分離（予約 ≠ 表示）— 正本 §5.2 V144.004
--
-- 背景: 現行は AdBannerChannelService.deliver() が「配信予約時点」で ad_impressions を記録し
-- served_at を埋める（＝ユーザーが見ていないのに impression 計上）。pull 型サービング（§7.2 STEP 1）は
-- 「予約時 NULL → serve 時に実表示として充足」に改める。そのため ad_impression_id / served_at を NULL 許容化する。
--
-- 本弾（.2）に前倒しする理由: サービングの受け入れ条件（§16 F09.19.2 の AC-2.2 / AC-2.8 / AC-2.9）が
-- 「served_at IS NULL の未表示予約」を前提とするため、テスト（試練）が成立するには本緩和が必須。
-- 正本では V144.004 を .3 に配置しているが、.2 の試練成立のため本 migration で NULL 許容のみ先行する
-- （インデックス入れ替えは .3 の担当範囲に残し、共存時の衝突を避けるため本 migration では行わない）。
--
-- 既存データ番人テスト不要（NOT NULL → NULL 許容の緩和方向のみ・既存行は served_at 充足済みで整合）。
ALTER TABLE ad_banner_deliveries
    MODIFY COLUMN ad_impression_id BIGINT UNSIGNED NULL
        COMMENT 'F09.7 ad_impressions.id (FKなし)。実表示（serve）時に設定。予約時 NULL',
    MODIFY COLUMN served_at DATETIME NULL
        COMMENT 'バナー実表示時刻。予約時 NULL（NULL = 未表示予約）';
