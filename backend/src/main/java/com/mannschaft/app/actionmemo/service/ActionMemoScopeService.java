package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.dto.AvailableOrgResponse;
import com.mannschaft.app.actionmemo.dto.AvailableTeamResponse;
import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * F02.5 行動メモ スコープ解決サービス。
 *
 * <p>ユーザーが利用可能なチーム・組織一覧の解決を担当する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActionMemoScopeService {

    private final UserRoleRepository userRoleRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;
    private final ActionMemoSettingsService settingsService;

    /**
     * ユーザーの所属チーム一覧（投稿先選択候補）を返す。
     *
     * @param userId 現在のユーザー ID
     * @return 所属チーム一覧
     */
    public List<AvailableTeamResponse> getAvailableTeams(Long userId) {
        // ユーザーのチーム所属一覧を取得（CMP-027: user_roles ∪ memberships の在籍チーム ID）
        List<Long> teamIds = userRoleRepository.findTeamIdsByUserId(userId);

        // デフォルト投稿先チームID
        Long defaultPostTeamId = settingsService.findSettings(userId)
                .map(UserActionMemoSettingsEntity::getDefaultPostTeamId)
                .orElse(null);

        return teamIds.stream()
                .distinct()
                .map(teamId -> teamRepository.findById(teamId).orElse(null))
                .filter(Objects::nonNull)
                .map(team -> AvailableTeamResponse.builder()
                        .id(team.getId())
                        .name(team.getName())
                        .isDefault(Objects.equals(team.getId(), defaultPostTeamId))
                        .build())
                .toList();
    }

    /**
     * Phase 5-2: ユーザーの所属組織一覧（組織スコープ投稿先選択候補）を返す。
     *
     * @param userId 現在のユーザー ID
     * @return 所属組織一覧
     */
    public List<AvailableOrgResponse> getAvailableOrgs(Long userId) {
        List<Long> orgIds = userRoleRepository.findOrganizationIdsByUserId(userId);

        return orgIds.stream()
                .distinct()
                .map(orgId -> organizationRepository.findById(orgId).orElse(null))
                .filter(Objects::nonNull)
                .map(org -> AvailableOrgResponse.builder()
                        .id(org.getId())
                        .name(org.getName())
                        .build())
                .toList();
    }
}
