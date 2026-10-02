package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ResolutionMode;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.VoteType;
import com.mannschaft.app.proxyvote.VotingStatus;
import com.mannschaft.app.proxyvote.dto.CastVoteRequest;
import com.mannschaft.app.proxyvote.dto.CastVoteResponse;
import com.mannschaft.app.proxyvote.entity.ProxyDelegationEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyDelegationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 投票登録・取消（変更）を担うサービス。
 * <p>ProxyVoteSessionService 分割（Phase 5 リファクタ）で切り出した。
 * ロジック・エラーコード・トランザクション境界は元実装と完全同一。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyVoteCastService {

    private final ProxyVoteSessionRepository sessionRepository;
    private final ProxyVoteMotionRepository motionRepository;
    private final ProxyVoteRepository voteRepository;
    private final ProxyDelegationRepository delegationRepository;
    private final AccessControlService accessControlService;

    /**
     * 投票する。
     */
    @Transactional
    public CastVoteResponse castVote(Long sessionId, CastVoteRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(sessionId);
        // 認可: 議決権 = セッションスコープの会員であること（票の水増し防止・直接投票経路でも検証）
        accessControlService.checkMembership(currentUserId, session.resolveScopeId(), session.scopeTypeName());
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(sessionId);
        validateCastVoteRequest(session, motions, request);

        // 既に投票済みかチェック
        boolean alreadyVoted = voteRepository.existsBySessionIdAndUserId(sessionId, currentUserId);
        if (alreadyVoted) {
            throw new BusinessException(ProxyVoteErrorCode.ALREADY_VOTED);
        }

        // 委任状がある場合は自動キャンセル
        delegationRepository.findBySessionIdAndDelegatorId(sessionId, currentUserId)
                .ifPresent(delegation -> {
                    delegation.cancel();
                    delegationRepository.save(delegation);
                });

        // 本人の投票を記録
        LocalDateTime votedAt = LocalDateTime.now();
        int votedCount = 0;
        for (CastVoteRequest.VoteItem item : request.getVotes()) {
            VoteType voteType = VoteType.valueOf(item.getVoteType());
            ProxyVoteEntity vote = ProxyVoteEntity.builder()
                    .motionId(item.getMotionId())
                    .userId(currentUserId)
                    .voteType(voteType)
                    .isProxyVote(false)
                    .votedAt(votedAt)
                    .build();
            voteRepository.save(vote);

            // カウント更新
            ProxyVoteMotionEntity motion = motions.stream()
                    .filter(m -> m.getId().equals(item.getMotionId()))
                    .findFirst().orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));
            motion.incrementVoteCount(voteType);
            motionRepository.save(motion);
            votedCount++;
        }

        // 委任を受けている場合: 代理投票を一括生成
        List<ProxyDelegationEntity> acceptedDelegations =
                delegationRepository.findBySessionIdAndDelegateIdAndStatus(sessionId, currentUserId, DelegationStatus.ACCEPTED);
        for (ProxyDelegationEntity delegation : acceptedDelegations) {
            for (CastVoteRequest.VoteItem item : request.getVotes()) {
                VoteType voteType = VoteType.valueOf(item.getVoteType());
                if (!voteRepository.existsByMotionIdAndUserId(item.getMotionId(), delegation.getDelegatorId())) {
                    ProxyVoteEntity proxyVote = ProxyVoteEntity.builder()
                            .motionId(item.getMotionId())
                            .userId(delegation.getDelegatorId())
                            .voteType(voteType)
                            .isProxyVote(true)
                            .delegationId(delegation.getId())
                            .votedAt(votedAt)
                            .build();
                    voteRepository.save(proxyVote);

                    ProxyVoteMotionEntity motion = motions.stream()
                            .filter(m -> m.getId().equals(item.getMotionId()))
                            .findFirst().orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));
                    motion.incrementVoteCount(voteType);
                    motionRepository.save(motion);
                }
            }
        }

        return CastVoteResponse.builder()
                .sessionId(sessionId)
                .votedMotions(votedCount)
                .votedAt(votedAt)
                .build();
    }

    /**
     * 投票を変更する。
     */
    @Transactional
    public CastVoteResponse updateVote(Long sessionId, CastVoteRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = findSessionOrThrow(sessionId);
        // 認可: 議決権 = セッションスコープの会員であること（票の水増し防止・直接投票経路でも検証）
        accessControlService.checkMembership(currentUserId, session.resolveScopeId(), session.scopeTypeName());
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        boolean alreadyVoted = voteRepository.existsBySessionIdAndUserId(sessionId, currentUserId);
        if (!alreadyVoted) {
            throw new BusinessException(ProxyVoteErrorCode.VOTE_NOT_FOUND);
        }

        List<ProxyVoteMotionEntity> motions = motionRepository.findBySessionIdOrderByMotionNumberAsc(sessionId);
        validateCastVoteRequest(session, motions, request);

        // 既存の投票を削除してカウント補正
        List<ProxyVoteEntity> existingVotes = voteRepository.findBySessionIdAndUserId(sessionId, currentUserId);
        for (ProxyVoteEntity existing : existingVotes) {
            ProxyVoteMotionEntity motion = motions.stream()
                    .filter(m -> m.getId().equals(existing.getMotionId()))
                    .findFirst().orElse(null);
            if (motion != null) {
                motion.decrementVoteCount(existing.getVoteType());
            }
            voteRepository.delete(existing);
        }

        // 代理投票も削除
        List<ProxyDelegationEntity> acceptedDelegations =
                delegationRepository.findBySessionIdAndDelegateIdAndStatus(sessionId, currentUserId, DelegationStatus.ACCEPTED);
        for (ProxyDelegationEntity delegation : acceptedDelegations) {
            List<ProxyVoteEntity> proxyVotes = voteRepository.findByDelegationId(delegation.getId());
            for (ProxyVoteEntity pv : proxyVotes) {
                ProxyVoteMotionEntity motion = motions.stream()
                        .filter(m -> m.getId().equals(pv.getMotionId()))
                        .findFirst().orElse(null);
                if (motion != null) {
                    motion.decrementVoteCount(pv.getVoteType());
                }
                voteRepository.delete(pv);
            }
        }
        motionRepository.saveAll(motions);

        // 新しい投票を記録（castVote と同じロジック）
        LocalDateTime votedAt = LocalDateTime.now();
        int votedCount = 0;
        for (CastVoteRequest.VoteItem item : request.getVotes()) {
            VoteType voteType = VoteType.valueOf(item.getVoteType());
            ProxyVoteEntity vote = ProxyVoteEntity.builder()
                    .motionId(item.getMotionId())
                    .userId(currentUserId)
                    .voteType(voteType)
                    .isProxyVote(false)
                    .votedAt(votedAt)
                    .build();
            voteRepository.save(vote);

            ProxyVoteMotionEntity motion = motions.stream()
                    .filter(m -> m.getId().equals(item.getMotionId()))
                    .findFirst().orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));
            motion.incrementVoteCount(voteType);
            motionRepository.save(motion);
            votedCount++;
        }

        // 代理投票を再生成
        for (ProxyDelegationEntity delegation : acceptedDelegations) {
            for (CastVoteRequest.VoteItem item : request.getVotes()) {
                VoteType voteType = VoteType.valueOf(item.getVoteType());
                ProxyVoteEntity proxyVote = ProxyVoteEntity.builder()
                        .motionId(item.getMotionId())
                        .userId(delegation.getDelegatorId())
                        .voteType(voteType)
                        .isProxyVote(true)
                        .delegationId(delegation.getId())
                        .votedAt(votedAt)
                        .build();
                voteRepository.save(proxyVote);

                ProxyVoteMotionEntity motion = motions.stream()
                        .filter(m -> m.getId().equals(item.getMotionId()))
                        .findFirst().orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));
                motion.incrementVoteCount(voteType);
                motionRepository.save(motion);
            }
        }

        return CastVoteResponse.builder()
                .sessionId(sessionId)
                .votedMotions(votedCount)
                .votedAt(votedAt)
                .build();
    }

    private ProxyVoteSessionEntity findSessionOrThrow(Long id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.SESSION_NOT_FOUND));
    }

    private void validateCastVoteRequest(ProxyVoteSessionEntity session,
                                          List<ProxyVoteMotionEntity> motions,
                                          CastVoteRequest request) {
        if (session.getResolutionMode() == ResolutionMode.WRITTEN) {
            if (request.getVotes().size() != motions.size()) {
                throw new BusinessException(ProxyVoteErrorCode.INCOMPLETE_VOTES);
            }
        }

        for (CastVoteRequest.VoteItem item : request.getVotes()) {
            ProxyVoteMotionEntity motion = motions.stream()
                    .filter(m -> m.getId().equals(item.getMotionId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.MOTION_NOT_FOUND));

            if (session.getResolutionMode() == ResolutionMode.MEETING) {
                if (motion.getVotingStatus() != VotingStatus.VOTING) {
                    throw new BusinessException(ProxyVoteErrorCode.NON_VOTING_MOTION_INCLUDED);
                }
            }
        }
    }
}
