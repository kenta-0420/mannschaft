-- =====================================================================
-- F17.1 ②-1: 村ニュースレター「内容モデル」— 号テーブル群（案Y・村ドメイン独立・UUIDv7 ネイティブ）
-- =====================================================================
-- 設計書: docs/features/F17.1_village_newsletter_content_model.md
--          §4.2（号テーブル）/ §4.5（送信ログ拡張）/ §4.3（集計日/配信日）/ §4.7（タグ2表＋公開ラダー）
--          §12 の ②-1 行 / §13（Flyway 採番方針）
--
-- 目的:
--   1. village_newsletter_issues 新設 … 集計→凍結→ラグ→配信を 1 号として持つ。
--      改ざん不可の digest_* snapshot 列・村長コメント欄・状態列・pull 層(title/visibility)。
--   2. village_newsletter_tags / village_newsletter_issue_tags 新設 … 案Y の pull 層。
--      両側 UUIDv7＝Long の壁を越えない（設計書 §3A.3・§4.7）。村ドメイン内で完結。
--   3. village_newsletters に集計日/配信日カラムを ALTER ADD（現行ハードコードを設定制へ・§4.3）。
--   4. village_newsletter_send_logs に issue_id を追加（号→配信結果を辿れる・§4.5）。
--
-- 原則準拠（CLAUDE.md）:
--   原則1: comment_updated_by(user_id) にはクロスドメイン FK を張らない。
--   原則2: village_id / issue_id / tag_id の FK は全て村ドメイン内のため CASCADE 可。
--          既存村テーブル群（V9.126〜V9.154 / V153）が例外なく
--          `FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE` を張る作法に倣う。
--   原則3: 号・タグとも deleted_at で論理削除。
--   原則6: 新規テーブルの PK は UUIDv7 BINARY(16)。
--   原則7 適用外: 村は organization_id を持たない全テナント横断ドメイン
--                （V9.147:4 / V153:37 の先例と同じ根拠）。
--
-- ---------------------------------------------------------------------
-- 【最重要】論理削除の時限爆弾（設計書 §4 / タスク指示・先例 V153 §5.4）
-- ---------------------------------------------------------------------
-- village_newsletters へ集計日/配信日を追加し NOT NULL 化する。NOT NULL 制約は
-- 「論理削除済みの行」にも適用される（DB は deleted_at を知らない）。したがって
-- 既存行のバックフィルを deleted_at IS NULL で絞ると、論理削除済みの設定行が
-- NULL のまま残り、直後の MODIFY ... NOT NULL が確実に失敗する（時限爆弾）。
--   → 本マイグレーションのバックフィル UPDATE は deleted_at で絞らない（全行を埋める）。
--   → 番人テスト FlywayExistingDataVillageNewsletterIssuesMigrationTest が
--      論理削除済みの設定行まで埋まっていることを機械的に検証する。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) village_newsletter_issues: 号（集計→凍結→ラグ→配信の 1 単位）
-- ---------------------------------------------------------------------
CREATE TABLE village_newsletter_issues (
    id                       BINARY(16)      NOT NULL                COMMENT 'UUIDv7 PK（原則6）',
    village_id               BINARY(16)      NOT NULL                COMMENT 'FK → villages.id（同一ドメイン CASCADE）',
    newsletter_id            BINARY(16)      NULL                    COMMENT 'village_newsletters.id。号外は NULL。FK は張らない（設定削除で号 archive を巻き込まない）',
    frequency                VARCHAR(20)     NULL                    COMMENT 'WEEKLY / MONTHLY。EXTRA(号外)では NULL',
    issue_type               VARCHAR(20)     NOT NULL DEFAULT 'REGULAR' COMMENT 'REGULAR / EXTRA',
    status                   VARCHAR(20)     NOT NULL DEFAULT 'AGGREGATED' COMMENT 'AGGREGATED→FROZEN→PUBLISHED→CANCELED',
    -- pull 層（案Y・ためる/公開一覧）
    title                    VARCHAR(200)    NOT NULL                COMMENT '号タイトル（既定は自動生成・村長編集可）',
    visibility               VARCHAR(30)     NOT NULL DEFAULT 'VILLAGE_MEMBERS' COMMENT 'VILLAGE_MEMBERS / PUBLIC（§4.7.2）',
    -- 集計対象期間（凍結時に確定）
    period_start             DATETIME(6)     NOT NULL                COMMENT '集計対象期間の開始 [from',
    period_end               DATETIME(6)     NOT NULL                COMMENT '集計対象期間の終了 to)',
    aggregated_at            DATETIME(6)     NULL                    COMMENT '集計・凍結を実施した時刻',
    scheduled_publish_at     DATETIME(6)     NULL                    COMMENT '配信予定（ラグの終端）',
    published_at             DATETIME(6)     NULL                    COMMENT '実配信時刻',
    -- 凍結ダイジェスト snapshot（凍結後は不変・改ざん防止の核心。Entity 側は setter を設けない）
    digest_post_count        INT UNSIGNED    NOT NULL DEFAULT 0,
    digest_new_member_count  INT UNSIGNED    NOT NULL DEFAULT 0,
    digest_festival_count    INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '§5.3（採否 §14 Q1）',
    digest_meetup_count      INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '§5.3',
    digest_recruit_count     INT UNSIGNED    NOT NULL DEFAULT 0      COMMENT '§5.3',
    digest_topic_1_name      VARCHAR(100)    NULL,
    digest_topic_1_count     INT UNSIGNED    NOT NULL DEFAULT 0,
    digest_topic_2_name      VARCHAR(100)    NULL,
    digest_topic_2_count     INT UNSIGNED    NOT NULL DEFAULT 0,
    digest_topic_3_name      VARCHAR(100)    NULL,
    digest_topic_3_count     INT UNSIGNED    NOT NULL DEFAULT 0,
    -- 村長コメント（ダイジェストとは別欄・凍結後も編集可・楽観ロック §4.4。号外では本文本体）
    headman_comment          TEXT            NULL,
    comment_updated_by       BIGINT UNSIGNED NULL                    COMMENT 'user_id（FK 張らない・原則1）',
    comment_updated_at       DATETIME(6)     NULL,
    -- 監査
    deleted_at               DATETIME(6)     NULL                    COMMENT '論理削除（原則3）',
    created_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at               DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version                  BIGINT          NOT NULL DEFAULT 0      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    -- 同一村×頻度×期間は 1 号（冪等・集計バッチ二重起動対策）。
    -- 号外は frequency=NULL のため MySQL の UNIQUE は複数 NULL を衝突させず、period_start=created_at で一意化（§10）。
    UNIQUE KEY uk_vni_village_period (village_id, frequency, period_start),
    KEY idx_vni_status_publish (status, scheduled_publish_at),
    KEY idx_vni_village_created (village_id, created_at),
    KEY idx_vni_public_published (visibility, status, published_at),
    CONSTRAINT fk_vni_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='村ニュースレター号（F17.1 ②-1）';

-- ---------------------------------------------------------------------
-- 2) village_newsletter_tags: 村ごとのタグマスタ（案Y・ブログ BlogTagEntity を金型に UUIDv7 で新設）
-- ---------------------------------------------------------------------
-- 同名重複防止に partial unique（WHERE deleted_at IS NULL）は MySQL 不可のため Service 層で判定する
-- （V153:46 / V19.007:4 の先例に従う）。
CREATE TABLE village_newsletter_tags (
    id           BINARY(16)  NOT NULL                COMMENT 'UUIDv7 PK（原則6）',
    village_id   BINARY(16)  NOT NULL                COMMENT 'FK → villages.id（同一ドメイン CASCADE）',
    name         VARCHAR(50) NOT NULL,
    color        VARCHAR(7)  NOT NULL DEFAULT '#6B7280' COMMENT '#RRGGBB（ブログ既定色に倣う）',
    sort_order   INT         NOT NULL DEFAULT 0,
    deleted_at   DATETIME(6) NULL                    COMMENT '論理削除（原則3）',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    version      BIGINT      NOT NULL DEFAULT 0      COMMENT '楽観ロック',
    PRIMARY KEY (id),
    KEY idx_vnt_village (village_id, sort_order),
    CONSTRAINT fk_vnt_village FOREIGN KEY (village_id) REFERENCES villages(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='村ニュースレタータグマスタ（F17.1 ②-1・案Y）';

-- ---------------------------------------------------------------------
-- 3) village_newsletter_issue_tags: 号×タグ中間表（両側 UUIDv7・村ドメイン内で完結）
-- ---------------------------------------------------------------------
-- 原則6（EntityUuidV7ConventionArchTest D-2b で機械的に強制）に従い、新規中間表も UUIDv7
-- サロゲート PK を持つ。リンクの一意性は UNIQUE (issue_id, tag_id) で担保する（両側 UUID＝Long 壁は
-- 越えない）。設計書 §4.7.1 は複合 PK を提示するが、既存中間表（blog_post_tags 等）は enforcement
-- 導入前の凍結免除であり、新規テーブルは UUIDv7 継承が必須のため surrogate PK とする。
CREATE TABLE village_newsletter_issue_tags (
    id           BINARY(16)  NOT NULL                COMMENT 'UUIDv7 PK（原則6）',
    issue_id     BINARY(16)  NOT NULL                COMMENT 'FK → village_newsletter_issues.id（同一ドメイン CASCADE）',
    tag_id       BINARY(16)  NOT NULL                COMMENT 'FK → village_newsletter_tags.id（同一ドメイン CASCADE）',
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_vnit_issue_tag (issue_id, tag_id),
    KEY idx_vnit_tag (tag_id),
    CONSTRAINT fk_vnit_issue FOREIGN KEY (issue_id) REFERENCES village_newsletter_issues(id) ON DELETE CASCADE,
    CONSTRAINT fk_vnit_tag   FOREIGN KEY (tag_id)   REFERENCES village_newsletter_tags(id)   ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='号×タグ中間表（F17.1 ②-1・案Y）';

-- ---------------------------------------------------------------------
-- 4) village_newsletter_send_logs へ issue_id を追加（号→配信結果の追跡・§4.5）
-- ---------------------------------------------------------------------
-- 号モデル導入前の既存ログ行は NULL（後方互換）。FK は張らずアプリ整合（issue は号 archive で、
-- 送信ログの削除連鎖に巻き込みたくない）。
ALTER TABLE village_newsletter_send_logs
    ADD COLUMN issue_id BINARY(16) NULL COMMENT 'village_newsletter_issues.id（号モデル導入前は NULL）' AFTER newsletter_id,
    ADD KEY idx_vnsl_issue (issue_id);

-- ---------------------------------------------------------------------
-- 5) village_newsletters へ集計日/配信日カラムを追加（現行ハードコードの設定制化・§4.3）
-- ---------------------------------------------------------------------
-- Stage: Expand → Backfill(全行) → NOT NULL 化。まず NULL 可で追加する。
-- aggregate_day/dispatch_day の意味は frequency により解釈（曜日 or 日付・月末=0 の番兵値）。
ALTER TABLE village_newsletters
    ADD COLUMN aggregate_day TINYINT UNSIGNED NULL
        COMMENT 'WEEKLY:1-7(月=1) / MONTHLY:1-28, 0=月末' AFTER next_scheduled_at,
    ADD COLUMN dispatch_day  TINYINT UNSIGNED NULL
        COMMENT 'WEEKLY:1-7(月=1) / MONTHLY:1-28, 0=月末' AFTER aggregate_day,
    ADD COLUMN dispatch_hour TINYINT UNSIGNED NOT NULL DEFAULT 18
        COMMENT '配信時刻（UTC 時・0-23）' AFTER dispatch_day;

