package com.mannschaft.app.common;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ユーザーを起点とする所属スコープ列挙の正本窓口。
 *
 * <p>「利用可能なユーザーの所属」と「membership 行そのものが現在有効」は同義ではない。
 * 前者は ACTIVE・非削除ユーザーに限定して {@code user_roles ∪ memberships} を返し、
 * 後者はユーザー状態を問わず {@code memberships.left_at IS NULL} の行だけを返す。
 * 呼び出し側が意図しない母集団を選ばないよう、用途別メソッドとして公開する。</p>
 *
 * <p>保護者による子データ閲覧は、PENDING_PARENTAL_CONSENT / FROZEN の子も被参照者に
 * なり得る意図的な例外である。Guardian 用メソッドは、呼び出し元で保護者リンクと年齢を
 * 検証済みであることを名前に明示し、通常の権限判定には使用しない。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MembershipScopeQueryService {

    private final UserRoleRepository userRoleRepository;
    private final MembershipRepository membershipRepository;

    /** ACTIVE・非削除ユーザーが所属するチームを role ∪ current membership で返す。 */
    public List<Long> findActiveTeamIds(Long userId) {
        return userRoleRepository.findTeamIdsByUserId(userId);
    }

    /** ACTIVE・非削除ユーザーが所属する組織を role ∪ current membership で返す。 */
    public List<Long> findActiveOrganizationIds(Long userId) {
        return userRoleRepository.findOrganizationIdsByUserId(userId);
    }

    /** ユーザー状態を問わず、離脱していない TEAM membership のスコープ ID を返す。 */
    public List<Long> findCurrentMembershipTeamIds(Long userId) {
        return findCurrentMemberships(userId, ScopeType.TEAM).stream()
                .map(CurrentMembershipScope::scopeId)
                .toList();
    }

    /** ユーザー状態を問わず、離脱していない ORGANIZATION membership のスコープ ID を返す。 */
    public List<Long> findCurrentMembershipOrganizationIds(Long userId) {
        return findCurrentMemberships(userId, ScopeType.ORGANIZATION).stream()
                .map(CurrentMembershipScope::scopeId)
                .toList();
    }

    /**
     * 認可済み保護者が閲覧する子について、状態を問わず current TEAM membership を返す。
     * 呼び出し前に GuardianChildViewService の権原・年齢ゲートを通過している必要がある。
     */
    public List<Long> findCurrentTeamIdsForAuthorizedGuardianSubject(Long childUserId) {
        return findCurrentMembershipTeamIds(childUserId);
    }

    /**
     * 認可済み保護者が閲覧する子について、状態を問わず current ORGANIZATION membership を返す。
     * 呼び出し前に GuardianChildViewService の権原・年齢ゲートを通過している必要がある。
     */
    public List<Long> findCurrentOrganizationIdsForAuthorizedGuardianSubject(Long childUserId) {
        return findCurrentMembershipOrganizationIds(childUserId);
    }

    /** ユーザー状態を問わず、離脱していない membership を joined_at 降順で返す。 */
    public List<CurrentMembershipScope> findCurrentMemberships(Long userId, ScopeType scopeType) {
        return membershipRepository.findActiveByUserAndScopeType(userId, scopeType).stream()
                .map(membership -> new CurrentMembershipScope(
                        membership.getScopeId(), membership.getJoinedAt()))
                .toList();
    }

    /** current membership の表示用最小情報。Entity をドメイン外へ漏らさない。 */
    public record CurrentMembershipScope(Long scopeId, LocalDateTime joinedAt) {
    }
}
