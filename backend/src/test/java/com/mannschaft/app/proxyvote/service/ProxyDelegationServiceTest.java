package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.dto.DelegateRequest;
import com.mannschaft.app.proxyvote.dto.DelegationResponse;
import com.mannschaft.app.proxyvote.dto.ReviewDelegationRequest;
import com.mannschaft.app.proxyvote.entity.ProxyDelegationEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyDelegationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link ProxyDelegationService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyDelegationService 単体テスト")
class ProxyDelegationServiceTest {

    @Mock private ProxyVoteSessionService sessionService;
    @Mock private ProxyDelegationRepository delegationRepository;
    @Mock private ProxyVoteRepository voteRepository;
    @Mock private ProxyVoteMotionRepository motionRepository;
    @Mock private ProxyVoteMapper mapper;

    @InjectMocks
    private ProxyDelegationService service;

    private static final Long SESSION_ID = 1L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("delegate")
    class Delegate {

        @Test
        @DisplayName("異常系: セッションがOPEN以外でエラー")
        void セッションOPEN以外() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.DRAFT).build();
            given(sessionService.findSessionOrThrow(SESSION_ID)).willReturn(session);

            DelegateRequest request = new DelegateRequest(2L, false, null, null);

            assertThatThrownBy(() -> service.delegate(SESSION_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        @Test
        @DisplayName("異常系: 無記名投票セッションでは委任不可")
        void 無記名投票委任不可() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).isAnonymous(true).build();
            given(sessionService.findSessionOrThrow(SESSION_ID)).willReturn(session);

            DelegateRequest request = new DelegateRequest(2L, false, null, null);

            assertThatThrownBy(() -> service.delegate(SESSION_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.DELEGATION_NOT_ALLOWED_ANONYMOUS);
        }

        @Test
        @DisplayName("異常系: 既に投票済みの場合は委任不可")
        void 投票済み委任不可() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).isAnonymous(false).build();
            given(sessionService.findSessionOrThrow(SESSION_ID)).willReturn(session);
            given(voteRepository.existsBySessionIdAndUserId(SESSION_ID, USER_ID)).willReturn(true);

            DelegateRequest request = new DelegateRequest(2L, false, null, null);

            assertThatThrownBy(() -> service.delegate(SESSION_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.ALREADY_VOTED_CANNOT_DELEGATE);
        }

        @Test
        @DisplayName("異常系: 自分自身への委任はエラー")
        void 自己委任エラー() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).isAnonymous(false).build();
            given(sessionService.findSessionOrThrow(SESSION_ID)).willReturn(session);
            given(voteRepository.existsBySessionIdAndUserId(SESSION_ID, USER_ID)).willReturn(false);
            given(delegationRepository.existsBySessionIdAndDelegatorId(SESSION_ID, USER_ID)).willReturn(false);

            DelegateRequest request = new DelegateRequest(USER_ID, false, null, null);

            assertThatThrownBy(() -> service.delegate(SESSION_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.SELF_DELEGATION);
        }
    }

    @Nested
    @DisplayName("reviewDelegation")
    class ReviewDelegation {

        @Test
        @DisplayName("異常系: SUBMITTED以外の委任状はレビュー不可")
        void SUBMITTED以外レビュー不可() {
            ProxyDelegationEntity delegation = ProxyDelegationEntity.builder()
                    .sessionId(SESSION_ID).delegatorId(USER_ID).status(DelegationStatus.ACCEPTED).build();
            given(delegationRepository.findById(1L)).willReturn(Optional.of(delegation));

            ReviewDelegationRequest request = new ReviewDelegationRequest("ACCEPTED");

            assertThatThrownBy(() -> service.reviewDelegation(1L, request, 200L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.DELEGATION_NOT_SUBMITTED);
        }
    }
}
