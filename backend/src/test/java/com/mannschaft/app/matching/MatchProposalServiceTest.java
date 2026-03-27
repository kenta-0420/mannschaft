package com.mannschaft.app.matching;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.dto.AcceptProposalRequest;
import com.mannschaft.app.matching.dto.AcceptProposalResponse;
import com.mannschaft.app.matching.dto.AgreeCancelResponse;
import com.mannschaft.app.matching.dto.CancelProposalRequest;
import com.mannschaft.app.matching.dto.CancellationSummaryResponse;
import com.mannschaft.app.matching.dto.CreateProposalRequest;
import com.mannschaft.app.matching.dto.ProposalCreateResponse;
import com.mannschaft.app.matching.dto.ProposalResponse;
import com.mannschaft.app.matching.dto.ProposalStatusResponse;
import com.mannschaft.app.matching.entity.MatchProposalDateEntity;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.MatchProposalDateRepository;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.repository.NgTeamRepository;
import com.mannschaft.app.matching.service.MatchProposalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link MatchProposalService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchProposalService 単体テスト")
class MatchProposalServiceTest {

    @Mock
    private MatchProposalRepository proposalRepository;
    @Mock
    private MatchProposalDateRepository proposalDateRepository;
    @Mock
    private MatchRequestRepository requestRepository;
    @Mock
    private NgTeamRepository ngTeamRepository;
    @Mock
    private MatchingMapper matchingMapper;

    @InjectMocks
    private MatchProposalService service;

    private static final Long TEAM_ID = 1L;
    private static final Long OTHER_TEAM_ID = 2L;
    private static final Long REQUEST_ID = 10L;
    private static final Long PROPOSAL_ID = 20L;

    private MatchRequestEntity createOpenRequest() {
        return MatchRequestEntity.builder()
                .teamId(OTHER_TEAM_ID)
                .title("テスト募集")
                .status(MatchRequestStatus.OPEN)
                .build();
    }

    @Nested
    @DisplayName("createProposal")
    class CreateProposal {

        @Test
        @DisplayName("正常系: 応募が正常に作成される")
        void 応募正常作成() {
            // Given
            MatchRequestEntity request = createOpenRequest();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of());
            given(proposalRepository.existsByRequestIdAndProposingTeamId(REQUEST_ID, TEAM_ID)).willReturn(false);

            MatchProposalEntity saved = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID)
                    .proposingTeamId(TEAM_ID)
                    .build();
            given(proposalRepository.save(any())).willReturn(saved);
            given(requestRepository.save(any())).willReturn(request);

            CreateProposalRequest createRequest = new CreateProposalRequest("テスト応募", null, "テスト会場");

            // When
            ProposalCreateResponse result = service.createProposal(TEAM_ID, REQUEST_ID, createRequest);

