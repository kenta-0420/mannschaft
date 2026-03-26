package com.mannschaft.app.matching;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.dto.CreateReviewRequest;
import com.mannschaft.app.matching.dto.ReviewCreateResponse;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.entity.MatchReviewEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.repository.MatchReviewRepository;
import com.mannschaft.app.matching.service.MatchReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link MatchReviewService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchReviewService 単体テスト")
class MatchReviewServiceTest {

    @Mock
    private MatchReviewRepository reviewRepository;
    @Mock
    private MatchProposalRepository proposalRepository;
    @Mock
    private MatchRequestRepository requestRepository;
    @Mock
    private MatchingMapper matchingMapper;

    @InjectMocks
    private MatchReviewService service;

    private static final Long TEAM_A = 1L;
    private static final Long TEAM_B = 2L;
    private static final Long PROPOSAL_ID = 10L;

    @Nested
    @DisplayName("createReview")
    class CreateReview {

        @Test
        @DisplayName("異常系: 応募が見つからない場合エラー")
        void 応募不存在() {
            // Given
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.empty());
            CreateReviewRequest request = new CreateReviewRequest(PROPOSAL_ID, (short) 4, "良い試合でした", true);

            // When & Then
            assertThatThrownBy(() -> service.createReview(TEAM_A, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.PROPOSAL_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: 関与していないチームはレビュー不可")
        void 非関与チームレビュー不可() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(100L)
                    .proposingTeamId(TEAM_B)
                    .build();
            // Set status to ACCEPTED via reflection
            setProposalStatus(proposal, MatchProposalStatus.ACCEPTED);

            MatchRequestEntity matchRequest = MatchRequestEntity.builder()
                    .teamId(TEAM_B)
                    .build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findById(100L)).willReturn(Optional.of(matchRequest));

            Long unrelatedTeam = 999L;
            CreateReviewRequest request = new CreateReviewRequest(PROPOSAL_ID, (short) 4, "テスト", true);

            // When & Then
            assertThatThrownBy(() -> service.createReview(unrelatedTeam, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.REVIEW_NOT_PARTICIPANT);
        }

        @Test
        @DisplayName("異常系: レビュー重複エラー")
        void レビュー重複() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(100L)
                    .proposingTeamId(TEAM_B)
                    .build();
            setProposalStatus(proposal, MatchProposalStatus.ACCEPTED);
            setUpdatedAt(proposal, LocalDateTime.now().minusDays(1));

            MatchRequestEntity matchRequest = MatchRequestEntity.builder()
                    .teamId(TEAM_A)
                    .build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findById(100L)).willReturn(Optional.of(matchRequest));
            given(reviewRepository.existsByProposalIdAndReviewerTeamId(PROPOSAL_ID, TEAM_A)).willReturn(true);

            CreateReviewRequest request = new CreateReviewRequest(PROPOSAL_ID, (short) 5, "テスト", true);

            // When & Then
            assertThatThrownBy(() -> service.createReview(TEAM_A, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.DUPLICATE_REVIEW);
        }

        @Test
        @DisplayName("正常系: レビューが正常に作成される")
        void レビュー正常作成() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(100L)
                    .proposingTeamId(TEAM_B)
                    .build();
            setProposalStatus(proposal, MatchProposalStatus.ACCEPTED);
            setUpdatedAt(proposal, LocalDateTime.now().minusDays(1));

            MatchRequestEntity matchRequest = MatchRequestEntity.builder()
                    .teamId(TEAM_A)
                    .build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findById(100L)).willReturn(Optional.of(matchRequest));
            given(reviewRepository.existsByProposalIdAndReviewerTeamId(PROPOSAL_ID, TEAM_A)).willReturn(false);

            MatchReviewEntity saved = MatchReviewEntity.builder()
                    .proposalId(PROPOSAL_ID)
                    .reviewerTeamId(TEAM_A)
                    .revieweeTeamId(TEAM_B)
                    .rating((short) 5)
                    .build();
            given(reviewRepository.save(any())).willReturn(saved);

            CreateReviewRequest request = new CreateReviewRequest(PROPOSAL_ID, (short) 5, "素晴らしい", true);

            // When
            ReviewCreateResponse result = service.createReview(TEAM_A, request);

            // Then
            assertThat(result.getRating()).isEqualTo((short) 5);
            assertThat(result.getRevieweeTeamId()).isEqualTo(TEAM_B);
        }
    }

    private void setProposalStatus(MatchProposalEntity proposal, MatchProposalStatus status) {
        try {
            Field field = MatchProposalEntity.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(proposal, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setUpdatedAt(MatchProposalEntity proposal, LocalDateTime updatedAt) {
        try {
            Field field = proposal.getClass().getSuperclass().getDeclaredField("updatedAt");
            field.setAccessible(true);
            field.set(proposal, updatedAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
