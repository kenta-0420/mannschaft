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
import com.mannschaft.app.village.entity.VillageRepresentativeEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.UserVillageNicknameRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
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
 * F17.1 Phase 1 B9 + Phase 2 U10 — 投稿主体（PostingIdentity）解決・検証サービス。
 *
 * <p>役割は 2 つに分かれる:</p>
 * <ul>
 *   <li>{@link #listIdentities(Long, UUID)} — 呼び出しユーザーが当該村でなれる
 *       投稿主体（USER + 代表チーム + 代表組織）一覧を返す（§4.6）</li>
 *   <li>{@link #validatePostingIdentity(Long, UUID, VillageSubjectType, Long)} —
 *       既存ドメイン Service（bulletin/timeline/chat）が投稿時に呼ぶ検証関数（§5.4 / §6.3）</li>
 * </ul>
 *
 * <p>判定ルール（Phase 1 + Phase 2 拡張、設計書 §5.4 準拠）:</p>
 * <ul>
 *   <li>USER: subjectId が actorUserId 本人と一致すること（IDOR 対策）</li>
 *   <li>TEAM: actorUserId が当該チームの ADMIN / DEPUTY_ADMIN
 *       <b>または</b> {@code village_representatives} で当該メンバーシップに対する
 *       現役の代表委任を保持していること。かつチームが当該村のメンバーであること。</li>
 *   <li>ORGANIZATION: actorUserId が当該組織の ADMIN / DEPUTY_ADMIN
 *       <b>または</b> {@code village_representatives} で当該メンバーシップに対する
 *       現役の代表委任を保持していること。かつ組織が当該村のメンバーであること。</li>
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

    private final VillageMembershipRepository membershipRepository;
    private final UserVillageNicknameRepository nicknameRepository;
    private final UserRoleRepository userRoleRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    /** Phase 2 U10: 専用代表ロール委任の判定に利用。 */
    private final VillageRepresentativeService villageRepresentativeService;
    private final VillageAccessGate accessGate;

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
        loadActiveVillage(villageId, actorUserId);

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

        // 3) Phase 2 U10: village_representatives で active な代表委任を受けたチーム/組織
        //    ADMIN ではないが HEADMAN/ELDER に専用代表として委任されたメンバーを拾う。
        //    設計書 §5.4 / §1.4 Phase 2 条項に対応。
        List<VillageRepresentativeEntity> delegations =
                villageRepresentativeService.findActiveRepresentativesByUser(actorUserId);
        for (VillageRepresentativeEntity rep : delegations) {
            // 別の村の委任は無視（横断利用の防衛）
            if (!villageId.equals(rep.getVillageId())) {
                continue;
            }
            // 委任先 membership を引いてチーム/組織を解決
            Optional<VillageMembershipEntity> membershipOpt =
                    membershipRepository.findById(rep.getMembershipId());
            if (membershipOpt.isEmpty()) {
                continue;
            }
            VillageMembershipEntity membership = membershipOpt.get();
            // 退会 / BAN 済みのメンバーシップは委任も無効扱い（村のメンバーでない subject は代表できない）
            if (membership.getLeftAt() != null || membership.getBannedAt() != null) {
                continue;
            }
            VillageSubjectType subjectType = membership.getSubjectType();
            Long subjectId = membership.getSubjectId();
            if (subjectId == null) {
                continue;
            }
            if (subjectType == VillageSubjectType.TEAM) {
                if (containsSubject(result, VillageSubjectType.TEAM, subjectId)) {
                    continue;
                }
                String name = teamRepository.findById(subjectId)
                        .map(TeamEntity::getName)
                        .orElse("TEAM:#" + subjectId);
                result.add(PostingIdentityResponse.team(subjectId, name));
            } else if (subjectType == VillageSubjectType.ORGANIZATION) {
                if (containsSubject(result, VillageSubjectType.ORGANIZATION, subjectId)) {
                    continue;
                }
                String name = organizationRepository.findById(subjectId)
                        .map(OrganizationEntity::getName)
                        .orElse("ORG:#" + subjectId);
                result.add(PostingIdentityResponse.organization(subjectId, name));
            }
            // USER タイプの委任は設計上発生しない（U3 で REPRESENTATIVE_NOT_TEAM_OR_ORG_MEMBERSHIP ガード済み）
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
     *   <li>当該主体が対象村の現役メンバーである（village_memberships に subject 一致行があり left_at IS NULL）</li>
     *   <li>actorUserId が当該主体の ADMIN/DEPUTY_ADMIN ロールを持つ
     *       <b>または</b>当該 membership に対し
     *       {@code village_representatives} の現役委任を保有していること（Phase 2 拡張）</li>
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
        loadActiveVillage(villageId, actorUserId);

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
                // 主体（チーム）が当該村のメンバーであることを先に確認（IDOR 対策）。
                // 委任判定にも membership.id が必要なので Optional で取得する。
                Optional<VillageMembershipEntity> teamMembership = membershipRepository
                        .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                                villageId, VillageSubjectType.TEAM, subjectId)
                        .filter(m -> m.getBannedAt() == null);
                if (teamMembership.isEmpty()) {
                    throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
                }
                // Phase 1: ADMIN ロール所持 OR Phase 2: 専用代表委任
                long adminCount = userRoleRepository.countTeamAdminByUserIdAndTeamId(actorUserId, subjectId);
                boolean isAdmin = adminCount > 0L;
                boolean isDelegated = !isAdmin && villageRepresentativeService
                        .isUserActiveRepresentative(teamMembership.get().getId(), actorUserId);
                if (!isAdmin && !isDelegated) {
                    throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
                }
            }
            case ORGANIZATION -> {
                Optional<VillageMembershipEntity> orgMembership = membershipRepository
                        .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                                villageId, VillageSubjectType.ORGANIZATION, subjectId)
                        .filter(m -> m.getBannedAt() == null);
                if (orgMembership.isEmpty()) {
                    throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
                }
                List<Long> admins = userRoleRepository.findAdminUserIdsByOrganizationId(subjectId);
                boolean isAdmin = admins.contains(actorUserId);
                boolean isDelegated = !isAdmin && villageRepresentativeService
                        .isUserActiveRepresentative(orgMembership.get().getId(), actorUserId);
                if (!isAdmin && !isDelegated) {
                    throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
                }
            }
            default -> throw new BusinessException(VillageErrorCode.VILLAGE_POSTING_IDENTITY_FORBIDDEN);
        }
    }

    // ========================================================================
    // 共通ヘルパ
    // ========================================================================

    /**
     * 稼働中かつ操作者に可視な村を取得する（判定は {@link VillageAccessGate} に一元化）。
     *
     * <p>非公開(UNLISTED)村を非村人が叩いた場合は、実在しない村 ID と<b>同一の</b>
     * {@code VILLAGE_NOT_FOUND} を返して村の存在ごと秘匿する。公開(PUBLIC)村は素通りし、
     * 非村人かどうかの 403 判定は従来どおり本サービスの呼び出し元に残る。
     * 判定順序とその理由は {@link VillageAccessGate#loadActiveVillage} の Javadoc を参照。</p>
     */
    private VillageEntity loadActiveVillage(UUID villageId, Long actorUserId) {
        return accessGate.loadActiveVillage(villageId, actorUserId);
    }

    /**
     * 当該ユーザーが対象村の現役 USER 主体メンバーであるか。
     *
     * <p>村メンバーシップ（{@code village_memberships}）に基づく村メンバー判定の正準実装。
     * 村掲示板グローバル方式の閲覧認可（{@code VillageBulletinAccessService}）など、
     * village ドメイン内の他サービスからも参照されるため public とする。
     * 退会（{@code left_at}）・BAN（{@code banned_at}）済みは非メンバー扱い。</p>
     *
     * @param villageId 対象村 ID
     * @param userId    判定対象ユーザー ID
     * @return 現役 USER メンバーなら {@code true}
     */
    public boolean isUserVillageMember(UUID villageId, Long userId) {
        return membershipRepository
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(
                        villageId, VillageSubjectType.USER, userId)
                .filter(m -> m.getBannedAt() == null)
                .isPresent();
    }

    /**
     * 指定ユーザーが USER 主体として現役所属している村の ID 一覧を返す（認可根治 Wave3-B7-timeline）。
     *
     * <p>{@code timeline} ドメインの {@code TimelinePostService#getUserPosts} が「呼び出し元が
     * 見える VILLAGE スコープ」を絞り込むために利用する。
     * {@code com.mannschaft.app.membership.service.MembershipService#getActiveTeamIdsByUser}
     * と同じ思想（プリミティブのみ返却・ドメイン境界原則5・D-3 ArchUnit 準拠）で、village ドメインの
     * {@link VillageMembershipRepository} を他ドメインへ直接漏らさない越境窓口。
     * 退会（{@code left_at}）・BAN（{@code banned_at}）済みは除外（{@link #isUserVillageMember} と同一の現役定義）。</p>
     *
     * @param userId 対象ユーザー ID
     * @return 現役 USER メンバーとして所属する村の ID 一覧
     */
    public List<UUID> getActiveVillageIdsByUser(Long userId) {
        return membershipRepository.findActiveUserMemberships(userId).stream()
                .map(VillageMembershipEntity::getVillageId)
                .toList();
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
