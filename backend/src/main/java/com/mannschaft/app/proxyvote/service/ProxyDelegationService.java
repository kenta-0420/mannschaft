package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.VoteType;
import com.mannschaft.app.proxyvote.dto.AttendanceResponse;
import com.mannschaft.app.proxyvote.dto.DelegateRequest;
import com.mannschaft.app.proxyvote.dto.DelegationResponse;
import com.mannschaft.app.proxyvote.dto.ReviewDelegationRequest;
import com.mannschaft.app.proxyvote.entity.ProxyDelegationEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyDelegationRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionRepository;
import com.mannschaft.app.proxyvote.repository.ProxyVoteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 委任状サービス。委任状の提出・承認/却下・取り下げを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyDelegationService {

    private final ProxyVoteSessionService sessionService;
    private final ProxyDelegationRepository delegationRepository;
    private final ProxyVoteRepository voteRepository;
    private final ProxyVoteMotionRepository motionRepository;
    private final ProxyVoteMapper mapper;

    /**
     * 委任状を提出する。
     */
    @Transactional
    public DelegationResponse delegate(Long sessionId, DelegateRequest request, Long currentUserId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);

        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }
        if (session.getIsAnonymous()) {
            throw new BusinessException(ProxyVoteErrorCode.DELEGATION_NOT_ALLOWED_ANONYMOUS);
        }
        if (voteRepository.existsBySessionIdAndUserId(sessionId, currentUserId)) {
            throw new BusinessException(ProxyVoteErrorCode.ALREADY_VOTED_CANNOT_DELEGATE);
        }
        if (delegationRepository.existsBySessionIdAndDelegatorId(sessionId, currentUserId)) {
            throw new BusinessException(ProxyVoteErrorCode.ALREADY_DELEGATED);
        }

        boolean isBlank = request.getIsBlank() != null && request.getIsBlank();
        if (!isBlank) {
            if (request.getDelegateId() == null) {
                throw new BusinessException(ProxyVoteErrorCode.DELEGATE_OUT_OF_SCOPE);
            }
            if (request.getDelegateId().equals(currentUserId)) {
                throw new BusinessException(ProxyVoteErrorCode.SELF_DELEGATION);
            }
            // TODO: 代理人がスコープ内かチェック
        }

        DelegationStatus initialStatus = session.getIsAutoAcceptDelegation()
                ? DelegationStatus.ACCEPTED : DelegationStatus.SUBMITTED;

        ProxyDelegationEntity delegation = ProxyDelegationEntity.builder()
                .sessionId(sessionId)
                .delegatorId(currentUserId)
                .delegateId(isBlank ? null : request.getDelegateId())
                .isBlank(isBlank)
                .electronicSealId(request.getElectronicSealId())
                .reason(request.getReason())
                .status(initialStatus)
                .build();
        delegation = delegationRepository.save(delegation);

        log.info("委任状提出: delegationId={}, sessionId={}, delegatorId={}", delegation.getId(), sessionId, currentUserId);
        return mapper.toDelegationResponse(delegation);
    }

    /**
     * 委任状を取り下げる。
     */
    @Transactional
    public void cancelDelegation(Long sessionId, Long currentUserId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);
        if (session.getStatus() != SessionStatus.OPEN) {
            throw new BusinessException(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        ProxyDelegationEntity delegation = delegationRepository.findBySessionIdAndDelegatorId(sessionId, currentUserId)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.DELEGATION_NOT_FOUND));

        if (delegation.getStatus() == DelegationStatus.REJECTED || delegation.getStatus() == DelegationStatus.CANCELLED) {
            throw new BusinessException(ProxyVoteErrorCode.DELEGATION_ALREADY_RESOLVED);
        }

        if (delegation.getStatus() == DelegationStatus.SUBMITTED) {
            delegationRepository.delete(delegation);
        } else if (delegation.getStatus() == DelegationStatus.ACCEPTED) {
            // 代理投票済みの場合はカウント補正
            List<ProxyVoteEntity> proxyVotes = voteRepository.findByDelegationId(delegation.getId());
            for (ProxyVoteEntity pv : proxyVotes) {
                ProxyVoteMotionEntity motion = motionRepository.findById(pv.getMotionId()).orElse(null);
                if (motion != null) {
                    motion.decrementVoteCount(pv.getVoteType());
                    motionRepository.save(motion);
                }
                voteRepository.delete(pv);
            }
            delegation.cancel();
            delegationRepository.save(delegation);
        }

        log.info("委任状取り下げ: sessionId={}, delegatorId={}", sessionId, currentUserId);
    }

    /**
     * 委任状を承認/却下する。
     */
    @Transactional
    public DelegationResponse reviewDelegation(Long delegationId, ReviewDelegationRequest request, Long reviewerId) {
        ProxyDelegationEntity delegation = delegationRepository.findById(delegationId)
                .orElseThrow(() -> new BusinessException(ProxyVoteErrorCode.DELEGATION_NOT_FOUND));

        if (delegation.getStatus() != DelegationStatus.SUBMITTED) {
            throw new BusinessException(ProxyVoteErrorCode.DELEGATION_NOT_SUBMITTED);
        }

        DelegationStatus newStatus = DelegationStatus.valueOf(request.getStatus());
        if (newStatus == DelegationStatus.ACCEPTED) {
            delegation.accept(reviewerId);
        } else if (newStatus == DelegationStatus.REJECTED) {
            delegation.reject(reviewerId);
        } else {
            throw new BusinessException(ProxyVoteErrorCode.DELEGATION_NOT_SUBMITTED);
        }

        delegation = delegationRepository.save(delegation);
        log.info("委任状レビュー: delegationId={}, status={}", delegationId, newStatus);
        return mapper.toDelegationResponse(delegation);
    }

    /**
     * 出席・委任状況一覧を取得する。
     */
    public AttendanceResponse getAttendance(Long sessionId) {
        ProxyVoteSessionEntity session = sessionService.findSessionOrThrow(sessionId);

        long votedCount = voteRepository.countDistinctVotersBySessionId(sessionId);
        long delegatedCount = delegationRepository.countBySessionIdAndStatus(sessionId, DelegationStatus.ACCEPTED);
        long notResponded = session.getEligibleCount() - votedCount - delegatedCount;
        if (notResponded < 0) notResponded = 0;

        // TODO: メンバー詳細一覧の構築（ユーザー一覧との結合が必要）

        return AttendanceResponse.builder()
                .sessionId(sessionId)
                .eligibleCount(session.getEligibleCount())
                .summary(AttendanceResponse.SummaryResponse.builder()
                        .votedCount(votedCount)
                        .delegatedCount(delegatedCount)
                        .notRespondedCount(notResponded)
                        .build())
                .members(List.of()) // TODO: メンバー一覧
                .build();
    }
}
