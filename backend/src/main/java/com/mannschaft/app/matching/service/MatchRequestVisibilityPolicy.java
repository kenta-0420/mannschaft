package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.matching.MatchRequestStatus;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.repository.NgTeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 募集（match_requests）の閲覧資格の判定（CMP-260821-1027）。
 *
 * <p>募集詳細（{@link MatchRequestService#getRequest}）と、書込前の閲覧ガード
 * {@link MatchRequestViewAccessGuard} が共有する唯一の判定。規則は次のとおり:</p>
 * <ul>
 *   <li>閲覧者の所属チーム群（{@link AccessControlService#findAffiliatedScopeIds}）のいずれかと
 *       募集チームが双方向 NG 関係なら見えない。</li>
 *   <li>所属チーム群に含まれない募集チームの、OPEN 以外の募集は見えない。</li>
 * </ul>
 *
 * <p>取引を持たない部品。取引の中から呼ばれた場合はその取引に参加し、取引の外から呼ばれた場合は
 * 他ドメインの判定がそれぞれの取引で行われる。</p>
 */
@Component
@RequiredArgsConstructor
public class MatchRequestVisibilityPolicy {

    private static final String SCOPE_TEAM = "TEAM";

    private final NgTeamRepository ngTeamRepository;
    private final AccessControlService accessControlService;

    /**
     * 閲覧者が募集を閲覧できるかを返す。
     *
     * @param entity        募集
     * @param currentUserId 閲覧者ユーザーID
     * @return 閲覧できる場合 true
     */
    public boolean isVisible(MatchRequestEntity entity, Long currentUserId) {
        Set<Long> myTeamIds = accessControlService.findAffiliatedScopeIds(currentUserId, SCOPE_TEAM);
        if (blockedTeamIdsOf(myTeamIds).contains(entity.getTeamId())) {
            return false;
        }
        return myTeamIds.contains(entity.getTeamId()) || entity.getStatus() == MatchRequestStatus.OPEN;
    }

    /**
     * 指定チーム群のそれぞれが双方向ブロックしている teamId を union して返す。
     *
     * @param myTeamIds 閲覧者の所属チーム群
     * @return ブロック関係にある teamId 集合
     */
    public Set<Long> blockedTeamIdsOf(Set<Long> myTeamIds) {
        Set<Long> blocked = new LinkedHashSet<>();
        for (Long teamId : myTeamIds) {
            blocked.addAll(ngTeamRepository.findBidirectionalBlockedTeamIds(teamId));
        }
        return blocked;
    }
}