-- 既存行のバックフィル — 既存挙動を保存（設計書 §4.3）。
--   WEEKLY: 集計=月曜(1)/配信=金曜(5)  … 現行の週次配信は金曜。
--   MONTHLY: 集計=月末(0)/配信=月末(0) … 現行の月次配信は月末。
-- 【時限爆弾の回避】deleted_at IS NULL で絞らない（論理削除済みの設定行も必ず埋める）。
UPDATE village_newsletters
   SET aggregate_day = CASE frequency WHEN 'WEEKLY' THEN 1 ELSE 0 END,
       dispatch_day  = CASE frequency WHEN 'WEEKLY' THEN 5 ELSE 0 END
 WHERE aggregate_day IS NULL OR dispatch_day IS NULL;

-- NOT NULL 化。ここで論理削除済みの行が NULL のまま残っていれば MODIFY が失敗し、
-- migration ごと停止する（＝バックフィルの取りこぼしを黙って許さない自己完結の番人）。
ALTER TABLE village_newsletters
    MODIFY COLUMN aggregate_day TINYINT UNSIGNED NOT NULL
        COMMENT 'WEEKLY:1-7(月=1) / MONTHLY:1-28, 0=月末',
    MODIFY COLUMN dispatch_day  TINYINT UNSIGNED NOT NULL
        COMMENT 'WEEKLY:1-7(月=1) / MONTHLY:1-28, 0=月末';

-- 値域ガード（MySQL 8 は CHECK を強制する）。0=月末の番兵値を許すため下限 0。
-- 月次で 29〜31 を選ばせない制御は UI 側（§4.3）。ここでは日付上限 31 まで許容する。
ALTER TABLE village_newsletters
    ADD CONSTRAINT chk_vn_aggregate_day CHECK (aggregate_day BETWEEN 0 AND 31),
    ADD CONSTRAINT chk_vn_dispatch_day  CHECK (dispatch_day  BETWEEN 0 AND 31),
    ADD CONSTRAINT chk_vn_dispatch_hour CHECK (dispatch_hour BETWEEN 0 AND 23);
