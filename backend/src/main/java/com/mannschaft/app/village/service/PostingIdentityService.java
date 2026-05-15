package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.PostingIdentityListResponse;
import com.mannschaft.app.village.dto.PostingIdentityResponse;
import com.mannschaft.app.village.entity.UserVillageNicknameEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * F17.1 Phase 1 B9 — 投稿主体（PostingIdentity）解決・検証サービス。
 *
 * <p>役割は 2 つに分かれる:</p>
 * <ul>
 *   <li>{@link #listIdentities(Long, UUID)} — 呼び出しユーザーが当該村でなれる
 *       投稿主体（USER + 代表チーム + 代表組織）一覧を返す（§4.6）</li>
 *   <li>{@link #validatePostingIdentity(Long, UUID, VillageSubjectType, Long)} —
 *       既存ドメイン Service（bulletin/timeline/chat）が投稿時に呼ぶ検証関数（§5.4 / §6.3）</li>
 * </ul>
 *
 * <p>判定ルール（Phase 1）:</p>
 * <ul>
 *   <li>USER: subjectId が actorUserId 本人と一致すること（IDOR 対策）</li>
 *   <li>TEAM: actorUserId が当該チームの ADMIN / DEPUTY_ADMIN かつ
 *       チームが当該村のメンバーであること</li>
 *   <li>ORGANIZATION: actorUserId が当該組織の ADMIN / DEPUTY_ADMIN かつ
 *       組織が当該村のメンバーであること</li>
 * </ul>
 *
 * <p>アーキテクチャ原則:</p>
 * <ul>
 *   <li>原則1: {@link UserRoleRepository}/{@link TeamRepository}/{@link OrganizationRepository} は
 *       Read-only で呼び出し、FK 制約には依存しない</li>
 *   <li>原則5: 本 Service は読取専用ゆえ {@code @Transactional(readOnly=true)} に閉じる。
 *       将来 PostingIdentity の集計テーブル化や監査ログ書込みが入った場合は
 *       Service 分割を検討する。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostingIdentityService {

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final UserVillageNicknameRepository nicknameRepository;
    private final UserRoleRepository userRoleRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    // ========================================================================
    // §4.6 投稿主体一覧
    // ========================================================================

    /**
     * 呼び出しユーザーが当該村でなれる投稿主体一覧を返す。
     *
     * <p>呼び出しユーザー自身が村の USER メンバーである必要がある。非メンバーは
     * {@link VillageErrorCode#NOT_MEMBER} で拒否する（IDOR 対策で 404）。</p>
     *
     * <p>含まれる主体:</p>
     * <ol>
     *   <li>USER（自分自身、村ニックネーム解決後）</li>
     *   <li>TEAM 代表（actorUserId が ADMIN/DEPUTY_ADMIN かつ村メンバー）</li>
     *   <li>ORGANIZATION 代表（actorUserId が ADMIN/DEPUTY_ADMIN かつ村メンバー）</li>
     * </ol>
     *
     * @param actorUserId 呼び出しユーザー
     * @param villageId   対象村
     * @return 投稿主体エントリのリスト（最低 1 件: USER）
     */
    @Transactional(readOnly = true)
    public PostingIdentityListResponse listIdentities(Long actorUserId, UUID villageId) {
        // 村存在性・凍結確認
        loadActiveVillage(villageId);

        // 呼び出しユーザーが USER として村のメンバーであること
        if (!isUserVillageMember(villageId, actorUserId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }

        List<PostingIdentityResponse> result = new ArrayList<>();

        // 1) USER 主体（村ニックネーム）
        String userDisplayName = resolveUserDisplayName(actorUserId);
        result.add(PostingIdentityResponse.user(actorUserId, userDisplayName));

        // 2) TEAM 代表（actor が ADMIN/DEPUTY_ADMIN のチーム）
        Set<Long> villageMemberTeamIds = membershipRepository
                .findByVillageIdAndLeftAtIsNullOrderByJoinedAtAsc(villageId,
                        org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(m -> m.getSubjectType() == VillageSubjectType.TEAM)
                .map(VillageMembershipEntity::getSubjectId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
        Set<Long> villageMemberOrgIds = membershipRepository
                .findByVillageIdAndLeftAtIsNullOrderByJoinedAtAsc(villageId,
                        org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(m -> m.getSubjectType() == VillageSubjectType.ORGANIZATION)
                .map(VillageMembershipEntity::getSubjectId)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));

        // actor が所属する team/org ロールを引いて、ADMIN/DEPUTY_ADMIN かつ村メンバーのものに絞る
        for (UserRoleEntity ur : userRoleRepository.findByUserIdAndTeamIdIsNotNull(actorUserId)) {
            Long teamId = ur.getTeamId();
            if (teamId == null || !villageMemberTeamIds.contains(teamId)) {
                continue;
            }
            if (userRoleRepository.countTeamAdminByUserIdAndTeamId(actorUserId, teamId) == 0L) {
                continue;
            }
            // 重複排除: 既に追加済みなら無視
            if (containsSubject(result, VillageSubjectType.TEAM, teamId)) {
                continue;
            }
            String name = teamRepository.findById(teamId)
                    .map(TeamEntity::getName)
                    .orElse("TEAM:#" + teamId);
            result.add(PostingIdentityResponse.team(teamId, name));
        }

        for (UserRoleEntity ur : userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(actorUserId)) {
            Long orgId = ur.getOrganizationId();
            // team_id != NULL かつ organization_id != NULL のレコードも返ってくるので、
            // ここでは「組織直属（team_id IS NULL）」も含めて拾うが、
            // 組織 ADMIN 判定は findAdminUserIdsByOrganizationId で行うため二重に検証する。
            if (orgId == null || !villageMemberOrgIds.contains(orgId)) {
                continue;
            }
            List<Long> orgAdmins = userRoleRepository.findAdminUserIdsByOrganizationId(orgId);
            if (!orgAdmins.contains(actorUserId)) {
                continue;
            }
            if (containsSubject(result, VillageSubjectType.ORGANIZATION, orgId)) {
                continue;
            }
            String name = organizationRepository.findById(orgId)
                    .map(OrganizationEntity::getName)
                    .orElse("ORG:#" + orgId);
            result.add(PostingIdentityResponse.organization(orgId, name));
        }

        return PostingIdentityListResponse.of(result);
    }

    // ========================================================================
    // §5.4 / §6.3 投稿主体の権限検証
    // ========================================================================

    /**
     * 投稿時の {@code postedAs} を検証する。
     *
     * <p>USER の場合は subjectId が actorUserId 本人と一致することのみ確認。
     * TEAM/ORGANIZATION の場合は以下をすべて満たす必要がある:</p>
     * <ol>
     *   <li>actorUserId が当該主体の ADMIN/DEPUTY_ADMIN ロールを持つ</li>
     *   <li>当該主体が対象村の現役メンバーである（village_memberships に subject 一致行があり left_at IS NULL）</li>
     * </ol>
     *
     * <p>いずれか失格なら {@link VillageErrorCode#VILLAGE_POSTING_IDENTITY_FORBIDDEN}（403）を投げる。
     * subjectType=USER で subjectId が呼び出しユーザーと不一致の場合は
     * IDOR 対策の {@link VillageErrorCode#REPRESENT_FORBIDDEN}（403）を維持する。</p>
     *
     * @param actorUserId 呼び出しユーザー
     * @param villageId   対象村
     * @param subjectType 投稿主体種別
     * @param subjectId   投稿主体 ID（USER の場合 userId、TEAM の場合 teamId、ORG の場合 organizationId）
     */
    @Transactional(readOnly = true)
    public void validatePostingIdentity(Long actorUserId,
                                        UUID villageId,
                                        VillageSubjectType subjectType,
                                        Long subjectId) {
        if (subjectType == null || subjectId == null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
        }

        // 村存在性・凍結確認
        loadActiveVillage(villageId);

        // 呼び出しユーザーが村のメンバーであること
        if (!isUserVillageMember(villageId, actorUserId)) {
            throw new BusinessException(VillageErrorCode.NOT_MEMBER);
        }

        switch (subjectType) {
            case USER -> {
                if (!actorUserId.equals(subjectId)) {
                    // 他人 ID なりすまし: §5.4 1) 違反
                    throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
                }
            }
            case TEAM -> {
                long adminCount = userRoleRepository.countTeamAdminByUserIdAndTeamId(actorUserId, subjectId);
                if (adminCount == 0L) {
                    throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
                }
                if (!isSubjectVillageMember(villageId, VillageSubjectType.TEAM, subjectId)) {
                    throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
                }
            }
            case ORGANIZATION -> {
                List<Long> admins = userRoleRepository.findAdminUserIdsByOrganizationId(subjectId);
                if (!admins.contains(actorUserId)) {
                    throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
                }
                if (!isSubjectVillageMember(villageId, VillageSubjectType.ORGANIZATION, subjectId)) {
                    throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
                }
            }
            default -> throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
        }
    }

    // ========================================================================
    // 共通ヘルパ
    // ========================================================================

    /** 有効な村を取得する（削除/凍結は VILLAGE_001 で扱う）。 */
    private VillageEntity loadActiveVillage(UUID villageId) {
        VillageEntity v = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (v.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        if (v.getArchivedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }
        return v;
    }

    /** 当該ユーザーが対象村の現役 USER 主体メンバーであるか。 */
    private boolean isUserVillageMember(UUID villageId, Long userId) {
        return membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, VillageSubjectType.USER, userId)
                .filter(m -> m.getBannedAt() == null)
                .isPresent();
    }

    /** 指定 subject が村の現役メンバーであるか。 */
    private boolean isSubjectVillageMember(UUID villageId, VillageSubjectType type, Long subjectId) {
        return membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(villageId, type, subjectId)
                .filter(m -> m.getBannedAt() == null)
                .isPresent();
    }

    /**
     * ユーザーの表示名（村ニックネーム）を解決する。
     * Phase 1 は {@code villageId IS NULL} の全村共通ニックネーム。未登録なら {@code "USER:#<id>"}。
     */
    private String resolveUserDisplayName(Long userId) {
        Optional<UserVillageNicknameEntity> nick = nicknameRepository.findByUserIdAndVillageIdIsNull(userId);
        return nick.map(UserVillageNicknameEntity::getNickname).orElse("USER:#" + userId);
    }

    /** 既追加かどうかを線形検索（リスト最大数件ゆえコスト無視）。 */
    private boolean containsSubject(List<PostingIdentityResponse> list,
                                    VillageSubjectType type,
                                    Long subjectId) {
        for (PostingIdentityResponse r : list) {
            if (r.subjectType() == type && r.subjectId().equals(subjectId)) {
                return true;
            }
        }
        return false;
    }
}
