package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamOrgAffiliationRestrictionKind;

import java.time.Instant;

/**
 * 加盟の再送制限の合成規則（F01.2.1 §5.4「既存行との合成」）の純粋関数。
 *
 * <p>同じ (組織, チーム, 向き) に対して制限が重ねて記録されるとき、1行にまとめる。規則は次のとおり。</p>
 * <ul>
 *   <li>既存が BLOCK なら、新しい制限が何であっても既存を残す（BLOCK は COOLDOWN で上書きしない）。</li>
 *   <li>既存が COOLDOWN で、新しい制限が BLOCK なら、新しい制限で置き換える。</li>
 *   <li>両方 COOLDOWN なら、期限の遅いほうを残す（同時刻は既存を残す）。</li>
 * </ul>
 */
public final class TeamOrgAffiliationRestrictionComposer {

    private TeamOrgAffiliationRestrictionComposer() {
        // 純粋関数のみ
    }

    /**
     * 新しい制限が既存の制限を置き換えるかを返す。
     *
     * @param existingKind  既存の種別
     * @param existingUntil 既存の期限（COOLDOWN のとき非 null。BLOCK は null）
     * @param incomingKind  新しい制限の種別
     * @param incomingUntil 新しい制限の期限（COOLDOWN のとき非 null。BLOCK は null）
     * @return 新しい制限で置き換えるなら true、既存を残すなら false
     */
    public static boolean incomingWins(TeamOrgAffiliationRestrictionKind existingKind, Instant existingUntil,
                                       TeamOrgAffiliationRestrictionKind incomingKind, Instant incomingUntil) {
        if (existingKind == TeamOrgAffiliationRestrictionKind.BLOCK) {
            return false;
        }
        if (incomingKind == TeamOrgAffiliationRestrictionKind.BLOCK) {
            return true;
        }
        return incomingUntil.isAfter(existingUntil);
    }
}
