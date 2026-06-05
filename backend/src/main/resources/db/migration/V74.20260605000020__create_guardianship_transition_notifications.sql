-- ※採番は origin/main 最大（V74.20260605000010）の次（タイムスタンプ式 V74.20260605000020）に確定。
-- F08.9 P3c-3 自立移行の通知バッチ2本（02_api_design §2.3 / 03_security §3.1）の重複送信防止テーブル。
--
-- 背景: 自立移行の保険として 2 つの日次バッチを動かす。
--   (1) 進学予告バッチ: 子が封印境界日（sealDate）の 3ヶ月前に入ったら保護者へ予告通知（アプリ内通知＋メール）。
--   (2) 封印時未設定メールバッチ: 封印日（sealDate <= today）にパスワード未設定の子へパスワード設定メール。
-- いずれも日次で走るため、同一（受信者×子×境界日×種別）で何度も送らないよう送信記録で冪等化する。
--
-- 設計判断（流用 vs 新設）:
--   既存の notification 系には「(受信者, 子, 封印境界日, 種別) で 1 回限り」を保証できる送信記録テーブルが無い
--   （notifications テーブルは送信ログであり UNIQUE 制約を持たない／email_outbox の idempotency_key は
--   メールにしか効かずアプリ内通知の冪等化に使えない）。よって専用の小さな冪等テーブルを新設する。
--
-- 設計原則:
--   原則1: クロスドメイン FK なし（recipient_user_id / child_user_id は論理参照・INDEX のみ）。
--   原則6: PK は UUIDv7（BINARY(16)）。
--   recipient_user_id は通知の宛先（進学予告=保護者／封印時メール=子本人）で常に NOT NULL とし、
--   UNIQUE KEY を (notification_kind, recipient_user_id, child_user_id, seal_date) に置く
--   （MySQL の UNIQUE は NULL を distinct 扱いするため、宛先を常に非 NULL にして冪等性を厳密化する）。
CREATE TABLE guardianship_transition_notifications (
    id                  BINARY(16)      NOT NULL COMMENT 'PK (UUIDv7)',
    notification_kind   VARCHAR(32)     NOT NULL COMMENT '種別: PROGRESSION_NOTICE（進学予告・保護者宛）/ SEAL_UNSET_PASSWORD（封印時未設定メール・子宛）',
    recipient_user_id   BIGINT UNSIGNED NOT NULL COMMENT '通知の宛先ユーザーID（進学予告=保護者／封印時メール=子本人）。論理参照・FKなし',
    child_user_id       BIGINT UNSIGNED NOT NULL COMMENT '対象の子ユーザーID。論理参照・FKなし',
    seal_date           DATE            NOT NULL COMMENT '封印境界日（GuardianshipAgePolicy.sealDate）。冪等キーの一部',
    created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '送信記録作成日時（UTC）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_gtn_dedup (notification_kind, recipient_user_id, child_user_id, seal_date),
    KEY idx_gtn_child (child_user_id, notification_kind),
    CONSTRAINT chk_gtn_kind CHECK (notification_kind IN ('PROGRESSION_NOTICE', 'SEAL_UNSET_PASSWORD'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='F08.9 P3c-3 自立移行通知の重複送信防止（進学予告/封印時未設定メール）';
