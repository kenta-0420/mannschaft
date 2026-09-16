package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.AttendanceStatus;
import com.mannschaft.app.schedule.ScheduleDelegationStatus;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.entity.ScheduleAttendanceEntity;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.event.ScheduleDelegationNotificationEvent;
import com.mannschaft.app.schedule.repository.ScheduleAttendanceRepository;
import com.mannschaft.app.schedule.repository.ScheduleDelegationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * スケジュール代理出席サービス（F03.10 §5）。
 *
 * <p>代理指定・承認・拒否・取消と、それに伴う出欠ステータス連動（§5.4）を担当する。
 * schedule_attendances は schedule ドメイン内のため、出欠連動は同一トランザクションで更新する
 * （CLAUDE.md 原則5）。通知は業務トランザクションの内側では発火せず、{@link ScheduleDelegationNotificationEvent} を
 * publish して AFTER_COMMIT 後に {@link ScheduleDelegationNotifier} が配送する（Issue #2990）。</p>
 *
 * <p>手本: {@code com.mannschaft.app.proxyvote.service.ProxyDelegationService}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScheduleDelegationService {

    private final ScheduleDelegationRepository delegationRepository;
    private final ScheduleAttendanceRepository attendanceRepository;
    private final ScheduleService scheduleService;
    private final ScheduleDelegationValidator validator;
    /**
     * Issue #2990 L2: 通知は業務トランザクション内で発火せず、{@link ScheduleDelegationNotificationEvent}
     * を publish して AFTER_COMMIT 後に {@link ScheduleDelegationNotifier} が配送する（原則5）。
     */
    private final ApplicationEventPublisher eventPublisher;
    private final ScheduleAccessGuard scheduleAccessGuard;

    /**
     * 代理を指定する（§5.1 + §5.6）。
     *
     * <p>バリデーション → レコード作成 → {@code is_proxy_auto_accept} で ACCEPTED/PENDING 分岐 →
     * 出欠連動（§5.4） → 通知（§8）。</p>
     *
     * @param scheduleId  スケジュール ID
     * @param delegatorId 委任者 user_id
     * @param delegateId  代理人 user_id
     * @param reason      委任理由（任意）
     * @return 作成された委任エンティティ
     */
    @Transactional
    public ScheduleDelegationEntity createDelegation(Long scheduleId, Long delegatorId,
                                                     Long delegateId, String reason) {
        ScheduleEntity schedule = scheduleService.getSchedule(scheduleId);
        validator.validateForCreate(schedule, delegatorId, delegateId);

        boolean autoAccept = Boolean.TRUE.equals(schedule.getIsProxyAutoAccept());
        ScheduleDelegationStatus initialStatus = autoAccept
                ? ScheduleDelegationStatus.ACCEPTED : ScheduleDelegationStatus.PENDING;

        ScheduleDelegationEntity delegation = ScheduleDelegationEntity.builder()
                .scheduleId(scheduleId)
                .delegatorId(delegatorId)
                .delegateId(delegateId)
                .organizationId(schedule.getOrganizationId())
                .teamId(schedule.getTeamId())
                .status(initialStatus)
                .reason(reason)
                .build();
        if (autoAccept) {
            // ACCEPTED で作成する場合は reviewedAt をセットする
            delegation.accept();
        }
        delegation = delegationRepository.save(delegation);

        // 出欠連動（§5.4）: 委任者 → ABSENT は常に行う
        updateDelegatorAbsent(scheduleId, delegatorId);

        if (autoAccept) {
            // ACCEPTED → 代理人 → ATTENDING（is_proxy_input=TRUE）
            updateDelegateAttending(scheduleId, delegateId);
            eventPublisher.publishEvent(new ScheduleDelegationNotificationEvent(
                    delegation.getId(), ScheduleDelegationNotificationEvent.Kind.AUTO_ACCEPTED));
        } else {
            eventPublisher.publishEvent(new ScheduleDelegationNotificationEvent(
                    delegation.getId(), ScheduleDelegationNotificationEvent.Kind.REQUEST_PENDING));
        }

        log.info("代理指定: scheduleId={}, delegatorId={}, delegateId={}, status={}",
                scheduleId, delegatorId, delegateId, initialStatus);
        return delegation;
    }

    /**
     * 代理を承認する（§5.2。PENDING のみ・代理人本人のみ）。
     *
     * @param delegationId  委任 ID
     * @param actingUserId  操作ユーザー（代理人本人である必要がある）
     * @return 更新後の委任エンティティ
     */
    @Transactional
    public ScheduleDelegationEntity accept(UUID delegationId, Long actingUserId) {
        ScheduleDelegationEntity delegation = findOrThrow(delegationId);
        scheduleAccessGuard.requireDelegationDelegate(delegation, actingUserId);
        if (delegation.getStatus() != ScheduleDelegationStatus.PENDING) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_PENDING);
        }

        delegation.accept();
        delegation = delegationRepository.save(delegation);

        // 代理人 → ATTENDING（is_proxy_input=TRUE）
        updateDelegateAttending(delegation.getScheduleId(), delegation.getDelegateId());
        eventPublisher.publishEvent(new ScheduleDelegationNotificationEvent(
                delegation.getId(), ScheduleDelegationNotificationEvent.Kind.ACCEPTED));

        log.info("代理承認: delegationId={}, delegateId={}", delegationId, actingUserId);
        return delegation;
    }

    /**
     * 代理を拒否する（§5.2。PENDING のみ・代理人本人のみ）。
     *
     * @param delegationId 委任 ID
     * @param actingUserId 操作ユーザー（代理人本人である必要がある）
     * @return 更新後の委任エンティティ
     */
    @Transactional
    public ScheduleDelegationEntity reject(UUID delegationId, Long actingUserId) {
        ScheduleDelegationEntity delegation = findOrThrow(delegationId);
        scheduleAccessGuard.requireDelegationDelegate(delegation, actingUserId);
        if (delegation.getStatus() != ScheduleDelegationStatus.PENDING) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_PENDING);
        }

        delegation.reject();
        delegation = delegationRepository.save(delegation);

        // 委任者の出欠は ABSENT のまま（ユーザーが手動再設定）。通知のみ。
        eventPublisher.publishEvent(new ScheduleDelegationNotificationEvent(
                delegation.getId(), ScheduleDelegationNotificationEvent.Kind.REJECTED));

        log.info("代理拒否: delegationId={}, delegateId={}", delegationId, actingUserId);
        return delegation;
    }

    /**
     * 委任者が自分の代理を取り消す（DELETE /me 相当・§5.3）。
     *
     * <p>アクティブ（PENDING/ACCEPTED）な代理を CANCELLED にし、代理人の代理由来の出欠
     * （is_proxy_input=TRUE）のみ UNDECIDED に巻き戻す（本人入力は温存）。</p>
     *
     * @param scheduleId  スケジュール ID
     * @param delegatorId 委任者 user_id
     */
    @Transactional
    public void withdraw(Long scheduleId, Long delegatorId) {
        ScheduleDelegationEntity delegation = delegationRepository
                .findFirstByScheduleIdAndDelegatorIdAndStatusIn(scheduleId, delegatorId,
                        List.of(ScheduleDelegationStatus.PENDING, ScheduleDelegationStatus.ACCEPTED))
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_FOUND));

        cancelInternal(delegation);
        log.info("代理取消（委任者操作）: scheduleId={}, delegatorId={}", scheduleId, delegatorId);
    }

    /**
     * システム都合で代理を取り消す（退会連動・委任者の出欠変更フックなどから呼ぶ・§5.3）。
     *
     * @param delegation 取り消す委任
     */
    @Transactional
    public void cancelBySystem(ScheduleDelegationEntity delegation) {
        cancelInternal(delegation);
    }

    /**
     * メンバー退会連動で代理を取り消し、相手方に通知する（§5.8）。
     *
     * <p>退会したのが代理人なら委任者へ「再設定」通知、委任者なら代理人へ「取消」通知を送る。
     * 代理由来の代理人出欠（is_proxy_input=TRUE）は本処理でも巻き戻す。</p>
     *
     * @param delegation 取り消す委任
     * @param leftUserId 退会したユーザー
     */
    @Transactional
    public void cancelOnMemberLeft(ScheduleDelegationEntity delegation, Long leftUserId) {
        boolean delegateLeft = delegation.getDelegateId().equals(leftUserId);
        delegation.cancel();
        delegationRepository.save(delegation);
        revertDelegateProxyAttendance(delegation.getScheduleId(), delegation.getDelegateId());
        if (delegateLeft) {
            eventPublisher.publishEvent(new ScheduleDelegationNotificationEvent(
                    delegation.getId(), ScheduleDelegationNotificationEvent.Kind.DELEGATE_LEFT));
        } else {
            eventPublisher.publishEvent(new ScheduleDelegationNotificationEvent(
                    delegation.getId(), ScheduleDelegationNotificationEvent.Kind.DELEGATOR_LEFT));
        }
        log.info("退会連動で代理取消: scheduleId={}, leftUserId={}, delegateLeft={}",
                delegation.getScheduleId(), leftUserId, delegateLeft);
    }

    /**
     * 退会連動 / クリーンアップ用: 指定スコープで当事者が指定ユーザーのアクティブ委任を取得する（§5.8）。
     */
    public List<ScheduleDelegationEntity> findActiveByScopeAndInvolvedUser(
            Long organizationId, Long teamId, Long userId) {
        return delegationRepository.findActiveByScopeAndInvolvedUser(organizationId, teamId, userId,
                List.of(ScheduleDelegationStatus.PENDING, ScheduleDelegationStatus.ACCEPTED));
    }

    /**
     * クリーンアップバッチ用: アクティブ委任を全件取得する（§5.8）。
     */
    public List<ScheduleDelegationEntity> findAllActive() {
        return delegationRepository.findByStatusIn(
                List.of(ScheduleDelegationStatus.PENDING, ScheduleDelegationStatus.ACCEPTED));
    }

    /**
     * 委任者が自分の出欠を ATTENDING に更新した時のフック（§5.3）。
     *
     * <p>PENDING 状態の代理を自動 CANCELLED にする。ACCEPTED はキャンセルしない（明示的な DELETE が必要）。
     * {@link ScheduleAttendanceService#respondAttendance} から呼ばれる。</p>
     *
     * @param scheduleId スケジュール ID
     * @param userId     出欠を更新したユーザー（委任者候補）
     * @param newStatus  新しい出欠ステータス
     */
    @Transactional
    public void onDelegatorAttendanceChanged(Long scheduleId, Long userId, AttendanceStatus newStatus) {
        if (newStatus != AttendanceStatus.ATTENDING) {
            return;
        }
        delegationRepository
                .findFirstByScheduleIdAndDelegatorIdAndStatusIn(scheduleId, userId,
                        List.of(ScheduleDelegationStatus.PENDING))
                .ifPresent(delegation -> {
                    delegation.cancel();
                    delegationRepository.save(delegation);
                    eventPublisher.publishEvent(new ScheduleDelegationNotificationEvent(
                            delegation.getId(), ScheduleDelegationNotificationEvent.Kind.CANCELLED));
                    log.info("委任者の自己出席により PENDING 代理を自動取消: scheduleId={}, delegatorId={}",
                            scheduleId, userId);
                });
    }

    // ---- 一覧/取得（第三陣 Controller が呼ぶ） ----

    /**
     * ADMIN 向け代理一覧（§4.1）。指定スケジュールの全代理を作成日時降順でページング取得する。
     */
    public Page<ScheduleDelegationEntity> listForAdmin(Long scheduleId, Pageable pageable) {
        return delegationRepository.findByScheduleIdOrderByCreatedAtDesc(scheduleId, pageable);
    }

    /**
     * 委任者視点（§4.1 asDelegator）。PENDING/ACCEPTED のみ返却する。
     */
    public Optional<ScheduleDelegationEntity> findAsDelegator(Long scheduleId, Long delegatorId) {
        return delegationRepository.findFirstByScheduleIdAndDelegatorIdAndStatusIn(
                scheduleId, delegatorId,
                List.of(ScheduleDelegationStatus.PENDING, ScheduleDelegationStatus.ACCEPTED));
    }

    /**
     * 代理人視点（§4.1 asDelegate）。PENDING の依頼のみ返却する。
     */
    public Optional<ScheduleDelegationEntity> findAsDelegate(Long scheduleId, Long delegateId) {
        return delegationRepository.findByScheduleIdAndDelegateIdAndStatusIn(
                        scheduleId, delegateId, List.of(ScheduleDelegationStatus.PENDING))
                .stream()
                .findFirst();
    }

    /**
     * 委任を ID で取得する（当事者・ADMIN の IDOR チェックは Controller で行う）。
     */
    public ScheduleDelegationEntity getById(UUID delegationId) {
        return findOrThrow(delegationId);
    }

    // ---- private ----

    private ScheduleDelegationEntity findOrThrow(UUID delegationId) {
        return delegationRepository.findById(delegationId)
                .orElseThrow(() -> new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_FOUND));
    }

    private void cancelInternal(ScheduleDelegationEntity delegation) {
        delegation.cancel();
        delegationRepository.save(delegation);
        // 代理由来の代理人出欠のみ UNDECIDED に巻き戻す（本人入力は温存）
        revertDelegateProxyAttendance(delegation.getScheduleId(), delegation.getDelegateId());
        eventPublisher.publishEvent(new ScheduleDelegationNotificationEvent(
                delegation.getId(), ScheduleDelegationNotificationEvent.Kind.CANCELLED));
    }

    /**
     * 委任者の出欠を ABSENT に更新する（§5.4）。委任者本人の入力なので is_proxy_input は変更しない。
     */
    private void updateDelegatorAbsent(Long scheduleId, Long delegatorId) {
        attendanceRepository.findByScheduleIdAndUserId(scheduleId, delegatorId)
                .ifPresent(attendance -> {
                    attendance.respond(AttendanceStatus.ABSENT, attendance.getComment());
                    attendanceRepository.save(attendance);
                });
    }

    /**
     * 代理人の出欠を ATTENDING に更新し、代理由来であることを is_proxy_input=TRUE で記録する（§5.4）。
     */
    private void updateDelegateAttending(Long scheduleId, Long delegateId) {
        attendanceRepository.findByScheduleIdAndUserId(scheduleId, delegateId)
                .ifPresent(attendance -> {
                    attendance.respond(AttendanceStatus.ATTENDING, attendance.getComment());
                    // 代理関係が自動投入したレコードであることを識別する（§5.3 / §5.4 / F14.1）
                    ScheduleAttendanceEntity marked = attendance.toBuilder()
                            .isProxyInput(true)
                            .build();
                    attendanceRepository.save(marked);
                });
    }

    /**
     * 代理由来（is_proxy_input=TRUE）の代理人出欠のみ UNDECIDED に戻す。本人入力（FALSE）は温存する（§5.3）。
     */
    private void revertDelegateProxyAttendance(Long scheduleId, Long delegateId) {
        attendanceRepository.findByScheduleIdAndUserId(scheduleId, delegateId)
                .filter(a -> Boolean.TRUE.equals(a.getIsProxyInput()))
                .ifPresent(attendance -> {
                    attendance.respond(AttendanceStatus.UNDECIDED, attendance.getComment());
                    ScheduleAttendanceEntity reset = attendance.toBuilder()
                            .isProxyInput(false)
                            .build();
                    attendanceRepository.save(reset);
                });
    }
}
