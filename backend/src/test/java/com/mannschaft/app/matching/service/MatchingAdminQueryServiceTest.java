package com.mannschaft.app.matching.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.matching.MatchProposalStatus;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F10.1.1 / P1: {@link MatchingAdminQueryService} 単体テスト。
 * 番人テスト: 受け手 team_id（募集を出したチーム）+ PENDING で集計していること（テナント越境防止）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchingAdminQueryService 単体テスト")
class MatchingAdminQueryServiceTest {

    @Mock
    private MatchProposalRepository matchProposalRepository;
    @Mock
    private NameResolverService nameResolverService;

    @InjectMocks
    private MatchingAdminQueryService service;

    private static final Long TEAM_ID = 10L;
    private static final String TEAM_SLUG = "dev-team";

    @Test
    @DisplayName("preview_size=0 → 受け手 team_id+PENDING 件数のみ・プレビューは呼ばない")
    void countOnly() {
        given(matchProposalRepository.countPendingReceivedByTeam(TEAM_ID, MatchProposalStatus.PENDING))
                .willReturn(4L);

        PendingAggregate result = service.pendingReceivedForTeam(TEAM_ID, TEAM_SLUG, 0);

        assertThat(result.pendingCount()).isEqualTo(4);
        assertThat(result.items()).isEmpty();
        verify(matchProposalRepository, never())
                .findPendingReceivedByTeam(any(), any(), any());
    }

    @Test
    @DisplayName("preview_size>0 → 応募元チーム名バルク解決・detail_route は id を含む個別遷移先")
    void countAndPreview() {
        given(matchProposalRepository.countPendingReceivedByTeam(TEAM_ID, MatchProposalStatus.PENDING))
                .willReturn(1L);
        MatchProposalEntity p = MatchProposalEntity.builder()
                .requestId(5L).proposingTeamId(33L).status(MatchProposalStatus.PENDING)
                .message("練習試合お願いします").build();
        ReflectionTestUtils.setField(p, "id", 7L);
        given(matchProposalRepository.findPendingReceivedByTeam(
                eq(TEAM_ID), eq(MatchProposalStatus.PENDING), any()))
                .willReturn(List.of(p));
        given(nameResolverService.resolveTeamNames(any())).willReturn(Map.of(33L, "鈴木FC"));

        PendingAggregate result = service.pendingReceivedForTeam(TEAM_ID, TEAM_SLUG, 3);

        assertThat(result.items()).hasSize(1);
        PendingAggregate.Item item = result.items().get(0);
        assertThat(item.requestedBy()).isEqualTo("鈴木FC");
        assertThat(item.title()).isEqualTo("練習試合お願いします");
        // id は主キーの文字列（§3.3）・detail_route は id を含む個別遷移先（§3.1）
        assertThat(item.id()).isEqualTo("7");
        assertThat(item.detailRoute()).isEqualTo("/teams/dev-team/admin/matching/7");
    }
}
