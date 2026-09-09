-- 柱③-B 請求担当引継（CMP-260901-1538）PR-4 / Codex 検分3巡目 P1-3
-- 設計書: docs/architecture/billing_payer_handover_design.md §5.5 ④・AC-20
--
-- 【何を解決するか】
-- SWITCHING 滞留の夜次照合は、Stripe の pending_setup_intent が未解決の引継を
-- 旧期末より前に FAILED で決着させるための走査である。ところが抽出条件が
-- 「承諾から24時間経過」だけだと、【認証が完了していて旧期末を待っているだけの正常な行】も
-- 毎晩ヒットし続ける。この種の行は処理しても状態が変わらないため集合から抜けず、
-- 1回の実行件数に上限がある以上、正常行が上限を埋めた時点で
-- 【後続の認証未解決行が永久に検査されない】（＝24時間期限を満たせない）。
--
-- 認証完了は Stripe 側の事実（pending_setup_intent が null になる）であり SQL からは見えない。
-- そこで「一度確認して解決済みだった」ことをこの列に刻み、以後の抽出から除外する。
-- pending_setup_intent は一度解決すると再び現れないため、この記録は後から覆らない。
--
-- NULL 許容で追加するだけの後方互換な変更であり、既存行は「未確認」として扱われる
-- （＝これまでどおり次回の夜次照合で1度だけ確認される）。
ALTER TABLE billing_payer_handover_requests
    ADD COLUMN setup_intent_verified_at DATETIME NULL
        COMMENT 'PR-4: 新サブスクの pending_setup_intent が解決済みであることを確認した時刻。SWITCHING 滞留の夜次照合はこの列が NULL の行だけを対象にする（正常待機行が上限を埋めて認証未解決行を飢餓させるのを防ぐ）'
        AFTER old_cancel_scheduled_at;

-- 滞留抽出のキーセット送り（accepted_at, id の複合カーソル）に対応するインデックス。
-- 抽出条件が status / psp_new_subscription_ref / setup_intent_verified_at で絞り、
-- accepted_at 昇順に走る形であるため、その順序で並べる。
CREATE INDEX idx_bphr_stalled_switching
    ON billing_payer_handover_requests (status, setup_intent_verified_at, accepted_at, id);
