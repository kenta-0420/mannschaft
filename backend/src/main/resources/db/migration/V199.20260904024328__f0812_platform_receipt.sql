-- F08.12: 運営領収書・適格請求書発行（PLATFORM スコープ）
--
-- 本ファイルは F08.12 第1段の DDL を 1 本に集約したものである。
-- scope_type / scope_id は VARCHAR(20) / BIGINT UNSIGNED であり ENUM ではないため、
-- PLATFORM 値の追加そのものに DDL 変更は不要（§3.0）。
--
-- 【重要】統合テストのスキーマは Flyway ではなく Entity から生成される
-- （application-test.yml: ddl-auto=create / flyway.enabled=false）。
-- 本ファイルの生成列・UNIQUE は ReceiptEntity 側にも同じ内容で宣言してある。
-- 片方だけを変更すると「CI 緑・本番で重複が通る」偽陰性になる。必ず両方を揃えること。

-- ─────────────────────────────────────────────────────────────
-- 1. receipts: 元データ参照の汎用化（§3.1）
--    member_payment_id は既存互換のため残す。
--    クロスドメイン FK は張らない（設計原則 1）。
-- ─────────────────────────────────────────────────────────────
ALTER TABLE receipts
  ADD COLUMN source_type VARCHAR(30) NULL COMMENT '元データ種別（MEMBER_PAYMENT/AD_INVOICE/NOTIFICATION_CREDIT_PURCHASE/BILLING_INVOICE/MANUAL）',
  ADD COLUMN source_ref  VARCHAR(64) NULL COMMENT '元データIDの文字列表現（FK なし・アプリ層で整合性保証。BIGINT は10進、UUID は小文字36文字）',
  ADD INDEX idx_r_source (source_type, source_ref);

-- ─────────────────────────────────────────────────────────────
-- 2. receipts: PDF の生成・保存状態（§3.1）
--    既存行には DEFAULT 'GENERATING' が入る。既存の団体領収書は pdf_storage_key が
--    1 行も書かれていないため、これは現状の導出結果と同じ値であり挙動は変わらない。
-- ─────────────────────────────────────────────────────────────
ALTER TABLE receipts
  ADD COLUMN pdf_status         VARCHAR(20)  NOT NULL DEFAULT 'GENERATING' COMMENT 'PDF生成状態（GENERATING/READY/FAILED）',
  ADD COLUMN pdf_attempt_count  INT UNSIGNED NOT NULL DEFAULT 0            COMMENT 'PDF生成・保存の試行回数（失敗時に加算）',
  ADD COLUMN pdf_failed_at      DATETIME     NULL                          COMMENT '直近の失敗時刻',
  ADD COLUMN pdf_failure_reason VARCHAR(500) NULL                          COMMENT '直近の失敗理由（エラーコード+要約。スタックトレースは入れない）',
  ADD INDEX idx_r_pdf_status (pdf_status, updated_at);

-- pdf_storage_key の意味を「原本（ORIGINAL）の storage_key」に確定する（§3.4.1）。
-- VOIDED のキーは絶対に書き込まない（上書きで原本が取得できなくなる事故を設計で塞ぐ）。
ALTER TABLE receipts
  MODIFY COLUMN pdf_storage_key VARCHAR(500) NULL COMMENT '原本（ORIGINAL）PDF のストレージキー。取得の正は receipt_pdf_archives 側であり本列は冗長キャッシュ。VOIDED のキーは書き込まない';

-- ─────────────────────────────────────────────────────────────
-- 3. receipts: 運営領収書の重複発行を原子的に防ぐ（§3.1）
--
--    要件: 同一 (source_type, source_ref) に対して有効な領収書は 1 通まで。
--          ただし無効化されたものは何通あってもよい（無効化 → 再発行を繰り返せる）。
--
--    MySQL に部分インデックスが無いため、STORED 生成列 + UNIQUE で表現する。
--    UNIQUE 索引は NULL を重複とみなさないので、
--      - 有効な PLATFORM 行  … キーが値を持つ  → 同一 source では 1 行しか入らない
--      - 無効化された行      … voided_at が入った瞬間 NULL に再計算 → 何通でも並ぶ
--      - TEAM / ORGANIZATION … 常に NULL       → F08.4 の分割領収書を一切壊さない
--
--    条件から scope_type を外してはならない（外すと団体の分割領収書が壊れる）。
--    区切りの 0x1F（Unit Separator）は、素朴な連結だと "AD"+"1_2" と "AD_1"+"2" が
--    同じ文字列になりうるため。
--
--    V60.004 の NOTE は「FK 列は生成列の式に使えない」と記録しているが、あの制限は
--    ON DELETE SET NULL / CASCADE を伴う FK 列に対するものである。receipts の FK は
--    recipient_user_id / issued_by / voided_by の 3 本で ON DELETE 指定が無く、
--    しかも本式が参照する 4 列はいずれも FK 列ではない。
-- ─────────────────────────────────────────────────────────────
ALTER TABLE receipts
  ADD COLUMN active_platform_source_key VARCHAR(110)
    GENERATED ALWAYS AS (
      CASE WHEN scope_type = 'PLATFORM'
                AND voided_at IS NULL
                AND source_type IS NOT NULL
                AND source_ref IS NOT NULL
           THEN CONCAT(source_type, 0x1F, source_ref)
           ELSE NULL END
    ) STORED
    COMMENT '有効なPLATFORM領収書の重複防止キー。無効化・非PLATFORM・source未設定はNULL',
  ADD UNIQUE KEY uq_r_active_platform_source (active_platform_source_key);

