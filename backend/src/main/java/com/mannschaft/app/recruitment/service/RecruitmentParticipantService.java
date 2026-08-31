package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
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
import com.mannschaft.app.recruitment.event.RecruitmentCancellationFeeChargeRequestedEvent;
import com.mannschaft.app.recruitment.event.RecruitmentParticipantConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
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
    /** F22.1 市: 充足（FULL）到達時の最終認証連携。 */
    private final MarketFinalizeService marketFinalizeService;
    /**
     * F22.1 市: 応募確定前の可視性ガード（02_api_design §5 / §7・04_security §1.1）。
     * FRIEND_TEAMS_ONLY 札は宛先解決集合のみ応募可（非対象は 404 存在秘匿）。
     */
    private final ContentVisibilityChecker visibilityChecker;
    /**
     * F22.1 市の謝礼決済: 応募確定（CONFIRMED）→ 謝礼の与信（authorize）連携イベントの発火元（02_api_design §5.1）。
     * payment.escrow リスナが購読する（クロスドメイン FK を作らず ID のみ受け渡す疎結合・README §7）。
     */
    private final ApplicationEventPublisher eventPublisher;

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
        // 自己応募は個人札が将来公開された後も不変の禁止契約であり、汚染行の存在秘匿より優先する。
        if (listing.getScopeType() == com.mannschaft.app.recruitment.RecruitmentScopeType.PERSONAL
                && listing.getScopeId().equals(userId)) {
            throw new BusinessException(com.mannschaft.app.market.MarketErrorCode.SELF_APPLICATION_FORBIDDEN);
        }
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

        // F22.1 市（02_api_design §5 / §7・04_security §1.1）: 確定の「前」に可視性ガードを通す。
        // PUBLIC/SCOPE_ONLY/SUPPORTERS_ONLY/CUSTOM_TEMPLATE は F00 標準判定、FRIEND_TEAMS_ONLY は
        // RecruitmentListingVisibilityResolver#evaluateCustom が宛先フレンドチーム集合で判定する。
        // 非対象ユーザーは NOT_FOUND→404（存在秘匿）/ deny→403 で弾かれ、IDOR（listingId 既知の
        // 任意ユーザーが応募できる）を根治する。
        visibilityChecker.assertCanView(ReferenceType.RECRUITMENT_LISTING, listingId, userId);

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
        // UNCOLLECTIBLE（F03.11.1 §5.3）はリトライを打ち切った状態であり、未払いであることに変わりはない。
        // これを対象から外すと「徴収の試行が尽きるまで待てば申込制限が消える」経路が残ってしまう。
        // 判定はユーザー単位であり、1 件でも該当があれば拒否する（免除の効き方は F03.11.1 §10.0）。
        boolean hasUnpaid = cancellationRecordRepository.existsByUserIdAndPaymentStatusIn(
                userId, List.of(CancellationPaymentStatus.PENDING, CancellationPaymentStatus.FAILED,
                        CancellationPaymentStatus.UNCOLLECTIBLE));
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
        boolean reachedFull = false;
        if (updated == 1) {
            isWaitlisted = false;
            // F22.1 市: この申込で OPEN→FULL に遷移したかを再ロードで検知する（§6.1）。
            // incrementConfirmedAtomic は status=CASE で FULL に遷移させる原子 UPDATE。
            RecruitmentListingEntity afterIncrement = listingRepository.findById(listingId).orElse(null);
            reachedFull = afterIncrement != null
                    && afterIncrement.getStatus() == RecruitmentListingStatus.FULL;
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

        // F22.1 市: 謝礼有効な札に確定（非キャンセル待ち）したら謝礼の与信（authorize）を開始する（§5.1）。
        if (!isWaitlisted) {
            publishPaymentAuthorizationIfNeeded(listing, saved, userId);
        }

        // F22.1 市: この申込で FULL に到達したら最終認証の確認通知を送る（§6.1）。
        if (reachedFull) {
            RecruitmentListingEntity fullListing = listingRepository.findById(listingId).orElse(null);
            if (fullListing != null) {
                marketFinalizeService.sendFinalizeConfirmation(fullListing);
            }
        }

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
        RecruitmentCancellationRecordEntity cancellationRecord =
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

        // §5.3 CONFIRMED だったならキャンセル待ちを昇格する
        if (wasConfirmed) {
            RecruitmentListingEntity reloadedForPromotion = listingRepository.findByIdForUpdate(listingId).orElseThrow();
            promoteFromWaitlistIfPossible(reloadedForPromotion);
        }

        // F03.11.1 §3.3 ステップ 1: キャンセル料の徴収要求を発火する。
        //
        // 徴収そのものはここで行わない。キャンセルは利用者の意思表示であり、この時点で枠の復帰・
        // キャンセル待ちの昇格が既に走っている。決済の失敗でそれらを巻き戻すと整合が壊れるため、
        // 徴収は本トランザクションのコミット後（AFTER_COMMIT）に非同期で走らせる（§3.1-1 / §3.1-2）。
        // したがってキャンセル API のレスポンスに決済の成否は乗らず、記録の paymentStatus として後から反映される。
        //
        // 与信は個人申込にしか立たないため（RecruitmentChargeAuthorizationListener の発火条件）、
        // チーム申込では徴収要求も出さない（§8）。
        boolean isUserApplication = participant.getParticipantType() == RecruitmentParticipantType.USER;
        if (fee.feeAmount() > 0 && isUserApplication) {
            eventPublisher.publishEvent(new RecruitmentCancellationFeeChargeRequestedEvent(
                    cancellationRecord.getId(), listingId, participant.getId(), userId, fee.feeAmount()));
        }

        log.info("F03.11 本人キャンセル: listingId={}, userId={}, fee={}",
                listingId, userId, fee.feeAmount());
        return mapper.toParticipantResponse(participant);
    }

    // ===========================================
    // 参加者一覧・出席管理 (管理者)
    // ===========================================

    public Page<RecruitmentParticipantResponse> listParticipants(Long listingId, Long userId, Pageable pageable) {
        RecruitmentListingEntity listing = listingService.findOrThrow(listingId);
        RecruitmentOperationalScopeGuard.requireTeamOrOrganization(listing);
        accessControlService.checkAdminOrAbove(userId, listing.getScopeId(), listing.getScopeType().name());

        return participantRepository.findByListingIdOrderByAppliedAtAsc(listingId, pageable)
                .map(mapper::toParticipantResponse);
    }

    @Transactional
    public RecruitmentParticipantResponse markAttended(Long listingId, Long participantId, Long userId) {
        RecruitmentListingEntity listing = listingService.findOrThrow(listingId);
        RecruitmentOperationalScopeGuard.requireTeamOrOrganization(listing);
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

    // ===========================================
    // §5.3 キャンセル待ち昇格
    // ===========================================

    /**
     * §5.3 キャンセル確定後にキャンセル待ちがいれば先頭1件を CONFIRMED に昇格する。
     * PESSIMISTIC_WRITE ロック取得済みのトランザクション内で呼ぶこと。
     *
     * @param listing 対象の募集枠（findByIdForUpdate で取得済みであること）
     */
    private void promoteFromWaitlistIfPossible(RecruitmentListingEntity listing) {
        if (listing.getConfirmedCount() >= listing.getCapacity()) {
            // まだ満員なら昇格不要
            return;
        }
        participantRepository.findFirstWaitlistedForUpdate(listing.getId()).ifPresent(candidate -> {
            RecruitmentParticipantStatus oldStatus = candidate.getStatus();
            candidate.promoteToConfirmed();
            listing.decrementWaitlist();
            listing.incrementConfirmed();
            participantRepository.save(candidate);
            listingRepository.save(listing);

            historyRepository.save(RecruitmentParticipantHistoryEntity.builder()
                    .participantId(candidate.getId())
                    .listingId(listing.getId())
                    .oldStatus(oldStatus)
                    .newStatus(RecruitmentParticipantStatus.CONFIRMED)
                    .changedBy(null)
                    .changeReason(ParticipantHistoryReason.AUTO_PROMOTE)
                    .build());

            // 応募時だけでなくキャンセル待ちの昇格時にも起票しないと、支払者の決済画面が恒久的に 404 になる。
            publishPaymentAuthorizationIfNeeded(listing, candidate, candidate.getUserId());

            log.info("F03.11 Phase3 キャンセル待ち昇格: listingId={}, userId={}",
                    listing.getId(), candidate.getUserId());
        });
    }

    /**
     * F22.1 市: 有料の個人応募が CONFIRMED になった時点で謝礼の与信開始イベントを発火する。
     */
    private void publishPaymentAuthorizationIfNeeded(
            RecruitmentListingEntity listing,
            RecruitmentParticipantEntity participant,
            Long payerUserId) {
        if (!Boolean.TRUE.equals(listing.getPaymentEnabled())
                || listing.getPrice() == null
                || participant.getUserId() == null
                || payerUserId == null) {
            return;
        }

        // payment.escrow が購読し ConnectChargeService.authorize を呼ぶ（疎結合・クロスドメイン FK 無し）。
        eventPublisher.publishEvent(new RecruitmentParticipantConfirmedEvent(
                listing.getId(),
                participant.getId(),
                payerUserId,
                listing.getScopeType().name(),
                listing.getScopeId(),
                listing.getPayeeKind(),
                listing.getPayeeUserId(),
                listing.getPrice().longValue(),
                // 役務日（役務完了の見込み＝札の start_at）。第三陣-b で「成立〜役務日 > 7日」なら成立時に与信せず
                // 完了時即時払い（DEFERRED）へフォールバックする判定に使う。start_at 未設定の札は null（安全側で従来与信）。
                listing.getStartAt()));
    }
}
