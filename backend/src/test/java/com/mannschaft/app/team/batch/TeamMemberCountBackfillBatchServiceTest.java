package com.mannschaft.app.team.batch;

import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link TeamMemberCountBackfillBatchService} 単体テスト（F15.4 Phase 4）。
 *
 * <p>Repository 呼び出しの委譲・例外伝搬を Mockito で検証する。
 * 実 DB に対する再集計挙動は {@code TeamMemberCountBackfillBatchServiceIntegrationTest} で別途検証。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamMemberCountBackfillBatchService 単体テスト")
class TeamMemberCountBackfillBatchServiceTest {

    @Mock
    private TeamRepository teamRepository;

    @InjectMocks
    private TeamMemberCountBackfillBatchService batch;

    @Test
    @DisplayName("recalculateAll: TeamRepository#recalculateMemberCounts を 1 回呼ぶ")
    void recalculateAll_invokes_repository_once() {
        given(teamRepository.recalculateMemberCounts()).willReturn(42);

        batch.recalculateAll();

        verify(teamRepository, times(1)).recalculateMemberCounts();
    }

    @Test
    @DisplayName("recalculateAll: 更新件数 0 でも例外を投げない")
    void recalculateAll_zero_updates_no_exception() {
        given(teamRepository.recalculateMemberCounts()).willReturn(0);

        batch.recalculateAll();

        verify(teamRepository, times(1)).recalculateMemberCounts();
    }

    @Test
    @DisplayName("recalculateAll: Repository が例外を投げたら呼び出し元に伝搬する")
    void recalculateAll_propagates_exception() {
        given(teamRepository.recalculateMemberCounts())
                .willThrow(new RuntimeException("DB unreachable"));

        try {
            batch.recalculateAll();
            org.junit.jupiter.api.Assertions.fail("例外が伝搬されるはず");
        } catch (RuntimeException e) {
            // 例外を握り潰さず伝搬することを確認（次回バッチでリトライ可能にする）
            org.assertj.core.api.Assertions.assertThat(e.getMessage()).isEqualTo("DB unreachable");
        }
        verify(teamRepository, times(1)).recalculateMemberCounts();
    }
}
