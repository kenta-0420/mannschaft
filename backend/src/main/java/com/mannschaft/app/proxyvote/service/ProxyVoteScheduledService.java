package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.proxyvote.ResolutionMode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.VotingStatus;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 自動遷移バッチサービス。5分間隔でステータス遷移・投票タイマー管理を実行する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProxyVoteScheduledService {

    private final ProxyVoteSessionRepository sessionRepository;
    private final ProxyVoteMotionRepository motionRepository;
    private final ProxyVoteMotionService motionService;

    /**
     * DRAFT → OPEN 自動遷移（両モード共通）。
     * OPEN → CLOSED 自動遷移（WRITTEN モードのみ）。
     * MEETING モード投票タイマー自動終了。
     */
    @Scheduled(fixedRate = 300_000) // 5分間隔
    @Transactional
    public void processAutoTransitions() {
        LocalDateTime now = LocalDateTime.now();

        // 1. DRAFT → OPEN 自動遷移
        List<ProxyVoteSessionEntity> draftSessions = sessionRepository
                .findByStatusAndVotingStartAtLessThanEqualAndVotingStartAtIsNotNull(SessionStatus.DRAFT, now);
        for (ProxyVoteSessionEntity session : draftSessions) {
            try {
                long motionCount = motionRepository.countBySessionId(session.getId());
                if (motionCount > 0) {
                    session.changeStatus(SessionStatus.OPEN);
                    if (session.getResolutionMode() == ResolutionMode.WRITTEN) {
                        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(session.getId());
                        motions.forEach(m -> m.changeVotingStatus(VotingStatus.VOTING));
                        motionRepository.saveAll(motions);
                    }
                    sessionRepository.save(session);
                    log.info("自動遷移 DRAFT → OPEN: sessionId={}", session.getId());
                }
            } catch (Exception e) {
                log.error("自動遷移エラー (DRAFT → OPEN): sessionId={}", session.getId(), e);
            }
        }

        // 2. OPEN → CLOSED 自動遷移（WRITTEN モードのみ）
        List<ProxyVoteSessionEntity> openWrittenSessions = sessionRepository
                .findByStatusAndResolutionModeAndVotingEndAtLessThanEqualAndVotingEndAtIsNotNull(
                        SessionStatus.OPEN, ResolutionMode.WRITTEN, now);
        for (ProxyVoteSessionEntity session : openWrittenSessions) {
            try {
                List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(session.getId());
                motions.forEach(m -> m.changeVotingStatus(VotingStatus.VOTED));
                motionRepository.saveAll(motions);
                session.changeStatus(SessionStatus.CLOSED);
                sessionRepository.save(session);
                log.info("自動遷移 OPEN → CLOSED (WRITTEN): sessionId={}", session.getId());
            } catch (Exception e) {
                log.error("自動遷移エラー (OPEN → CLOSED): sessionId={}", session.getId(), e);
            }
        }

        // 3. MEETING モード投票タイマー自動終了
        List<ProxyVoteMotionEntity> expiredMotions = motionRepository
                .findByVotingStatusAndVoteDeadlineAtLessThanEqualAndVoteDeadlineAtIsNotNull(VotingStatus.VOTING, now);
        for (ProxyVoteMotionEntity motion : expiredMotions) {
            try {
                motionService.endVote(motion.getId());
                log.info("投票タイマー自動終了: motionId={}", motion.getId());
            } catch (Exception e) {
                log.error("投票タイマー自動終了エラー: motionId={}", motion.getId(), e);
            }
        }
    }
}
