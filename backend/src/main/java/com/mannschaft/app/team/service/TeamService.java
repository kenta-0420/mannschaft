package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.event.TeamMemberRemovedEvent;
import com.mannschaft.app.team.repository.TeamRepository;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MembershipCreateRequest;
import com.mannschaft.app.membership.dto.MembershipLeaveRequest;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.role.dto.MemberResponse;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.dto.CreateTeamRequest;
import com.mannschaft.app.team.dto.TeamOrgSummaryResponse;
import com.mannschaft.app.team.dto.TeamResponse;
import com.mannschaft.app.team.dto.TeamSummaryResponse;
import com.mannschaft.app.team.dto.UpdateTeamRequest;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.mannschaft.app.team.service.TeamShiftSettingsService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チーム管理サービス。チームのCRUD・アーカイブ・メンバー一覧・SUPPORTER管理を提供する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class TeamService {

    private final TeamRepository teamRepository;
    private final TeamBlockRepository teamBlockRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TeamFriendRepository teamFriendRepository;
    private final TeamShiftSettingsService teamShiftSettingsService;
    private final MeterRegistry meterRegistry;
    private final MemberQueryDispatcher memberQueryDispatcher;
    private final MembershipService membershipService;
    private final MembershipRepository membershipRepository;

    /**
     * チームを作成し、作成者をADMINロールで紐付ける。
     */
    @Transactional
    public ApiResponse<TeamResponse> createTeam(Long userId, CreateTeamRequest req) {
        TeamEntity team = TeamEntity.builder()
                .name(req.getName())
                .template(req.getTemplate())
                .prefecture(req.getPrefecture())
                .city(req.getCity())
                .visibility(req.getVisibility() != null
                        ? TeamEntity.Visibility.valueOf(req.getVisibility())
                        : TeamEntity.Visibility.PRIVATE)
                .supporterEnabled(false)
                .build();
        teamRepository.save(team);

        // 作成者をADMINロールで紐付ける
        RoleEntity adminRole = roleRepository.findByName("ADMIN")
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_005));
        UserRoleEntity userRole = UserRoleEntity.builder()
                .userId(userId)
                .roleId(adminRole.getId())
                .teamId(team.getId())
                .build();
        userRoleRepository.save(userRole);

        // チームシフト設定をデフォルト値で初期化
        teamShiftSettingsService.initializeDefaultSettings(team.getId());

        log.info("チーム作成完了: teamId={}, userId={}", team.getId(), userId);
        long teamFriendCount = teamFriendRepository.countFriendsByTeamId(team.getId());
        // F00.5 Phase 5: SUPPORTER カウントを memberships 経由に切替
        long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                ScopeType.TEAM, team.getId(), RoleKind.SUPPORTER);
        return ApiResponse.of(toResponse(team, 1, teamFriendCount, supporterCount));
    }

    /**
     * チームを取得する。
     */
    public ApiResponse<TeamResponse> getTeam(Long teamId) {
        TeamEntity team = findTeamOrThrow(teamId);
        int memberCount = (int) userRoleRepository.countByTeamId(teamId);
        long teamFriendCount = teamFriendRepository.countFriendsByTeamId(teamId);
        // F00.5 Phase 5: SUPPORTER カウントを memberships 経由に切替
        long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        return ApiResponse.of(toResponse(team, memberCount, teamFriendCount, supporterCount));
    }

    /**
     * チームを更新する。
     */
    @Transactional
    public ApiResponse<TeamResponse> updateTeam(Long teamId, UpdateTeamRequest req) {
        TeamEntity team = findTeamOrThrow(teamId);
        checkNotArchived(team);

        TeamEntity updated = team.toBuilder()
                .name(req.getName() != null ? req.getName() : team.getName())
                .nameKana(req.getNameKana() != null ? req.getNameKana() : team.getNameKana())
                .nickname1(req.getNickname1() != null ? req.getNickname1() : team.getNickname1())
                .nickname2(req.getNickname2() != null ? req.getNickname2() : team.getNickname2())
                .template(req.getTemplate() != null ? req.getTemplate() : team.getTemplate())
                .prefecture(req.getPrefecture() != null ? req.getPrefecture() : team.getPrefecture())
                .city(req.getCity() != null ? req.getCity() : team.getCity())
                .visibility(req.getVisibility() != null
                        ? TeamEntity.Visibility.valueOf(req.getVisibility())
                        : team.getVisibility())
                .supporterEnabled(req.getSupporterEnabled() != null ? req.getSupporterEnabled() : team.getSupporterEnabled())
                .build();
        teamRepository.save(updated);

        int memberCount = (int) userRoleRepository.countByTeamId(teamId);
        long teamFriendCount = teamFriendRepository.countFriendsByTeamId(teamId);
        // F00.5 Phase 5: SUPPORTER カウントを memberships 経由に切替
        long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                ScopeType.TEAM, teamId, RoleKind.SUPPORTER);
        log.info("チーム更新完了: teamId={}", teamId);
        return ApiResponse.of(toResponse(updated, memberCount, teamFriendCount, supporterCount));
    }

    /**
     * チームを論理削除する。
     */
    @Transactional
    public void deleteTeam(Long teamId) {
        TeamEntity team = findTeamOrThrow(teamId);
        team.softDelete();
        log.info("チーム削除完了: teamId={}", teamId);
    }

    /**
     * チームをアーカイブする。
     */
    @Transactional
    public void archiveTeam(Long teamId) {
        TeamEntity team = findTeamOrThrow(teamId);
        if (team.getArchivedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_002);
        }
        team.archive();
        log.info("チームアーカイブ完了: teamId={}", teamId);
    }

    /**
     * チームのアーカイブを解除する。
     */
    @Transactional
    public void unarchiveTeam(Long teamId) {
        TeamEntity team = findTeamOrThrow(teamId);
        team.unarchive();
        log.info("チームアーカイブ解除完了: teamId={}", teamId);
    }

    /**
     * チームをキーワード検索する。
     */
    public PagedResponse<TeamSummaryResponse> searchTeams(String keyword, Pageable pageable) {
        Page<TeamEntity> page = teamRepository.searchByKeyword(
                keyword != null ? keyword : "", pageable);

        var data = page.getContent().stream()
                .map(team -> {
                    int memberCount = (int) userRoleRepository.countByTeamId(team.getId());
                    long teamFriendCount = teamFriendRepository.countFriendsByTeamId(team.getId());
                    // F00.5 Phase 5: SUPPORTER カウントを memberships 経由に切替
                    long supporterCount = membershipRepository.countActiveByScopeAndRoleKind(
                            ScopeType.TEAM, team.getId(), RoleKind.SUPPORTER);
                    return new TeamSummaryResponse(
                            team.getId(), team.getName(), team.getTemplate(),
                            team.getVisibility().name(), memberCount,
                            teamFriendCount, supporterCount);
                })
                .toList();

        var meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(data, meta);
    }

    /**
     * チームのメンバー一覧を取得する。
     *
     * <p>F00.5 Phase 3: MemberQueryDispatcher 経由で memberships + user_roles を統合参照する。
     * Phase 2 の Shadow Mode（user_roles のみ参照）は廃止。</p>
     *
     * <p>設計書: docs/features/F00.5_membership_basis.md §7 / §13.6.4</p>
     */
    public PagedResponse<MemberResponse> getMembers(Long teamId, Pageable pageable) {
        findTeamOrThrow(teamId);

        // F00.5 Phase 3: MemberQueryDispatcher 経由で memberships 参照に完全切替
        var memberDtos = memberQueryDispatcher.queryMembers(teamId, ScopeType.TEAM, null);

        var data = memberDtos.stream()
                .map(dto -> new MemberResponse(
                        dto.userId(),
                        dto.displayName(),
                        dto.avatarUrl(),
                        dto.roleName(),
                        dto.joinedAt()))
                .toList();

        // Dispatcher は全件リストを返すため、ページネーションはアプリ側でエミュレート
        int page = pageable.isPaged() ? pageable.getPageNumber() : 0;
        int size = pageable.isPaged() ? pageable.getPageSize() : data.size();
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, data.size());
        List<MemberResponse> pagedData = (fromIndex >= data.size())
                ? List.<MemberResponse>of() : data.subList(fromIndex, toIndex);

        long totalElements = data.size();
        int totalPages = size == 0 ? 1 : (int) Math.ceil((double) totalElements / size);

        var meta = new PagedResponse.PageMeta(totalElements, page, size, totalPages);
        return PagedResponse.of(pagedData, meta);
    }

    /**
     * SUPPORTERとしてチームをフォローする（自己登録）。
     *
     * <p>F00.5 Phase 5: memberships への書き込みに切替。MembershipService.join() 経由で
     * 冪等性保証・イベント発火を一本化する。</p>
     */
    @Transactional
    public void followTeam(Long userId, Long teamId) {
        findTeamOrThrow(teamId);

        // ブロックチェック
        if (teamBlockRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new BusinessException(TeamErrorCode.TEAM_004);
        }

        // 重複チェック（memberships に既にアクティブな SUPPORTER がいる場合）
        if (membershipRepository.existsActiveByUserAndScopeAndRoleKind(
                userId, ScopeType.TEAM, teamId, RoleKind.SUPPORTER)) {
            throw new BusinessException(TeamErrorCode.TEAM_003);
        }

        // F00.5 Phase 5: memberships に SUPPORTER として入会
        MembershipCreateRequest req = new MembershipCreateRequest();
        req.setUserId(userId);
        req.setScopeType(ScopeType.TEAM);
        req.setScopeId(teamId);
        req.setRoleKind(RoleKind.SUPPORTER);
        req.setSource("SELF_FOLLOW");
        membershipService.join(req);

        log.info("チームフォロー完了: userId={}, teamId={}", userId, teamId);
    }

    /**
     * SUPPORTERとしてのフォローを解除する。
     *
     * <p>F00.5 Phase 5: memberships への退会処理に切替。MembershipService.leave() 経由で
     * 退会履歴・イベント発火を一本化する。</p>
     */
    @Transactional
    public void unfollowTeam(Long userId, Long teamId) {
        findTeamOrThrow(teamId);

        // F00.5 Phase 5: memberships から SUPPORTER として退会
        Optional<MembershipEntity> active = membershipRepository.findActiveByUserAndScope(
                userId, ScopeType.TEAM, teamId);
        if (active.isPresent()) {
            MembershipLeaveRequest leaveReq = new MembershipLeaveRequest();
            leaveReq.setLeaveReason(LeaveReason.SELF);
            membershipService.leave(active.get().getId(), leaveReq);
        }

        // Phase 3: チームメンバー脱退イベント発行（行動メモのデフォルト投稿先リセット用）
        eventPublisher.publishEvent(new TeamMemberRemovedEvent(userId, teamId));
        log.info("チームフォロー解除完了: userId={}, teamId={}", userId, teamId);
    }

    /**
     * チームが所属する組織一覧を取得する。
     */
    public List<TeamOrgSummaryResponse> getOrganizations(Long teamId) {
        findTeamOrThrow(teamId);
        return teamOrgMembershipRepository.findByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE)
                .stream()
                .map(m -> organizationRepository.findById(m.getOrganizationId()).orElse(null))
                .filter(org -> org != null)
                .map(org -> new TeamOrgSummaryResponse(
                        org.getId(),
                        org.getName(),
                        null,
                        org.getVisibility().name(),
                        (int) userRoleRepository.countByOrganizationId(org.getId())))
                .toList();
    }

    /**
     * 論理削除済みチームを復元する（SYSTEM_ADMIN専用）。
     */
    @Transactional
    public void restoreTeam(Long teamId) {
        if (teamRepository.countByIdIncludingDeleted(teamId) == 0) {
            throw new BusinessException(TeamErrorCode.TEAM_001);
        }
        int updated = teamRepository.restoreById(teamId);
        if (updated == 0) {
            throw new BusinessException(TeamErrorCode.TEAM_006);
        }
        log.info("チーム復元完了: teamId={}", teamId);
    }

    // ========================================
    // ヘルパー（private）
    // ========================================

    private TeamEntity findTeamOrThrow(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));
    }

    private void checkNotArchived(TeamEntity team) {
        if (team.getArchivedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_002);
        }
    }

    private TeamResponse toResponse(TeamEntity team, int memberCount,
                                     long teamFriendCount, long supporterCount) {
        return new TeamResponse(
                team.getId(), team.getName(), team.getNameKana(),
                team.getNickname1(), team.getNickname2(), team.getTemplate(),
                team.getPrefecture(), team.getCity(), team.getVisibility().name(),
                team.getSupporterEnabled(), team.getVersion(),
                memberCount, team.getIconUrl(), team.getBannerUrl(),
                team.getArchivedAt(), team.getCreatedAt(),
                teamFriendCount, supporterCount);
    }
}
