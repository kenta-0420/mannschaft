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
import java.util.List;
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
        long supporterCount = userRoleRepository.countMembersByScopeAndRole("TEAM", team.getId(), "SUPPORTER");
        return ApiResponse.of(toResponse(team, 1, teamFriendCount, supporterCount));
    }

    /**
     * チームを取得する。
     */
    public ApiResponse<TeamResponse> getTeam(Long teamId) {
        TeamEntity team = findTeamOrThrow(teamId);
        int memberCount = (int) userRoleRepository.countByTeamId(teamId);
        long teamFriendCount = teamFriendRepository.countFriendsByTeamId(teamId);
        long supporterCount = userRoleRepository.countMembersByScopeAndRole("TEAM", teamId, "SUPPORTER");
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
        long supporterCount = userRoleRepository.countMembersByScopeAndRole("TEAM", teamId, "SUPPORTER");
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
                    long supporterCount = userRoleRepository.countMembersByScopeAndRole(
                            "TEAM", team.getId(), "SUPPORTER");
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
     */
    public PagedResponse<MemberResponse> getMembers(Long teamId, Pageable pageable) {
        findTeamOrThrow(teamId);

        Page<UserRoleEntity> page = userRoleRepository.findByTeamId(teamId, pageable);

        var data = page.getContent().stream()
                .map(ur -> {
                    UserRepository.MemberSummary user = userRepository.findMemberSummaryById(ur.getUserId()).orElse(null);
                    RoleEntity role = roleRepository.findById(ur.getRoleId()).orElse(null);
                    return new MemberResponse(
                            ur.getUserId(),
                            user != null ? user.getDisplayName() : null,
                            user != null ? user.getAvatarUrl() : null,
                            role != null ? role.getName() : null,
                            ur.getCreatedAt());
                })
                .toList();

        var meta = new PagedResponse.PageMeta(
                page.getTotalElements(), page.getNumber(), page.getSize(), page.getTotalPages());
        return PagedResponse.of(data, meta);
    }

    /**
     * SUPPORTERとしてチームをフォローする（自己登録）。
     */
    @Transactional
    public void followTeam(Long userId, Long teamId) {
        findTeamOrThrow(teamId);

        // ブロックチェック
        if (teamBlockRepository.existsByTeamIdAndUserId(teamId, userId)) {
            throw new BusinessException(TeamErrorCode.TEAM_004);
        }

        // 重複チェック
        if (userRoleRepository.existsByUserIdAndTeamId(userId, teamId)) {
            throw new BusinessException(TeamErrorCode.TEAM_003);
        }

        RoleEntity supporterRole = roleRepository.findByName("SUPPORTER")
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_005));

        UserRoleEntity userRole = UserRoleEntity.builder()
                .userId(userId)
                .roleId(supporterRole.getId())
                .teamId(teamId)
                .build();
        userRoleRepository.save(userRole);

        log.info("チームフォロー完了: userId={}, teamId={}", userId, teamId);
    }

    /**
     * SUPPORTERとしてのフォローを解除する。
     */
    @Transactional
    public void unfollowTeam(Long userId, Long teamId) {
        findTeamOrThrow(teamId);
        userRoleRepository.deleteByUserIdAndTeamId(userId, teamId);
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
