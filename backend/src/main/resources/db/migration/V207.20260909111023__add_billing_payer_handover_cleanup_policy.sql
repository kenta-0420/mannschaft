-- 柱③-B 請求担当引継（CMP-260901-1538）PR-4 / Codex 検分5巡目 P1-2
-- 設計書: docs/architecture/billing_payer_handover_design.md §3.6.2
--
-- 【何を解決するか】
-- 失敗確定の後始末は「CAS で FAILING_CLEANUP を取る → Stripe の後始末 → finalizeFailure で終端化」
-- の3段で進み、途中で落ちた場合は夜次バッチが FAILING_CLEANUP の行を拾って再試行する。
--
-- ところが後始末の内容は経路によって異なる:
--   ・RESUME→FAILED では、運用者が「旧サブスクの期末解約予約を差し戻すか」を明示的に選ぶ
--     （旧が既に次の期間へ更新済みの場合、差し戻しは旧をさらに継続させるため誤りになりうる）。
--     また「引継自体を諦める」という判断なので AC-20 の再要求も作らない。
--   ・切替時の追加認証失敗や滞留の決着では、差し戻して他 ADMIN へ再通知する。
--
-- この判断を持たないまま夜次が再試行すると、運用者が「戻さない・再要求しない」と決めた行に対して
-- 【旧契約を継続へ戻し、再要求まで作ってしまう】。運用者の判断が上書きされる。
--
-- 判断は「その行をどう終わらせるか」の一部であり、再試行を跨いで保持される必要があるため列に持つ。
-- NULL は「既定（差し戻す・再通知する）」を意味し、本 migration 以前の行と互換である。
ALTER TABLE billing_payer_handover_requests
    ADD COLUMN cleanup_revert_old_cancel BOOLEAN NULL
        COMMENT 'PR-4: 失敗確定の後始末で旧サブスクの cancel_at_period_end を差し戻すか。RESUME→FAILED では運用者が明示的に選ぶ。NULL は既定（差し戻す）'
        AFTER setup_intent_verified_at,
    ADD COLUMN cleanup_renotify BOOLEAN NULL
        COMMENT 'PR-4: 失敗確定後に AC-20 の再要求・再通知を行うか。RESUME→FAILED（引継自体を諦める判断）では false。NULL は既定（再通知する）'
        AFTER cleanup_revert_old_cancel;
