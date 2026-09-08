-- =====================================================================
-- 柱③-B 組織契約の請求担当と個人支払手段の分離（CMP-260901-1538・PR-3: 退会時の一括期末解約）
-- =====================================================================
-- 設計書: docs/architecture/billing_payer_handover_design.md §6・§6.1（AC-13）
--
-- このテーブルが解く2つの問題（Codex 検分1巡目 P1-1・P1-3）:
--
-- ① 再試行経路が無い（P1-1）
--    退会イベントは永続化されない Spring のインメモリイベントであり、ハンドラは共有 event-pool の
--    非同期処理である。Stripe 失敗・投入拒否・commit 直後のプロセス停止では処理そのものが失われ、
--    退会者への課金継続をログ監視だけに委ねることになる。サブスク単位の処理状態を永続化することで、
--    PENDING/FAILED を機械的に拾い直せる形にする（再試行の駆動そのものは PR-4 の夜次バッチ）。
--
-- ② 退会取消時に「退会処理由来で予約したか」を判別できない（P1-3）
--    membership_subscriptions.cancel_at_period_end は boolean であり、退会前に本人が明示解約した
--    契約と、退会処理が自動予約した契約を区別できない。単純に payer の全予約を解除すると、
--    本人が意図して解約した契約まで復活させてしまう。この表が「由来」の正本となる。
--
-- クロスドメイン FK は張らない（payer_user_id は users.id への論理参照）。
-- subscription_id も同一 payment ドメイン内だが、履歴表であり物理削除に追随させないため FK を張らない。
-- 新規テーブルの主キーは UUIDv7（BINARY(16)・CLAUDE.md 原則6）。
-- =====================================================================
CREATE TABLE membership_payer_withdrawal_cancellations (
    id BINARY(16) NOT NULL COMMENT 'UUIDv7',
    subscription_id BINARY(16) NOT NULL COMMENT 'membership_subscriptions.id への論理参照',
    payer_user_id BIGINT UNSIGNED NOT NULL COMMENT '退会申請した払い手（users.id への論理参照）',
    withdrawal_attempt_at DATETIME(6) NOT NULL COMMENT '退会試行の世代。処理時点の users.deleted_at。どの退会申請に属する作業行かを一意に指す',
    stripe_subscription_id VARCHAR(255) NULL COMMENT '予約時点の Stripe Subscription ID（未連結なら NULL）',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/SUCCEEDED/FAILED/RESTORING/RESTORED/SUPERSEDED（6値）。非終端は PENDING・FAILED・RESTORING',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '試行回数。PR-4 の再試行バッチが上限判定に使う',
    last_error VARCHAR(1000) NULL COMMENT '直近の失敗理由（再試行の切り分け用・PII は含めない）',
    scheduled_at DATETIME(6) NULL COMMENT 'Stripe と DB の双方で期末解約予約が確定した瞬間（SUCCEEDED と同時に埋まる）',
    restored_at DATETIME(6) NULL COMMENT '退会取消により予約を解除した瞬間。NULL の SUCCEEDED 行だけが復旧対象',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_mpwc_subscription (subscription_id)
        COMMENT '1サブスクにつき1行。退会→取消→再退会は同じ行を PENDING へ差し戻して再利用する',
    KEY idx_mpwc_payer_status (payer_user_id, status, restored_at)
        COMMENT '退会取消時の復旧対象（payer 一致・SUCCEEDED・restored_at IS NULL）を引く',
    KEY idx_mpwc_retry (status, updated_at)
        COMMENT 'PR-4 の再試行バッチが PENDING/FAILED/RESTORING を古い順に拾う',
    CONSTRAINT chk_mpwc_status CHECK (status IN (
        'PENDING', 'SUCCEEDED', 'FAILED', 'RESTORING', 'RESTORED', 'SUPERSEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='払い手の退会に伴う継続課金の期末解約の処理状態。再試行の拾い直しと退会取消時の復旧対象判定の正本';
