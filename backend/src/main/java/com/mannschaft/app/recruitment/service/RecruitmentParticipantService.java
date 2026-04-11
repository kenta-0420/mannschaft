package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.recruitment.CancellationPaymentStatus;
import com.mannschaft.app.recruitment.CancellationSource;
import com.mannschaft.app.recruitment.ParticipantHistoryReason;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.dto.ApplyToRecruitmentRequest;
import com.mannschaft.app.recruitment.dto.CancelMyApplicationRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
import com.mannschaft.app.recruitment.entity.RecruitmentCancellationRecordEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantHistoryEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentCancellationRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantHistoryRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 募集型予約: 参加申込・キャンセル中核サービス。
 *
 * 設計書参照:
 * - §5.2 参加申込 (個人/チーム、楽観的ロック、キャンセル待ち追加)
 * - §5.3 キャンセル時のフロー (PESSIMISTIC_WRITE + FULL→OPEN 復帰)
 * - §5.9 キャンセル料計算統合
 * - §9.2 申込 API
 * - §9.10 キャンセル API (acknowledged_fee 必須)
 *
 * Phase 1+5a の限定:
 * - §5.2 ステップ4 ペナルティチェック → Phase 5b
 * - §5.2 ステップ9 レート制限 → Phase 4
 * - §5.3 自動昇格 (promoteFromWaitlistIfPossible) → Phase 3
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecruitmentParticipantService {

    private final RecruitmentParticipantRepository participantRepository;
    private final RecruitmentListingRepository listingRepository;
    private final RecruitmentParticipantHistoryRepository historyRepository;
    private final RecruitmentCancellationRecordRepository cancellationRecordRepository;
    private final RecruitmentCancellationPolicyService policyService;
    private final RecruitmentListingService listingService;
    private final AccessControlService accessControlService;
    private final RecruitmentMapper mapper;

    // ===========================================
    // §5.2 参加申込
    // ===========================================

    @Transactional
    public RecruitmentParticipantResponse apply(Long listingId, Long userId, ApplyToRecruitmentRequest request) {
        // §Phase4 レート制限: 1分間に5件以上の申込は拒否
        long recentCount = participantRepository.countRecentApplicationsByUser(userId, LocalDateTime.now().minusMinutes(1));
        if (recentCount >= 5) {
            throw new BusinessException(RecruitmentErrorCode.APPLY_RATE_LIMIT_EXCEEDED);
        }

        RecruitmentListingEntity listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));

        // §5.2 step3 締切チェック
        if (LocalDateTime.now().isAfter(listing.getApplicationDeadline())) {
            throw new BusinessException(RecruitmentErrorCode.DEADLINE_EXCEEDED);
        }

        // ステータスチェック
        if (listing.getStatus() == RecruitmentListingStatus.DRAFT) {
            throw new BusinessException(RecruitmentErrorCode.DRAFT_NOT_APPLICABLE);
        }
        if (listing.getStatus() == RecruitmentListingStatus.CANCELLED
                || listing.getStatus() == RecruitmentListingStatus.AUTO_CANCELLED
                || listing.getStatus() == RecruitmentListingStatus.CLOSED
                || listing.getStatus() == RecruitmentListingStatus.COMPLETED) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }

        // §5.2 step6 participation_type 整合
        boolean isIndividualListing = listing.getParticipationType() == RecruitmentParticipationType.INDIVIDUAL;
        boolean isUserApplication = request.getParticipantType() == RecruitmentParticipantType.USER;
        if (isIndividualListing != isUserApplication) {
            throw new BusinessException(RecruitmentErrorCode.PARTICIPATION_TYPE_MISMATCH);
        }
        if (request.getParticipantType() == RecruitmentParticipantType.TEAM && request.getTeamId() == null) {
            throw new BusinessException(RecruitmentErrorCode.PARTICIPATION_TYPE_MISMATCH);
        }

        // §5.2 step5 (Phase 5a) 未払いキャンセル料チェック
        boolean hasUnpaid = cancellationRecordRepository.existsByUserIdAndPaymentStatusIn(
                userId, List.of(CancellationPaymentStatus.PENDING, CancellationPaymentStatus.FAILED));
        if (hasUnpaid) {
            throw new BusinessException(RecruitmentErrorCode.CANCELLATION_PAYMENT_FAILED);
        }

        // §5.2 step5(b) 重複申込チェック
        boolean alreadyApplied;
        if (isUserApplication) {
            alreadyApplied = participantRepository
                    .findByListingIdAndUserIdAndStatusNot(listingId, userId, RecruitmentParticipantStatus.CANCELLED)
                    .isPresent();
        } else {
            alreadyApplied = participantRepository
                    .findByListingIdAndTeamIdAndStatusNot(listingId, request.getTeamId(), RecruitmentParticipantStatus.CANCELLED)
                    .isPresent();
        }
        if (alreadyApplied) {
            throw new BusinessException(RecruitmentErrorCode.ALREADY_APPLIED);
        }

        // §5.2 step7 楽観的ロックで確定数加算
        int updated = listingRepository.incrementConfirmedAtomic(listingId);

        boolean isWaitlisted;
        Integer waitlistPosition = null;
        if (updated == 1) {
            isWaitlisted = false;
        } else {
            // 満員 → キャンセル待ちフロー (§5.2 step8)
            int waitlistUpdated = listingRepository.incrementWaitlistAtomic(listingId);
            if (waitlistUpdated == 0) {
                throw new BusinessException(RecruitmentErrorCode.WAITLIST_LIMIT_EXCEEDED);
            }
            isWaitlisted = true;
            // next_waitlist_position は incrementWaitlistAtomic で +1 されているので、再ロード後に -1 で取得
            RecruitmentListingEntity reloaded = listingRepository.findById(listingId).orElseThrow();
            waitlistPosition = reloaded.getNextWaitlistPosition() - 1;
        }

        RecruitmentParticipantEntity participant = RecruitmentParticipantEntity.builder()
                .listingId(listingId)
                .participantType(request.getParticipantType())
                .userId(isUserApplication ? userId : null)
                .teamId(isUserApplication ? null : request.getTeamId())
                .appliedBy(userId)
                .status(isWaitlisted ? RecruitmentParticipantStatus.WAITLISTED : RecruitmentParticipantStatus.CONFIRMED)
                .waitlistPosition(waitlistPosition)
                .note(request.getNote())
                .build();
        RecruitmentParticipantEntity saved = participantRepository.save(participant);

        // 履歴記録
        historyRepository.save(RecruitmentParticipantHistoryEntity.builder()
                .participantId(saved.getId())
                .listingId(listingId)
                .oldStatus(null)
                .newStatus(saved.getStatus())
                .changedBy(userId)
                .changeReason(ParticipantHistoryReason.USER_ACTION)
                .build());

        log.info("F03.11 申込: listingId={}, userId={}, status={}, waitlistPos={}",
                listingId, userId, saved.getStatus(), waitlistPosition);
        return mapper.toParticipantResponse(saved);
    }

    // ===========================================
    // §5.3 + §5.9 + §9.10 キャンセル
    // ===========================================

    @Transactional
    public RecruitmentParticipantResponse cancelMyApplication(
            Long listingId, Long userId, CancelMyApplicationRequest request) {

        // §9.10 acknowledged_fee 必須
        if (request == null || !Boolean.TRUE.equals(request.getAcknowledgedFee())) {
            throw new BusinessException(RecruitmentErrorCode.FEE_NOT_ACKNOWLEDGED);
        }

        // PESSIMISTIC_WRITE で listing をロック
        RecruitmentListingEntity listing = listingRepository.findByIdForUpdate(listingId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));

        RecruitmentParticipantEntity participant = participantRepository
                .findActiveByListingAndUser(listingId, userId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));

        if (participant.getStatus() == RecruitmentParticipantStatus.CANCELLED) {
            throw new BusinessException(RecruitmentErrorCode.ALREADY_CANCELLED);
        }

        boolean wasConfirmed = participant.getStatus() == RecruitmentParticipantStatus.CONFIRMED;
        boolean wasWaitlisted = participant.getStatus() == RecruitmentParticipantStatus.WAITLISTED;

        // §5.9 キャンセル料計算
        LocalDateTime cancelAt = LocalDateTime.now();
        RecruitmentCancellationPolicyService.CalculatedFee fee = policyService.calculateFee(listing, cancelAt);

        // §9.10 fee_amount_at_request との乖離チェック (409)
        if (request.getFeeAmountAtRequest() != null
                && request.getFeeAmountAtRequest().intValue() != fee.feeAmount()) {
            log.warn("F03.11 キャンセル料乖離: listingId={}, requested={}, calculated={}",
                    listingId, request.getFeeAmountAtRequest(), fee.feeAmount());
            throw new BusinessException(RecruitmentErrorCode.CANCELLATION_FEE_MISMATCH);
        }

        RecruitmentParticipantStatus oldStatus = participant.getStatus();
        participant.cancelByUser();
        participantRepository.save(participant);

        // 履歴記録
        historyRepository.save(RecruitmentParticipantHistoryEntity.builder()
                .participantId(participant.getId())
                .listingId(listingId)
                .oldStatus(oldStatus)
                .newStatus(RecruitmentParticipantStatus.CANCELLED)
                .changedBy(userId)
                .changeReason(ParticipantHistoryReason.USER_ACTION)
                .build());

        // §5.9 キャンセル記録 (Phase 5a)
        cancellationRecordRepository.save(RecruitmentCancellationRecordEntity.builder()
                .participantId(participant.getId())
                .listingId(listingId)
                .userId(userId)
                .teamId(participant.getTeamId())
                .cancelledAt(cancelAt)
                .cancelledBy(userId)
                .cancelSource(CancellationSource.USER)
                .hoursBeforeStart((int) Math.max(0, fee.hoursBefore()))
                .appliedTierId(fee.tierId())
                .feeAmount(fee.feeAmount())
                .paymentStatus(fee.feeAmount() > 0
                        ? CancellationPaymentStatus.PENDING
                        : CancellationPaymentStatus.NOT_REQUIRED)
                .build());

        // §5.3 confirmed_count 減算 + FULL→OPEN 自動復帰 (CONFIRMED キャンセル時のみ)
        if (wasConfirmed) {
            listingRepository.decrementConfirmedAtomic(listingId);
        } else if (wasWaitlisted) {
            // WAITLISTED の場合は waitlist_count を減算
            RecruitmentListingEntity reloaded = listingRepository.findByIdForUpdate(listingId).orElseThrow();
            reloaded.decrementWaitlist();
            listingRepository.save(reloaded);
        }

        // Phase 3 で実装: promoteFromWaitlistIfPossible()
        log.info("F03.11 本人キャンセル: listingId={}, userId={}, fee={}",
                listingId, userId, fee.feeAmount());
        return mapper.toParticipantResponse(participant);
    }

    // ===========================================
    // 参加者一覧・出席管理 (管理者)
    // ===========================================

    public Page<RecruitmentParticipantResponse> listParticipants(Long listingId, Long userId, Pageable pageable) {
        RecruitmentListingEntity listing = listingService.findOrThrow(listingId);
        accessControlService.checkAdminOrAbove(userId, listing.getScopeId(), listing.getScopeType().name());

        return participantRepository.findByListingIdOrderByAppliedAtAsc(listingId, pageable)
                .map(mapper::toParticipantResponse);
    }

    @Transactional
    public RecruitmentParticipantResponse markAttended(Long listingId, Long participantId, Long userId) {
        RecruitmentListingEntity listing = listingService.findOrThrow(listingId);
        accessControlService.checkAdminOrAbove(userId, listing.getScopeId(), listing.getScopeType().name());

        RecruitmentParticipantEntity participant = participantRepository.findByIdAndListingId(participantId, listingId)
                .orElseThrow(() -> new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));

        RecruitmentParticipantStatus oldStatus = participant.getStatus();
        try {
            participant.markAttended();
        } catch (IllegalStateException e) {
            throw new BusinessException(RecruitmentErrorCode.INVALID_STATE_TRANSITION);
        }
        participantRepository.save(participant);

        historyRepository.save(RecruitmentParticipantHistoryEntity.builder()
                .participantId(participant.getId())
                .listingId(listingId)
                .oldStatus(oldStatus)
                .newStatus(RecruitmentParticipantStatus.ATTENDED)
                .changedBy(userId)
                .changeReason(ParticipantHistoryReason.ADMIN_ACTION)
                .build());

        return mapper.toParticipantResponse(participant);
    }

    public List<RecruitmentParticipantResponse> listMyActiveParticipations(Long userId) {
        return mapper.toParticipantResponseList(participantRepository.findMyActiveParticipations(userId));
    }
}
