package com.mannschaft.app.common.visibility;

import com.mannschaft.app.team.repository.TeamOrgIdProjection;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ScopeAncestorResolver} の単体テスト。
 *
 * <p>F00 Phase A-3b — TEAM スコープの親 ORG をバルク解決し、
 * ORGANIZATION スコープはそのまま返すことを検証する。Repository は Mock。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScopeAncestorResolver — TEAM → 親 ORG バルク解決")
class ScopeAncestorResolverTest {

    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @InjectMocks
    private ScopeAncestorResolver resolver;

    @BeforeEach
    void setUp() {
        // 各テストで stub をセット
    }

    @Test
    @DisplayName("TEAM 集合に対して親 ORG をバルク解決する")
    void TEAM集合_親ORGバルク解決() {
        ScopeKey team1 = new ScopeKey("TEAM", 1L);
        ScopeKey team2 = new ScopeKey("TEAM", 2L);

        Map<Long, Long> teamToOrg = new HashMap<>();
        teamToOrg.put(1L, 10L);
        teamToOrg.put(2L, 20L);
        when(teamOrgMembershipRepository.findOrganizationIdByTeamIdIn(Set.of(1L, 2L)))
                .thenReturn(teamToOrg);

        Map<ScopeKey, Long> result = resolver.resolveParentOrgIds(Set.of(team1, team2));

        assertThat(result).hasSize(2);
        assertThat(result.get(team1)).isEqualTo(10L);
        assertThat(result.get(team2)).isEqualTo(20L);
    }

    @Test
    @DisplayName("ORGANIZATION スコープは自身を親 ORG ID としてパススルー")
    void ORGスコープはパススルー() {
        ScopeKey org10 = new ScopeKey("ORGANIZATION", 10L);
        ScopeKey org20 = new ScopeKey("ORGANIZATION", 20L);

        Map<ScopeKey, Long> result = resolver.resolveParentOrgIds(Set.of(org10, org20));

        // ORG のみなら Repository は呼ばれない
        verify(teamOrgMembershipRepository, never()).findOrganizationIdByTeamIdIn(anySet());
        assertThat(result).hasSize(2);
        assertThat(result.get(org10)).isEqualTo(10L);
        assertThat(result.get(org20)).isEqualTo(20L);
    }

    @Test
    @DisplayName("TEAM/ORG 混在は両方とも結果に含まれる")
    void TEAM_ORG混在() {
        ScopeKey team1 = new ScopeKey("TEAM", 1L);
        ScopeKey org20 = new ScopeKey("ORGANIZATION", 20L);

        when(teamOrgMembershipRepository.findOrganizationIdByTeamIdIn(Set.of(1L)))
                .thenReturn(Map.of(1L, 10L));

        Map<ScopeKey, Long> result = resolver.resolveParentOrgIds(Set.of(team1, org20));

        assertThat(result).hasSize(2);
        assertThat(result.get(team1)).isEqualTo(10L);
        assertThat(result.get(org20)).isEqualTo(20L);
    }

    @Test
    @DisplayName("TEAM の親 ORG が見つからない場合はそのスコープを結果に含めない")
    void TEAM未所属はスキップ() {
        ScopeKey team1 = new ScopeKey("TEAM", 1L);
        ScopeKey team2 = new ScopeKey("TEAM", 2L);

        // team2 は ACTIVE 所属無し → マップに登場しない
        when(teamOrgMembershipRepository.findOrganizationIdByTeamIdIn(Set.of(1L, 2L)))
                .thenReturn(Map.of(1L, 10L));

        Map<ScopeKey, Long> result = resolver.resolveParentOrgIds(Set.of(team1, team2));

        assertThat(result).hasSize(1);
        assertThat(result).containsKey(team1);
        assertThat(result).doesNotContainKey(team2);
    }

    @Test
    @DisplayName("空集合では Repository を呼ばず空マップを返す")
    void 空集合はSQL未発行() {
        Map<ScopeKey, Long> result = resolver.resolveParentOrgIds(Collections.emptySet());

        assertThat(result).isEmpty();
        verify(teamOrgMembershipRepository, never()).findOrganizationIdByTeamIdIn(any());
    }

    @Test
    @DisplayName("null は空マップを返す")
    void null_空マップ() {
        Map<ScopeKey, Long> result = resolver.resolveParentOrgIds(null);
        assertThat(result).isEmpty();
        verify(teamOrgMembershipRepository, never()).findOrganizationIdByTeamIdIn(any());
    }

    @Test
    @DisplayName("ORG のみの場合 Repository を呼ばない（teamIds 集合が空のため）")
    void ORGのみは_Repository呼ばれない() {
        ScopeKey org10 = new ScopeKey("ORGANIZATION", 10L);

        Map<ScopeKey, Long> result = resolver.resolveParentOrgIds(Set.of(org10));

        assertThat(result).hasSize(1);
        verify(teamOrgMembershipRepository, never()).findOrganizationIdByTeamIdIn(any());
    }

    /**
     * 補助: TeamOrgIdProjection を作るためのインターフェース動的実装。
     * 当テストでは Repository.findOrganizationIdByTeamIdIn (default method の戻り値: Map) を
     * 直接 stub するため、このヘルパは現状不要。互換のためダミー実装は残しておく。
     */
    private TeamOrgIdProjection projectionOf(Long teamId, Long organizationId) {
        return new TeamOrgIdProjection() {
            @Override
            public Long getTeamId() {
                return teamId;
            }

            @Override
            public Long getOrganizationId() {
                return organizationId;
            }
        };
    }
}
