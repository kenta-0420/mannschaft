package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先ターゲット認可検証（軍議第8版確定稿 §3.3・AC-12〜17・AC-31・AC-33）。
 *
 * <p><b>骨格のみ（試練A）。出陣で実装する。</b> ターゲットが送信スコープの配下かどうかを
 * {@code TARGET_OUT_OF_SCOPE}（403）で判定する。組織スコープでは、ORGANIZATION(id) が自組織か
 * 子孫組織であること、TEAM(id) がツリー内のいずれかの組織に ACTIVE で所属するチームであることを検証する。
 * チームスコープでは TEAM(自チーム) 以外を許さない。
 *
 * <p>AC-35: 検証は N（ターゲット件数）に比例したクエリ数にしない（ツリーの取得1回と IN 句1回）。
 * AC-33: 登録時の検証（{@link #validateForGroupRegistration}）と送信時の検証
 * （{@link #validateForSend}）は基準が異なる（送信時は「現在も配下にある分だけを展開する」）。</p>
 */
@Component
public class ConfirmableTargetAuthorizationValidator {

    /**
     * 送信時に、宛先グループの登録時のターゲットが送信スコープの配下からすでに外れていても
     * 例外にしない（§3.3 AC-33）。ここは登録時（新規 targets 指定・グループ作成/更新）の検証で、
     * 配下から外れていれば {@code TARGET_OUT_OF_SCOPE}（403）を投げる。
     *
     * @param requestScopeType 送信・登録操作のスコープ種別
     * @param requestScopeId   送信・登録操作のスコープID
     * @param targets          検証対象のターゲット一覧
     */
    public void validateForGroupRegistration(
            ScopeType requestScopeType, Long requestScopeId, List<ConfirmableTargetSpec> targets) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }

    /**
     * 送信時、認可違反のターゲットは 403 とする（直接指定 targets の場合。AC-12〜14）。
     * グループ経由の場合は、配下から外れた分は 0 人として黙って展開から除外する（AC-33。
     * こちらは本メソッドでなく展開系 {@code ConfirmableTargetsFanoutRecipientSource} が担当する）。
     */
    public void validateForSend(
            ScopeType requestScopeType, Long requestScopeId, List<ConfirmableTargetSpec> targets) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }
}
