package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * CMP-261012-0315 AC-7: フレンドチームのフォロー先判定（{@code resolveFollowableTeamId} / {@code isFollowableTeam}）。
 * 不在・論理削除済み・アーカイブ済み（ACTIVE のまま archivedAt あり）・PROVISIONED は不可、通常の ACTIVE は可。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-261012-0315 TeamService フォロー先判定")
class TeamServiceFollowableTeamTest {

    @Mock
    private TeamRepository teamRepository;

    @InjectMocks
    private TeamService teamService;

    private static final Long ID = 20L;
    private static final String SLUG = "rival-fc";

    private static TeamEntity team() {
        TeamEntity entity = TeamEntity.builder().name("相手").slug(SLUG).build();
        ReflectionTestUtils.setField(entity, "id", ID);
        return entity;
    }

    private static TeamEntity archived() {
        TeamEntity entity = team();
        ReflectionTestUtils.setField(entity, "archivedAt", LocalDateTime.of(2026, 10, 1, 0, 0));
        return entity;
    }

    private static TeamEntity provisioned() {
        TeamEntity entity = team();
        ReflectionTestUtils.setField(entity, "lifecycleStatus", TeamEntity.LifecycleStatus.PROVISIONED);
        return entity;
    }

    @Nested
    @DisplayName("resolveFollowableTeamId（slug）")
    class ResolveBySlug {

        @Test
        @DisplayName("通常の ACTIVE チームは ID を返す")
        void active() {
            given(teamRepository.findBySlugAndDeletedAtIsNull(SLUG)).willReturn(Optional.of(team()));
            assertThat(teamService.resolveFollowableTeamId(SLUG)).contains(ID);
        }

        @Test
        @DisplayName("不在・論理削除済みは空")
        void absent() {
            given(teamRepository.findBySlugAndDeletedAtIsNull(SLUG)).willReturn(Optional.empty());
            assertThat(teamService.resolveFollowableTeamId(SLUG)).isEmpty();
        }

        @Test
        @DisplayName("アーカイブ済みは空")
        void archivedIsEmpty() {
            given(teamRepository.findBySlugAndDeletedAtIsNull(SLUG)).willReturn(Optional.of(archived()));
            assertThat(teamService.resolveFollowableTeamId(SLUG)).isEmpty();
        }

        @Test
        @DisplayName("PROVISIONED（ACTIVE 以外）は空")
        void provisionedIsEmpty() {
            given(teamRepository.findBySlugAndDeletedAtIsNull(SLUG)).willReturn(Optional.of(provisioned()));
            assertThat(teamService.resolveFollowableTeamId(SLUG)).isEmpty();
        }
    }

    @Nested
    @DisplayName("isFollowableTeam（ID）")
    class ById {

        @Test
        @DisplayName("通常の ACTIVE チームは true")
        void active() {
            given(teamRepository.findById(ID)).willReturn(Optional.of(team()));
            assertThat(teamService.isFollowableTeam(ID)).isTrue();
        }

        @Test
        @DisplayName("不在・論理削除済み（@SQLRestriction で findById が空）は false")
        void absent() {
            given(teamRepository.findById(ID)).willReturn(Optional.empty());
            assertThat(teamService.isFollowableTeam(ID)).isFalse();
        }

        @Test
        @DisplayName("アーカイブ済みは false")
        void archivedIsFalse() {
            given(teamRepository.findById(ID)).willReturn(Optional.of(archived()));
            assertThat(teamService.isFollowableTeam(ID)).isFalse();
        }

        @Test
        @DisplayName("PROVISIONED（ACTIVE 以外）は false")
        void provisionedIsFalse() {
            given(teamRepository.findById(ID)).willReturn(Optional.of(provisioned()));
            assertThat(teamService.isFollowableTeam(ID)).isFalse();
        }
    }
}
