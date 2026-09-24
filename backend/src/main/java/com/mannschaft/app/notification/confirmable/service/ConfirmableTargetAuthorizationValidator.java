package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先ターゲット認可検証（軍議第8版確定稿 §3.3・AC-12〜17・AC-31・AC-33）。
 *
 * <p>ターゲットが送信スコープの配下かどうかを {@code TARGET_OUT_OF_SCOPE}（403）で判定する。組織スコープでは、
 * ORGANIZATION(id) が自組織か子孫組織であること、TEAM(id) がツリー内のいずれかの組織に ACTIVE で所属する
 * チームであることを検証する。チームスコープでは TEAM(自チーム) 以外を許さない。</p>
 *
 * <p>AC-35: 検証は N（ターゲット件数）に比例したクエリ数にしない（組織スコープでは、ツリーの取得1回と
 * ACTIVE チーム所属の IN 句1回の最大2クエリで完結する。チームスコープは DB を引かない）。</p>
 *
 * <p>{@link #validateForGroupRegistration} と {@link #validateForSend} は、直接指定ターゲットに対する
 * 判定基準としては同一の「現在配下にあるか」を用いる。両者の違いは呼び出し側の扱いにある。
 * 宛先グループ経由（{@code recipientGroupId} 指定）の送信では本クラスの {@code validateForSend} を
 * 呼ばず、配下から外れた分は {@code ConfirmableTargetsFanoutRecipientSource} が黙って0人展開する
 * （AC-33）。本クラスの2メソッドは、どちらも「呼ばれた時点で配下にない直接指定ターゲット」を403にする。</p>
 */
@Component
@RequiredArgsConstructor
public class ConfirmableTargetAuthorizationValidator {

    /** OrgFanoutRecipientSource / UserRoleRepository の再帰CTEと同じサイクル防止上限。 */
    static final int MAX_ORG_DESCENDANT_DEPTH = 32;

    private final ConfirmableNotificationTargetRepository targetRepository;

    /**
     * 宛先グループの登録・更新時のターゲット検証（AC-31・AC-33）。判定基準は {@link #validateForSend} と同じ
     * （呼ばれた時点で配下にあるか）。
     */
    public void validateForGroupRegistration(
            ScopeType requestScopeType, Long requestScopeId, List<ConfirmableTargetSpec> targets) {
        validate(requestScopeType, requestScopeId, targets);
    }

    /**
     * 送信時、直接指定 targets の認可違反を403にする（AC-12〜14）。
     */
    public void validateForSend(
            ScopeType requestScopeType, Long requestScopeId, List<ConfirmableTargetSpec> targets) {
        validate(requestScopeType, requestScopeId, targets);
    }

    private void validate(ScopeType requestScopeType, Long requestScopeId, List<ConfirmableTargetSpec> targets) {
        if (targets == null || targets.isEmpty()) {
            return;
        }

        if (requestScopeType == ScopeType.TEAM) {
            for (ConfirmableTargetSpec target : targets) {
                boolean ok = target.getType() == ConfirmableTargetType.TEAM
                        && target.getId().equals(requestScopeId);
                if (!ok) {
                    throw new BusinessException(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);
                }
            }
            return;
        }

        // ORGANIZATION スコープ: ツリー取得は1クエリ（N に依存しない）。
        List<Long> orgTreeIds = targetRepository.findOrganizationTreeIds(requestScopeId, MAX_ORG_DESCENDANT_DEPTH);
        Set<Long> orgTreeIdSet = new HashSet<>(orgTreeIds);

        List<Long> teamTargetIds = targets.stream()
                .filter(t -> t.getType() == ConfirmableTargetType.TEAM)
                .map(ConfirmableTargetSpec::getId)
                .distinct()
                .collect(Collectors.toList());

        // ACTIVE 所属判定も件数に依らず1クエリ（IN 句）で完結させる（AC-35）。
        Set<Long> activeTeamIdSet = teamTargetIds.isEmpty()
                ? Set.of()
                : new HashSet<>(targetRepository.findActiveTeamIdsWithinOrganizations(teamTargetIds, orgTreeIds));

        for (ConfirmableTargetSpec target : targets) {
            boolean ok;
            if (target.getType() == ConfirmableTargetType.ORGANIZATION) {
                ok = orgTreeIdSet.contains(target.getId());
            } else {
                ok = activeTeamIdSet.contains(target.getId());
            }
            if (!ok) {
                throw new BusinessException(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);
            }
        }
    }
}
