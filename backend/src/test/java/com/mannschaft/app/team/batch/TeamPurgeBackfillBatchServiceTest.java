package com.mannschaft.app.team.batch;

import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link TeamPurgeBackfillBatchService} 単体テスト（Phase D-3）。
 *
 * <p>Repository 呼び出しの委譲・例外伝搬を Mockito で検証する。
 * 実 DB に対する孤児補正の動作確認は将来の統合テストで対応。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamPurgeBackfillBatchService 単体テスト")
class TeamPurgeBackfillBatchServiceTest {

    @Mock
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    @InjectMocks
    private TeamPurgeBackfillBatchService batch;

    @Test
    @DisplayName("backfill: invited_by と responded_by の孤児補正クエリをそれぞれ 1 回呼ぶ")
    void backfill_invokes_both_orphan_queries_once() {
        given(teamOrgMembershipRepository.nullifyOrphanInvitedBy()).willReturn(3);
        given(teamOrgMembershipRepository.nullifyOrphanRespondedBy()).willReturn(2);

        batch.backfill();

        verify(teamOrgMembershipRepository, times(1)).nullifyOrphanInvitedBy();
        verify(teamOrgMembershipRepository, times(1)).nullifyOrphanRespondedBy();
        verifyNoMoreInteractions(teamOrgMembershipRepository);
    }

    @Test
    @DisplayName("backfill: 孤児が 0 件のときも例外を投げない")
    void backfill_zero_orphans_no_exception() {
        given(teamOrgMembershipRepository.nullifyOrphanInvitedBy()).willReturn(0);
        given(teamOrgMembershipRepository.nullifyOrphanRespondedBy()).willReturn(0);

        batch.backfill();

        verify(teamOrgMembershipRepository, times(1)).nullifyOrphanInvitedBy();
        verify(teamOrgMembershipRepository, times(1)).nullifyOrphanRespondedBy();
    }

    @Test
    @DisplayName("backfill: nullifyOrphanInvitedBy が例外を投げたら呼び出し元に伝搬する")
    void backfill_propagates_exception_from_invited_by_query() {
        given(teamOrgMembershipRepository.nullifyOrphanInvitedBy())
                .willThrow(new RuntimeException("DB unreachable"));

        assertThatThrownBy(() -> batch.backfill())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB unreachable");

        verify(teamOrgMembershipRepository, times(1)).nullifyOrphanInvitedBy();
    }
}
