package com.mannschaft.app.matching;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.dto.CreateMatchRequestRequest;
import com.mannschaft.app.matching.dto.MatchRequestCreateResponse;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.repository.MatchReviewRepository;
import com.mannschaft.app.matching.repository.NgTeamRepository;
import com.mannschaft.app.matching.service.MatchRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link MatchRequestService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchRequestService 単体テスト")
class MatchRequestServiceTest {

    @Mock
    private MatchRequestRepository requestRepository;
    @Mock
    private MatchProposalRepository proposalRepository;
    @Mock
    private MatchReviewRepository reviewRepository;
    @Mock
    private NgTeamRepository ngTeamRepository;

    @InjectMocks
    private MatchRequestService service;

    private static final Long TEAM_ID = 1L;
    private static final Long REQUEST_ID = 10L;

    @Nested
    @DisplayName("createRequest")
    class CreateRequest {

        @Test
        @DisplayName("正常系: 募集が正常に作成される")
        void 募集正常作成() {
            // Given
            CreateMatchRequestRequest request = new CreateMatchRequestRequest(
                    "テスト募集", null, "PRACTICE", null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, null, null);

            MatchRequestEntity saved = MatchRequestEntity.builder()
                    .teamId(TEAM_ID)
                    .title("テスト募集")
                    .build();
            given(requestRepository.save(any())).willReturn(saved);

            // When
            MatchRequestCreateResponse result = service.createRequest(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 開始日が終了日より後の場合エラー")
        void 日付範囲不正() {
            // Given
            CreateMatchRequestRequest request = new CreateMatchRequestRequest(
                    "テスト募集", null, "PRACTICE", null, null,
                    null, null, null,
                    LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 5),
                    null, null, null, null, null, null, null);

            // When & Then
            assertThatThrownBy(() -> service.createRequest(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.INVALID_DATE_RANGE);
        }

        @Test
        @DisplayName("異常系: 最小参加人数が最大参加人数を超過")
        void 参加人数範囲不正() {
            // Given
            CreateMatchRequestRequest request = new CreateMatchRequestRequest(
                    "テスト募集", null, "PRACTICE", null, null,
                    null, null, null, null, null, null, null, null,
                    (short) 20, (short) 10, null, null);

            // When & Then
            assertThatThrownBy(() -> service.createRequest(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.INVALID_PARTICIPANT_RANGE);
        }
    }

    @Nested
    @DisplayName("updateRequest")
    class UpdateRequest {

        @Test
        @DisplayName("異常系: 他チームの募集は更新不可")
        void 他チーム更新不可() {
            // Given
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(999L)
                    .title("他チーム募集")
                    .build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));

            CreateMatchRequestRequest request = new CreateMatchRequestRequest(
                    "更新", null, "PRACTICE", null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, null, null);

            // When & Then
            assertThatThrownBy(() -> service.updateRequest(REQUEST_ID, TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }
    }

    @Nested
    @DisplayName("deleteRequest")
    class DeleteRequest {

        @Test
        @DisplayName("異常系: 募集が見つからない場合エラー")
        void 募集不存在() {
            // Given
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.deleteRequest(REQUEST_ID, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.REQUEST_NOT_FOUND);
        }
    }
}
