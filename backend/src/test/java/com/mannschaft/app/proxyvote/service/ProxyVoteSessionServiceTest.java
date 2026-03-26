package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.ProxyVoteScopeType;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.dto.CreateSessionRequest;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyDelegationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteAttachmentRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteSessionRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link ProxyVoteSessionService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyVoteSessionService 単体テスト")
class ProxyVoteSessionServiceTest {

    @Mock private ProxyVoteSessionRepository sessionRepository;
    @Mock private ProxyVoteMotionRepository motionRepository;
    @Mock private ProxyVoteAttachmentRepository attachmentRepository;
    @Mock private ProxyVoteRepository voteRepository;
    @Mock private ProxyDelegationRepository delegationRepository;
    @Mock private ProxyVoteMapper mapper;
    @Mock private UserRoleRepository userRoleRepository;

    @InjectMocks
    private ProxyVoteSessionService service;

    private static final Long SESSION_ID = 1L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("deleteSession")
    class DeleteSession {

        @Test
        @DisplayName("異常系: DRAFT以外の削除はエラー")
        void DRAFT以外削除不可() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).build();
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            assertThatThrownBy(() -> service.deleteSession(SESSION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.STATUS_MUST_BE_DRAFT);
        }
    }

    @Nested
    @DisplayName("openSession")
    class OpenSession {

        @Test
        @DisplayName("異常系: DRAFT以外のOPEN遷移はエラー")
        void DRAFT以外OPEN不可() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).build();
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            assertThatThrownBy(() -> service.openSession(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.STATUS_MUST_BE_DRAFT);
        }

        @Test
        @DisplayName("異常系: 議案が0件の場合OPEN不可")
        void 議案ゼロ件() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.DRAFT).scopeType(ProxyVoteScopeType.TEAM)
                    .teamId(1L).build();
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));
            given(motionRepository.countBySessionId(SESSION_ID)).willReturn(0L);

            assertThatThrownBy(() -> service.openSession(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.NO_MOTIONS);
        }
    }

    @Nested
    @DisplayName("closeSession")
    class CloseSession {

        @Test
        @DisplayName("異常系: OPEN以外のCLOSE遷移はエラー")
        void OPEN以外CLOSE不可() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.DRAFT).build();
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            assertThatThrownBy(() -> service.closeSession(SESSION_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }
    }

    @Nested
    @DisplayName("finalizeSession")
    class FinalizeSession {

        @Test
        @DisplayName("異常系: CLOSED以外のFINALIZE遷移はエラー")
        void CLOSED以外FINALIZE不可() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).build();
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            assertThatThrownBy(() -> service.finalizeSession(SESSION_ID, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.STATUS_MUST_BE_CLOSED);
        }
    }

    @Nested
    @DisplayName("castVote")
    class CastVote {

        @Test
        @DisplayName("異常系: OPEN以外で投票はエラー")
        void OPEN以外投票不可() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.CLOSED).build();
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            assertThatThrownBy(() -> service.castVote(SESSION_ID, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }
    }

    @Nested
    @DisplayName("updateSession")
    class UpdateSession {

        @Test
        @DisplayName("異常系: CLOSED/FINALIZEDのセッションは更新不可")
        void CLOSED更新不可() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.CLOSED).build();
            given(sessionRepository.findById(SESSION_ID)).willReturn(Optional.of(session));

            assertThatThrownBy(() -> service.updateSession(SESSION_ID, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.SESSION_NOT_UPDATABLE);
        }
    }

    @Nested
    @DisplayName("createSession")
    class CreateSession {

        @Test
        @DisplayName("異常系: MEETINGモードでmeeting_date未指定はエラー")
        void MEETING_DATE必須() {
            CreateSessionRequest request = new CreateSessionRequest(
                    "TEAM", 1L, null, "MEETING", "テスト総会", null,
                    null, null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> service.createSession(request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.MEETING_DATE_REQUIRED);
        }
    }
}