-- PLATFORM 領収書は source を必ず持つ（§4.1 の電子帳簿保存法「取引先」検索要件のため）。
-- source が NULL の運営領収書が生まれると、その 1 件だけ取引先で検索できなくなる。
--
-- 【CHECK 制約を使わない理由（CI 実測で判明）】
-- MySQL は CHECK 違反を エラー 3819 / SQLSTATE 'HY000' で返す。HY000 は汎用コードであり、
-- Spring の例外変換は UncategorizedSQLException にしか落とせない。そのためアプリ層
-- （PlatformReceiptIssueService の DataIntegrityViolationException 捕捉）が整合性違反として
-- 扱えず、並行発行の競合と区別できなくなる。
-- 一方 NOT NULL 違反は エラー 1048 / SQLSTATE '23000'（integrity constraint violation）で
-- 返るため正しく分類される。よって「条件付き NOT NULL」を STORED 生成列 + NOT NULL で表現する。
-- 違反行は式が NULL に評価され、NOT NULL 違反として INSERT が拒否される。
ALTER TABLE receipts
  ADD COLUMN platform_source_present TINYINT UNSIGNED
    GENERATED ALWAYS AS (
      CASE WHEN scope_type = 'PLATFORM'
                AND (source_type IS NULL OR source_ref IS NULL)
           THEN NULL ELSE 1 END
    ) STORED NOT NULL
    COMMENT 'PLATFORM領収書がsourceを持つことを強制する番人。違反行は式がNULLになりNOT NULL違反で拒否される';

-- ─────────────────────────────────────────────────────────────
-- 4. receipt_number_sequences: 採番の直列化解消（§3.2）
--
--    従来の採番は発行者設定行そのものを PESSIMISTIC_WRITE でロックしていた。
--    団体スコープは行がテナントごとに分かれるため実害が小さいが、PLATFORM は
--    全プラットフォームで 1 行であり、月次一括発行が全件直列化する。
--
--    本表は PLATFORM スコープに先行適用する。団体スコープは
--    receipt_issuer_settings.next_receipt_number 方式のまま据え置く（後方互換。§8）。
-- ─────────────────────────────────────────────────────────────
CREATE TABLE receipt_number_sequences (
    id          BINARY(16)      NOT NULL COMMENT 'PK（UUIDv7）',
    scope_type  VARCHAR(20)     NOT NULL COMMENT 'スコープ（PLATFORM / TEAM / ORGANIZATION）',
    scope_id    BIGINT UNSIGNED NOT NULL COMMENT 'スコープ ID（PLATFORM は 0）',
    period_key  VARCHAR(8)      NOT NULL COMMENT '期間キー（YYYYMM。将来 YYYY も許容できる長さ）',
    next_number INT UNSIGNED    NOT NULL DEFAULT 1 COMMENT '次に払い出す番号',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_rns_scope_period (scope_type, scope_id, period_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='領収書番号の採番シーケンス（発行者設定行のロックから切り離す）';

-- ─────────────────────────────────────────────────────────────
-- 5. receipt_pdf_archives: 電子帳簿保存法の原本保存（§3.3）
--
--    一意制約を (receipt_id, archive_kind) にしているのは、無効化時に
--    「無効」表示の PDF を同じ領収書の新しいアーカイブ行として追加するためである。
--    receipt_id 単独の一意制約では void が制約違反で失敗する。
--    元の ORIGINAL 行は書き換えも削除もされず、保存期限まで残る。
--
--    receipt_id は同一 receipt ドメイン内のため FK 可（設計原則 1）。
-- ─────────────────────────────────────────────────────────────
CREATE TABLE receipt_pdf_archives (
    id                BINARY(16)      NOT NULL COMMENT 'PK（UUIDv7）',
    receipt_id        BIGINT UNSIGNED NOT NULL COMMENT 'FK → receipts。対象領収書',
    archive_kind      VARCHAR(20)     NOT NULL COMMENT '原本の種別（ORIGINAL / VOIDED）',
    storage_key       VARCHAR(500)    NOT NULL COMMENT 'オブジェクトストレージ上のキー',
    content_sha256    CHAR(64)        NOT NULL COMMENT 'PDF 原本の SHA-256（改ざん検知）',
    byte_size         BIGINT UNSIGNED NOT NULL COMMENT 'バイト数',
    archived_at       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '保存日時',
    retention_until   DATE            NOT NULL COMMENT '保存期限（archived_at + 7年）',
    retention_backend VARCHAR(30)     NOT NULL COMMENT '実際に効いた不変性の担保手段（S3_OBJECT_LOCK / R2_BUCKET_LOCK / APP_ONLY）',
    created_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_rpa_receipt_kind (receipt_id, archive_kind),
    INDEX idx_rpa_receipt (receipt_id),
    INDEX idx_rpa_retention (retention_until),
    CONSTRAINT fk_rpa_receipt FOREIGN KEY (receipt_id) REFERENCES receipts (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='領収書 PDF 原本アーカイブ（電子帳簿保存法）';