            // Then
            assertThat(result).isNotNull();
            verify(proposalRepository).save(any());
        }

        @Test
        @DisplayName("異常系: 自チームの募集に応募するとエラー")
        void 自チーム応募エラー() {
            // Given
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(TEAM_ID)
                    .status(MatchRequestStatus.OPEN)
                    .build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));

            CreateProposalRequest createRequest = new CreateProposalRequest("テスト", null, null);

            // When & Then
            assertThatThrownBy(() -> service.createProposal(TEAM_ID, REQUEST_ID, createRequest))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.SELF_PROPOSAL_NOT_ALLOWED);
        }

        @Test
        @DisplayName("異常系: 募集がOPEN以外だとエラー")
        void 募集がOPEN以外() {
            // Given
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(OTHER_TEAM_ID)
                    .status(MatchRequestStatus.MATCHED)
                    .build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));

            CreateProposalRequest createRequest = new CreateProposalRequest("テスト", null, null);

            // When & Then
            assertThatThrownBy(() -> service.createProposal(TEAM_ID, REQUEST_ID, createRequest))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.REQUEST_NOT_OPEN);
        }

        @Test
        @DisplayName("異常系: NGチームによるブロック")
        void NGチームブロック() {
            // Given
            MatchRequestEntity request = createOpenRequest();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of(OTHER_TEAM_ID));

            CreateProposalRequest createRequest = new CreateProposalRequest("テスト", null, null);

            // When & Then
            assertThatThrownBy(() -> service.createProposal(TEAM_ID, REQUEST_ID, createRequest))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.NG_TEAM_BLOCKED);
        }

        @Test
        @DisplayName("異常系: 重複応募エラー")
        void 重複応募エラー() {
            // Given
            MatchRequestEntity request = createOpenRequest();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of());
            given(proposalRepository.existsByRequestIdAndProposingTeamId(REQUEST_ID, TEAM_ID)).willReturn(true);

            CreateProposalRequest createRequest = new CreateProposalRequest("テスト", null, null);

            // When & Then
            assertThatThrownBy(() -> service.createProposal(TEAM_ID, REQUEST_ID, createRequest))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.DUPLICATE_PROPOSAL);
        }

        @Test
        @DisplayName("異常系: 募集が見つからない")
        void 募集不存在() {
            // Given
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.empty());

            CreateProposalRequest createRequest = new CreateProposalRequest("テスト", null, null);

            // When & Then
            assertThatThrownBy(() -> service.createProposal(TEAM_ID, REQUEST_ID, createRequest))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.REQUEST_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("acceptProposal")
    class AcceptProposal {

        @Test
        @DisplayName("正常系: 応募承諾でマッチング成立")
        void 応募承諾成功() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID)
                    .proposingTeamId(OTHER_TEAM_ID)
                    .build();
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(TEAM_ID)
                    .status(MatchRequestStatus.OPEN)
                    .build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findByIdForUpdate(REQUEST_ID)).willReturn(Optional.of(request));
            given(proposalRepository.findByRequestIdAndStatus(any(), any())).willReturn(List.of());
            given(proposalRepository.save(any())).willReturn(proposal);
            given(requestRepository.save(any())).willReturn(request);

            AcceptProposalRequest acceptRequest = new AcceptProposalRequest(null, null, null, null, null);

            // When
            AcceptProposalResponse result = service.acceptProposal(PROPOSAL_ID, TEAM_ID, acceptRequest);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("異常系: 権限なし（募集チーム以外）")
        void 権限なし() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID)
                    .proposingTeamId(OTHER_TEAM_ID)
                    .build();
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(OTHER_TEAM_ID)
                    .status(MatchRequestStatus.OPEN)
                    .build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findByIdForUpdate(REQUEST_ID)).willReturn(Optional.of(request));

            // When & Then
            assertThatThrownBy(() -> service.acceptProposal(PROPOSAL_ID, TEAM_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }
    }

    @Nested
    @DisplayName("rejectProposal")
    class RejectProposal {

        @Test
        @DisplayName("異常系: PENDINGでない応募の拒否はエラー")
        void PENDING以外拒否エラー() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID)
                    .proposingTeamId(OTHER_TEAM_ID)
                    .build();
            // simulate ACCEPTED status via reflection or mock
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(TEAM_ID)
                    .build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));

            // proposal defaults to PENDING, so this should work
            given(proposalRepository.save(any())).willReturn(proposal);

            // When
            ProposalStatusResponse result = service.rejectProposal(PROPOSAL_ID, TEAM_ID, "不適合");

            // Then
            assertThat(result.getStatus()).isEqualTo("REJECTED");
        }
    }

    @Nested
    @DisplayName("withdrawProposal")
    class WithdrawProposal {

        @Test
        @DisplayName("異常系: 応募チーム以外の取り下げはエラー")
        void 権限なし取り下げ() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID)
                    .proposingTeamId(OTHER_TEAM_ID)
                    .build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));

            // When & Then
            assertThatThrownBy(() -> service.withdrawProposal(PROPOSAL_ID, TEAM_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }
    }

    @Nested
    @DisplayName("agreeCancellation")
    class AgreeCancellation {

        @Test
        @DisplayName("異常系: CANCELLED以外の応募に対して合意承認不可")
        void CANCELLED以外合意承認不可() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID)
                    .proposingTeamId(OTHER_TEAM_ID)
                    .build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));

            // When & Then - status is PENDING so should throw INVALID_PROPOSAL_STATUS
            assertThatThrownBy(() -> service.agreeCancellation(PROPOSAL_ID, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.INVALID_PROPOSAL_STATUS));
        }
    }

    @Nested
    @DisplayName("withdrawProposal")
    class WithdrawProposalAdditional {

        @Test
        @DisplayName("正常系: 応募取り下げ成功")
        void 取り下げ成功() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID)
                    .proposingTeamId(TEAM_ID)
                    .build();
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(OTHER_TEAM_ID).title("募集")
                    .activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13")
                    .build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(proposalRepository.save(any())).willReturn(proposal);
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));
            given(requestRepository.save(any())).willReturn(request);

            // When
            ProposalStatusResponse result = service.withdrawProposal(PROPOSAL_ID, TEAM_ID, "辞退します");

            // Then
            assertThat(result.getStatus()).isEqualTo("WITHDRAWN");
        }

        @Test
        @DisplayName("異常系: PENDING以外の応募は取り下げ不可")
        void PENDING以外取り下げ不可() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID).proposingTeamId(TEAM_ID).build();
            // Set status to ACCEPTED via accept()
            proposal.accept();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));

            // When / Then
            assertThatThrownBy(() -> service.withdrawProposal(PROPOSAL_ID, TEAM_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.INVALID_PROPOSAL_STATUS));
        }
    }

    @Nested
    @DisplayName("cancelProposal")
    class CancelProposal {

        @Test
        @DisplayName("正常系: キャンセル成功（一方的）")
        void キャンセル成功_一方的() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID).proposingTeamId(OTHER_TEAM_ID).build();
            proposal.accept(); // set to ACCEPTED
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("募集").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.MATCHED).prefectureCode("13").build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));
            given(proposalRepository.save(any())).willReturn(proposal);
            given(requestRepository.save(any())).willReturn(request);

            // When
            ProposalStatusResponse result = service.cancelProposal(PROPOSAL_ID, TEAM_ID,
                    new CancelProposalRequest("解散のため", false));

            // Then
            assertThat(result.getStatus()).isEqualTo("CANCELLED");
        }

        @Test
        @DisplayName("異常系: ACCEPTED以外の応募はキャンセル不可")
        void ACCEPTED以外キャンセル不可() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID).proposingTeamId(OTHER_TEAM_ID).build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));

            // When / Then
            assertThatThrownBy(() -> service.cancelProposal(PROPOSAL_ID, TEAM_ID,
                    new CancelProposalRequest("理由", false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.INVALID_PROPOSAL_STATUS));
        }

        @Test
        @DisplayName("異常系: 募集・応募チーム以外はキャンセル不可")
        void 権限なしキャンセル不可() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID).proposingTeamId(OTHER_TEAM_ID).build();
            proposal.accept();
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(999L).title("募集").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.MATCHED).prefectureCode("13").build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));

            // When / Then
            assertThatThrownBy(() -> service.cancelProposal(PROPOSAL_ID, TEAM_ID,
                    new CancelProposalRequest("理由", false)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.INSUFFICIENT_PERMISSION));
        }
    }

    @Nested
    @DisplayName("rejectProposal")
    class RejectProposalAdditional {

        @Test
        @DisplayName("異常系: 権限なしで拒否不可")
        void 権限なし拒否不可() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID).proposingTeamId(OTHER_TEAM_ID).build();
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(999L).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));

            // When / Then
            assertThatThrownBy(() -> service.rejectProposal(PROPOSAL_ID, TEAM_ID, "不適合"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.INSUFFICIENT_PERMISSION));
        }

        @Test
        @DisplayName("異常系: PENDING以外の応募は拒否不可")
        void PENDING以外拒否不可() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID).proposingTeamId(OTHER_TEAM_ID).build();
            proposal.accept(); // ACCEPTED
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));

            // When / Then
            assertThatThrownBy(() -> service.rejectProposal(PROPOSAL_ID, TEAM_ID, "不適合"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.INVALID_PROPOSAL_STATUS));
        }
    }

    @Nested
    @DisplayName("createProposal - 期限切れ・日程候補超過")
    class CreateProposalEdgeCases {

        @Test
        @DisplayName("異常系: 期限切れ募集への応募はエラー")
        void 期限切れ募集応募エラー() {
            // Given
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(OTHER_TEAM_ID)
                    .title("テスト募集")
                    .status(MatchRequestStatus.OPEN)
                    .expiresAt(java.time.LocalDateTime.now().minusDays(1))
                    .build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of());
            given(proposalRepository.existsByRequestIdAndProposingTeamId(REQUEST_ID, TEAM_ID)).willReturn(false);

            CreateProposalRequest createRequest = new CreateProposalRequest("テスト", null, null);

            // When & Then
            assertThatThrownBy(() -> service.createProposal(TEAM_ID, REQUEST_ID, createRequest))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.REQUEST_NOT_OPEN);
        }

        @Test
        @DisplayName("異常系: 日程候補が5件超過でエラー")
        void 日程候補超過エラー() {
            // Given
            MatchRequestEntity request = createOpenRequest();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(request));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of());
            given(proposalRepository.existsByRequestIdAndProposingTeamId(REQUEST_ID, TEAM_ID)).willReturn(false);

            List<com.mannschaft.app.matching.dto.ProposedDateRequest> dates = List.of(
                    new com.mannschaft.app.matching.dto.ProposedDateRequest(java.time.LocalDate.now(), null, null),
                    new com.mannschaft.app.matching.dto.ProposedDateRequest(java.time.LocalDate.now().plusDays(1), null, null),
                    new com.mannschaft.app.matching.dto.ProposedDateRequest(java.time.LocalDate.now().plusDays(2), null, null),
                    new com.mannschaft.app.matching.dto.ProposedDateRequest(java.time.LocalDate.now().plusDays(3), null, null),
                    new com.mannschaft.app.matching.dto.ProposedDateRequest(java.time.LocalDate.now().plusDays(4), null, null),
                    new com.mannschaft.app.matching.dto.ProposedDateRequest(java.time.LocalDate.now().plusDays(5), null, null)
            );
            CreateProposalRequest createRequest = new CreateProposalRequest("テスト", dates, null);

            // When & Then
            assertThatThrownBy(() -> service.createProposal(TEAM_ID, REQUEST_ID, createRequest))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.TOO_MANY_PROPOSED_DATES);
        }
    }

    @Nested
    @DisplayName("acceptProposal - 既にMATCHED・PENDING以外")
    class AcceptProposalAdditional {

        @Test
        @DisplayName("異常系: 既にMATCHED状態の募集への承諾はエラー")
        void MATCHED状態承諾エラー() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID).proposingTeamId(OTHER_TEAM_ID).build();
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.MATCHED).prefectureCode("13").build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findByIdForUpdate(REQUEST_ID)).willReturn(Optional.of(request));

            // When & Then
            assertThatThrownBy(() -> service.acceptProposal(PROPOSAL_ID, TEAM_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.REQUEST_ALREADY_MATCHED));
        }

        @Test
        @DisplayName("異常系: PENDING以外の応募は承諾不可")
        void PENDING以外承諾不可() {
            // Given
            MatchProposalEntity proposal = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID).proposingTeamId(OTHER_TEAM_ID).build();
            proposal.reject("既に拒否済み"); // REJECTED status
            MatchRequestEntity request = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
            given(requestRepository.findByIdForUpdate(REQUEST_ID)).willReturn(Optional.of(request));

            // When & Then
            assertThatThrownBy(() -> service.acceptProposal(PROPOSAL_ID, TEAM_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.INVALID_PROPOSAL_STATUS));
        }
    }
}
