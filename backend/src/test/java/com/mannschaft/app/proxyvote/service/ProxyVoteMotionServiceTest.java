package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.MotionResult;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.ResolutionMode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.VotingStatus;
import com.mannschaft.app.proxyvote.dto.MotionResponse;
import com.mannschaft.app.proxyvote.dto.StartVoteRequest;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ProxyVoteMotionService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyVoteMotionService 単体テスト")
class ProxyVoteMotionServiceTest {

    @Mock private ProxyVoteSessionService sessionService;
    @Mock private ProxyVoteMotionRepository motionRepository;
    @Mock private ProxyVoteRepository voteRepository;
    @Mock private ProxyDelegationRepository delegationRepository;
    @Mock private ProxyVoteMapper mapper;

    @InjectMocks
    private ProxyVoteMotionService service;

    private static final Long MOTION_ID = 1L;
    private static final Long SESSION_ID = 10L;

    @Nested
    @DisplayName("startVote")
    class StartVote {

        @Test
        @DisplayName("異常系: WRITTEN モードでは投票開始不可")
        void WRITTENモード不可() {
            ProxyVoteMotionEntity motion = ProxyVoteMotionEntity.builder()
                    .sessionId(SESSION_ID).build();
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .resolutionMode(ResolutionMode.WRITTEN).status(SessionStatus.OPEN).build();

            given(sessionService.findMotionOrThrow(MOTION_ID)).willReturn(motion);
            given(sessionService.findSessionOrThrow(SESSION_ID)).willReturn(session);

            assertThatThrownBy(() -> service.startVote(MOTION_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.MEETING_MODE_ONLY);
        }

        @Test
        @DisplayName("異常系: セッションがOPEN以外でエラー")
        void セッションOPEN以外() {
            ProxyVoteMotionEntity motion = ProxyVoteMotionEntity.builder()
                    .sessionId(SESSION_ID).build();
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .resolutionMode(ResolutionMode.MEETING).status(SessionStatus.DRAFT).build();

            given(sessionService.findMotionOrThrow(MOTION_ID)).willReturn(motion);
            given(sessionService.findSessionOrThrow(SESSION_ID)).willReturn(session);

            assertThatThrownBy(() -> service.startVote(MOTION_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }
    }

    @Nested
    @DisplayName("endVote")
    class EndVote {

        @Test
        @DisplayName("異常系: 議案がVOTING状態でないとエラー")
        void 議案VOTING以外() {
            ProxyVoteMotionEntity motion = ProxyVoteMotionEntity.builder()
                    .sessionId(SESSION_ID).votingStatus(VotingStatus.PENDING).build();
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .resolutionMode(ResolutionMode.MEETING).status(SessionStatus.OPEN).build();

            given(sessionService.findMotionOrThrow(MOTION_ID)).willReturn(motion);
            given(sessionService.findSessionOrThrow(SESSION_ID)).willReturn(session);

            assertThatThrownBy(() -> service.endVote(MOTION_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.MOTION_NOT_VOTING);
        }
    }

    @Nested
    @DisplayName("startAllVotes")
    class StartAllVotes {

        @Test
        @DisplayName("異常系: PENDING議案がゼロ件でエラー")
        void PENDING議案ゼロ() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .resolutionMode(ResolutionMode.MEETING).status(SessionStatus.OPEN).build();

            given(sessionService.findSessionOrThrow(SESSION_ID)).willReturn(session);
            given(motionRepository.countBySessionIdAndVotingStatus(SESSION_ID, VotingStatus.PENDING)).willReturn(0L);

            assertThatThrownBy(() -> service.startAllVotes(SESSION_ID, 100L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.NO_PENDING_MOTIONS);
        }
    }
}
