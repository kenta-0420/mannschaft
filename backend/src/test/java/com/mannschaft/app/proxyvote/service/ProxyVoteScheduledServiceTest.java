package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.proxyvote.ResolutionMode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.VotingStatus;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ProxyVoteScheduledService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyVoteScheduledService 単体テスト")
class ProxyVoteScheduledServiceTest {

    @Mock private ProxyVoteSessionRepository sessionRepository;
    @Mock private ProxyVoteMotionRepository motionRepository;
    @Mock private ProxyVoteMotionService motionService;

    @InjectMocks
    private ProxyVoteScheduledService service;

    @Nested
    @DisplayName("processAutoTransitions")
    class ProcessAutoTransitions {

        @Test
        @DisplayName("正常系: DRAFT→OPENの自動遷移が実行される")
        void DRAFT_OPEN自動遷移() {
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .resolutionMode(ResolutionMode.MEETING).build();
            setStatus(session, SessionStatus.DRAFT);
            given(sessionRepository.findByStatusAndVotingStartAtLessThanEqualAndVotingStartAtIsNotNull(
                    eq(SessionStatus.DRAFT), any())).willReturn(List.of(session));
            given(motionRepository.countBySessionId(any())).willReturn(1L);
            given(sessionRepository.findByStatusAndResolutionModeAndVotingEndAtLessThanEqualAndVotingEndAtIsNotNull(
                    any(), any(), any())).willReturn(List.of());
            given(motionRepository.findByVotingStatusAndVoteDeadlineAtLessThanEqualAndVoteDeadlineAtIsNotNull(
                    any(), any())).willReturn(List.of());

            service.processAutoTransitions();

            verify(sessionRepository).save(any());
        }
    }

    private void setStatus(ProxyVoteSessionEntity entity, SessionStatus status) {
        try {
            var field = ProxyVoteSessionEntity.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(entity, status);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
