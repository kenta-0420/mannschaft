-- 柱③-B 請求担当引継（CMP-260901-1538）PR-4 / Codex 検分4巡目
-- 設計書: docs/architecture/billing_payer_handover_design.md §3.6.2
--
-- 【何を解決するか】
-- 引継を FAILED で終わらせる経路（切替時の pending_setup_intent 未解決・RESUME→FAILED・
-- SWITCHING 滞留の決着）は、DB の終端化だけでは終わらない。Stripe 側に
--   ・新 trial サブスクの即時取消
--   ・旧サブスクの cancel_at_period_end 差し戻し
-- という後始末が残る。これらは外部システムへの呼び出しなので DB トランザクションに
-- 巻き込めず、失敗しうる。
--
-- 是正前は「FAILED にしてから後始末し、目印（old_cancel_scheduled_at）の有無で未了を表す」形だった。
-- しかしこの目印は CAS の時点で消えており、また FAILED は生成列 open_old_contract_id 上の
-- 終端であるため、後始末が失敗した瞬間に
--   ① 夜次バッチの抽出対象から外れて回収不能になる
--   ② 同一旧契約への UNIQUE 枠が空き、通常の作成入口から次の要求を作れてしまう
--      （＝旧試行のサブスクが Stripe に残ったまま新しい承諾が進み、二重サブスクになり得る）
-- という2つの穴が同時に開いた。
--
-- 【設計】
-- 「後始末未了」を目印の有無ではなく【明示的な非終端状態】として表現する。
-- FAILING_CLEANUP は終端3値（COMPLETED / FAILED / EXPIRED）に含まれないため、
--   ・生成列 open_old_contract_id は値を保持し続ける
--     → uk_bphr_open_old_contract が【通常の作成入口も含めて】同一契約への新規要求を物理的に拒む
--   ・夜次バッチはこの状態を抽出して後始末を再試行できる
-- という2つの性質が、既存の仕組みだけで自動的に成立する。
-- Stripe の後始末が成功したことを確認してから初めて FAILED へ終端化する。
--
-- 生成列の CASE 式は終端3値を列挙する形（それ以外は値を保持）であるため変更不要。
ALTER TABLE billing_payer_handover_requests
    DROP CHECK chk_bphr_status;

ALTER TABLE billing_payer_handover_requests
    ADD CONSTRAINT chk_bphr_status CHECK (status IN (
        'REQUESTED','ACCEPTED','REQUIRES_PAYMENT_METHOD','SWITCHING',
        'PARTIALLY_COMPLETED','MANUAL_INTERVENTION','FAILING_CLEANUP',
        'COMPLETED','FAILED','EXPIRED'));
