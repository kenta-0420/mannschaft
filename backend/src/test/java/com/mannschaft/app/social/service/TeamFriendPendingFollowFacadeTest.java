package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.social.dto.PendingTeamFollowListResponse;
import com.mannschaft.app.team.service.TeamFollowTargetQueryService;
import com.mannschaft.app.team.service.TeamFollowTargetSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CMP-261021-0020 AC9/10: 段取り境界の例外伝播と一括照会の試練。
 * DB/認可そのものは実MySQL HTTP契約ITで検査し、本試験は呼出順・呼出数だけを検査する。
 * 陣立て書で単体検証を指定された境界契約のため、協力Beanをモックする。
 */
@ExtendWith(MockitoExtension.class)
class TeamFriendPendingFollowFacadeTest {

    private static final Long TEAM_ID = 10L;
    private static final Long USER_ID = 20L;
    private static final PageRequest PAGE = PageRequest.of(0, 20);
    private static final LocalDateTime FOLLOWED_AT = LocalDateTime.of(2026, 1, 1, 12, 0);

    @Mock private AccessControlService accessControlService;
    @Mock private TeamFriendPendingFollowQueryService queryService;
    @Mock private TeamFollowTargetQueryService targetQueryService;

    private TeamFriendPendingFollowFacade facade;

    @BeforeEach
    void 型骨格へ協力者を渡す() {
        facade = new TeamFriendPendingFollowFacade(accessControlService, queryService, targetQueryService);
    }

    @Test
    void AC9_チーム概要取得例外を伏せ字や部分成功に変えずそのまま伝播する() {
        RuntimeException failure = new IllegalStateException("概要取得障害");
        when(queryService.findPendingFollows(TEAM_ID, PAGE)).thenReturn(new PageImpl<>(List.of(
                new PendingTeamFollowRow(1L, 100L, false, FOLLOWED_AT)), PAGE, 1));
        when(targetQueryService.findDisclosableSummaries(any())).thenThrow(failure);

        assertThatThrownBy(() -> facade.getPendingFollows(TEAM_ID, USER_ID, PAGE)).isSameAs(failure);
    }

    @Test
    void AC10_認可後にsocialを読みページ内ID集合を一度だけteamへ照会する() {
        when(queryService.findPendingFollows(TEAM_ID, PAGE)).thenReturn(new PageImpl<>(List.of(
                new PendingTeamFollowRow(2L, 100L, true, FOLLOWED_AT),
                new PendingTeamFollowRow(1L, 101L, false, FOLLOWED_AT)), PAGE, 2));
        when(targetQueryService.findDisclosableSummaries(any())).thenReturn(Map.of(
                100L, new TeamFollowTargetSummary(100L, "公開チーム", "public-team")));

        PendingTeamFollowListResponse response = facade.getPendingFollows(TEAM_ID, USER_ID, PAGE);

        var order = inOrder(accessControlService, queryService, targetQueryService);
        order.verify(accessControlService).checkPermission(USER_ID, TEAM_ID, "TEAM", "MANAGE_FRIEND_TEAMS");
        order.verify(queryService).findPendingFollows(TEAM_ID, PAGE);
        order.verify(targetQueryService).findDisclosableSummaries(org.mockito.ArgumentMatchers.argThat(
                ids -> ids.size() == 2 && ids.containsAll(List.of(100L, 101L))));
        verify(targetQueryService, times(1)).findDisclosableSummaries(any());
        assertThat(response.data()).hasSize(2);
        assertThat(response.data().get(0).nameHidden()).isFalse();
        assertThat(response.data().get(0).targetTeamName()).isEqualTo("公開チーム");
        assertThat(response.data().get(1).nameHidden()).isTrue();
        assertThat(response.data().get(1).targetTeamName()).isNull();
        assertThat(response.data().get(1).targetTeamSlug()).isNull();
    }
}
