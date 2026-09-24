package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.CheckinType;
import com.mannschaft.app.event.EventDelegationStatus;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.entity.EventAttendanceMode;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventDelegationEntity;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.event.EventDelegationAcceptedEvent;
import com.mannschaft.app.event.event.EventDelegationNotificationEvent;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventDelegationRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
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
 * イベント代理出席サービス（F03.10 §5）。
 *
 * <p>{@link com.mannschaft.app.schedule.service.ScheduleDelegationService} と同型 + イベント固有の
 * RSVP 連動（§5.4）・代理チェックイン（§5.7）・F08.3 投票代理連携（§5.5）を担当する。</p>
 *
 * <p>RSVP・チェックインは event ドメイン内のため同一トランザクションで更新する。
 * proxyvote ドメインとの連携は {@link EventDelegationAcceptedEvent} を発火し、
 * proxyvote 側の {@code @TransactionalEventListener(AFTER_COMMIT)} が受信する（CLAUDE.md 原則5）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventDelegationService {

    private final EventDelegationRepository delegationRepository;
    private final EventRsvpResponseRepository rsvpResponseRepository;
    private final EventCheckinRepository checkinRepository;
    private final EventService eventService;
    private final EventDelegationValidator validator;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 代理を指定する（§5.1 + §5.6）。
     *
     * @param eventId            イベント ID
     * @param delegatorId        委任者 user_id
     * @param delegateId         代理人 user_id
     * @param reason             委任理由（任意）
     * @param proxyVoteSessionId 連携する投票セッション ID（任意・null 可）
     * @return 作成された委任エンティティ
     */
    @Transactional
    public EventDelegationEntity createDelegation(Long eventId, Long delegatorId, Long delegateId,
                                                  String reason, Long proxyVoteSessionId) {
        EventEntity event = eventService.findEventOrThrow(eventId);
        validator.validateForCreate(event, delegatorId, delegateId, proxyVoteSessionId);

        boolean autoAccept = Boolean.TRUE.equals(event.getIsProxyAutoAccept());
        EventDelegationStatus initialStatus = autoAccept
                ? EventDelegationStatus.ACCEPTED : EventDelegationStatus.PENDING;

        EventDelegationEntity delegation = EventDelegationEntity.builder()
                .eventId(eventId)
                .delegatorId(delegatorId)
                .delegateId(delegateId)
                .organizationId(scopeOrgId(event))
                .teamId(scopeTeamId(event))
                .status(initialStatus)
                .reason(reason)
                .proxyVoteSessionId(proxyVoteSessionId)
                .build();
        if (autoAccept) {
            delegation.accept();
        }
        delegation = delegationRepository.save(delegation);

        // 出欠連動（§5.4）: 委任者 → NOT_ATTENDING（RSVP モードのみ）
        updateDelegatorNotAttending(event, delegatorId);

        if (autoAccept) {
            onAccepted(event, delegation);
            publishNotification(delegation, EventDelegationNotificationEvent.Kind.AUTO_ACCEPTED);
        } else {
            publishNotification(delegation, EventDelegationNotificationEvent.Kind.REQUEST_PENDING);
        }

        log.info("イベント代理指定: eventId={}, delegatorId={}, delegateId={}, status={}",
                eventId, delegatorId, delegateId, initialStatus);
        return delegation;
    }

    /**
     * 代理を承認する（§5.2。PENDING のみ・代理人本人のみ）。
     */
    @Transactional
    public EventDelegationEntity accept(UUID delegationId, Long actingUserId) {
        EventDelegationEntity delegation = findOrThrow(delegationId);
        if (!delegation.getDelegateId().equals(actingUserId)) {
            throw new BusinessException(EventErrorCode.DELEGATION_NOT_DELEGATE);
        }
        if (delegation.getStatus() != EventDelegationStatus.PENDING) {
            throw new BusinessException(EventErrorCode.DELEGATION_NOT_PENDING);
        }

        delegation.accept();
        delegation = delegationRepository.save(delegation);

        EventEntity event = eventService.findEventOrThrow(delegation.getEventId());
        onAccepted(event, delegation);
        publishNotification(delegation, EventDelegationNotificationEvent.Kind.ACCEPTED);

        log.info("イベント代理承認: delegationId={}, delegateId={}", delegationId, actingUserId);
        return delegation;
    }

    /**
     * 代理を拒否する（§5.2。PENDING のみ・代理人本人のみ）。
     */
    @Transactional
    public EventDelegationEntity reject(UUID delegationId, Long actingUserId) {
        EventDelegationEntity delegation = findOrThrow(delegationId);
        if (!delegation.getDelegateId().equals(actingUserId)) {
            throw new BusinessException(EventErrorCode.DELEGATION_NOT_DELEGATE);
        }
        if (delegation.getStatus() != EventDelegationStatus.PENDING) {
            throw new BusinessException(EventErrorCode.DELEGATION_NOT_PENDING);
        }

        delegation.reject();
        delegation = delegationRepository.save(delegation);
        publishNotification(delegation, EventDelegationNotificationEvent.Kind.REJECTED);

        log.info("イベント代理拒否: delegationId={}, delegateId={}", delegationId, actingUserId);
        return delegation;
    }

    /**
     * 委任者が自分の代理を取り消す（DELETE /me 相当・§5.3）。
     */
    @Transactional
    public void withdraw(Long eventId, Long delegatorId) {
        EventDelegationEntity delegation = delegationRepository
                .findFirstByEventIdAndDelegatorIdAndStatusIn(eventId, delegatorId,
                        List.of(EventDelegationStatus.PENDING, EventDelegationStatus.ACCEPTED))
                .orElseThrow(() -> new BusinessException(EventErrorCode.DELEGATION_NOT_FOUND));

        cancelInternal(delegation);
        log.info("イベント代理取消（委任者操作）: eventId={}, delegatorId={}", eventId, delegatorId);
    }

    /**
     * システム都合で代理を取り消す（退会連動・バッチなどから呼ぶ・§5.3 / §5.8）。
     */
    @Transactional
    public void cancelBySystem(EventDelegationEntity delegation) {
        cancelInternal(delegation);
    }

    /**
     * メンバー退会連動で代理を取り消し、相手方に通知する（§5.8）。
     *
     * @param delegation 取り消す委任
     * @param leftUserId 退会したユーザー
     */
    @Transactional
    public void cancelOnMemberLeft(EventDelegationEntity delegation, Long leftUserId) {
        boolean delegateLeft = delegation.getDelegateId().equals(leftUserId);
        delegation.cancel();
        delegationRepository.save(delegation);
        publishNotification(delegation, delegateLeft
                ? EventDelegationNotificationEvent.Kind.DELEGATE_LEFT
                : EventDelegationNotificationEvent.Kind.DELEGATOR_LEFT);
        log.info("退会連動で代理取消: eventId={}, leftUserId={}, delegateLeft={}",
                delegation.getEventId(), leftUserId, delegateLeft);
    }

    /**
     * 退会連動 / クリーンアップ用: 指定スコープで当事者が指定ユーザーのアクティブ委任を取得する（§5.8）。
     */
    public List<EventDelegationEntity> findActiveByScopeAndInvolvedUser(
            Long organizationId, Long teamId, Long userId) {
        return delegationRepository.findActiveByScopeAndInvolvedUser(organizationId, teamId, userId,
                List.of(EventDelegationStatus.PENDING, EventDelegationStatus.ACCEPTED));
    }

    /**
     * クリーンアップバッチ用: アクティブ委任を全件取得する（§5.8）。
     */
    public List<EventDelegationEntity> findAllActive() {
        return delegationRepository.findByStatusIn(
                List.of(EventDelegationStatus.PENDING, EventDelegationStatus.ACCEPTED));
    }

    /**
     * 代理チェックイン（§5.7）。
     *
     * <p>delegation.status = ACCEPTED かつ（delegate_id = actingUser または ADMIN）でのみ実行可能。
     * 二重チェックインは {@code existsByDelegationId} で 409 ガードする。</p>
     *
     * @param eventId      イベント ID
     * @param delegationId 代理委任 ID
     * @param actingUserId 操作ユーザー
     * @param isAdmin      ADMIN / DEPUTY_ADMIN(CHECKIN_EVENTS) 権限を持つか（権限解決は Controller 層）
     * @return 作成されたチェックインエンティティ
     */
    @Transactional
    public EventCheckinEntity proxyCheckin(Long eventId, UUID delegationId, Long actingUserId, boolean isAdmin) {
        EventDelegationEntity delegation = findOrThrow(delegationId);

        // 実行権限: 代理人本人または ADMIN
        if (!isAdmin && !delegation.getDelegateId().equals(actingUserId)) {
            throw new BusinessException(EventErrorCode.DELEGATION_CHECKIN_FORBIDDEN);
        }
        // status = ACCEPTED
        if (delegation.getStatus() != EventDelegationStatus.ACCEPTED) {
            throw new BusinessException(EventErrorCode.DELEGATION_CHECKIN_NOT_ACCEPTED);
        }
        // 二重チェックイン防止（§3.5 / §5.7）
        if (checkinRepository.existsByDelegationId(delegationId)) {
            throw new BusinessException(EventErrorCode.DELEGATION_ALREADY_CHECKED_IN);
        }

        EventCheckinEntity checkin = EventCheckinEntity.builder()
                .eventId(eventId)
                .ticketId(null)                       // 代理チェックインはチケットレス
                .checkinType(CheckinType.PROXY)
                .delegationId(delegationId)
                .rollCallUserId(delegation.getDelegatorId()) // 出席を肩代わりする対象＝委任者
                .checkedInBy(actingUserId)
                .build();
        checkin = checkinRepository.save(checkin);

        log.info("代理チェックイン: eventId={}, delegationId={}, delegateId={}, actingUserId={}",
                eventId, delegationId, delegation.getDelegateId(), actingUserId);
        return checkin;
    }

    // ---- 一覧/取得（第三陣 Controller が呼ぶ） ----

    /** ADMIN 向け代理一覧（§4.2）。 */
    public Page<EventDelegationEntity> listForAdmin(Long eventId, Pageable pageable) {
        return delegationRepository.findByEventIdOrderByCreatedAtDesc(eventId, pageable);
    }

    /** 委任者視点（§4.2 asDelegator）。PENDING/ACCEPTED のみ返却する。 */
    public Optional<EventDelegationEntity> findAsDelegator(Long eventId, Long delegatorId) {
        return delegationRepository.findFirstByEventIdAndDelegatorIdAndStatusIn(
                eventId, delegatorId,
                List.of(EventDelegationStatus.PENDING, EventDelegationStatus.ACCEPTED));
    }

    /** 代理人視点（§4.2 asDelegate）。PENDING の依頼のみ返却する。 */
    public Optional<EventDelegationEntity> findAsDelegate(Long eventId, Long delegateId) {
        return delegationRepository.findByEventIdAndDelegateIdAndStatusIn(
                        eventId, delegateId, List.of(EventDelegationStatus.PENDING))
                .stream()
                .findFirst();
    }

    /** 委任を ID で取得する（IDOR チェックは Controller で行う）。 */
    public EventDelegationEntity getById(UUID delegationId) {
        return findOrThrow(delegationId);
    }

    /**
     * F08.3 連携で作成した proxy_delegations.id を event_delegations に逆設定する（§5.5）。
     *
     * <p>proxyvote ドメインの AFTER_COMMIT リスナーから別トランザクションで呼ばれる。
     * 連携がスキップされた場合（{@code proxyDelegationId == null}）は何もしない。</p>
     *
     * @param delegationId      event_delegations.id
     * @param proxyDelegationId 作成された proxy_delegations.id（スキップ時は null）
     */
    @Transactional
    public void linkProxyDelegation(UUID delegationId, Long proxyDelegationId) {
        if (proxyDelegationId == null) {
            return;
        }
        delegationRepository.findById(delegationId).ifPresent(delegation -> {
            delegation.linkProxyDelegation(proxyDelegationId);
            delegationRepository.save(delegation);
            log.info("event_delegations.proxy_delegation_id 設定: delegationId={}, proxyDelegationId={}",
                    delegationId, proxyDelegationId);
        });
    }

    // ---- private ----

    private EventDelegationEntity findOrThrow(UUID delegationId) {
        return delegationRepository.findById(delegationId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.DELEGATION_NOT_FOUND));
    }

    /**
     * ACCEPTED 確定時の共通処理: RSVP 反映（§5.4） + F08.3 連携イベント発火（§5.5）。
     */
    private void onAccepted(EventEntity event, EventDelegationEntity delegation) {
        // RSVP モード: 代理人の RSVP を ATTENDING に反映（REGISTRATION モードは自動作成しない・§5.4）
        if (event.getAttendanceMode() == EventAttendanceMode.RSVP) {
            applyDelegateRsvpAttending(event.getId(), delegation.getDelegateId());
        }
        // F08.3 連携（§5.5）: ACCEPTED 確定をイベント発火（proxy_delegations はこの場で作らない）
        eventPublisher.publishEvent(new EventDelegationAcceptedEvent(
                delegation.getId(),
                event.getId(),
                delegation.getDelegatorId(),
                delegation.getDelegateId(),
                event.getScopeType(),
                event.getScopeId(),
                delegation.getProxyVoteSessionId()));
    }

    private void cancelInternal(EventDelegationEntity delegation) {
        delegation.cancel();
        delegationRepository.save(delegation);
        publishNotification(delegation, EventDelegationNotificationEvent.Kind.CANCELLED);
    }

    /**
     * 代理出席の通知配送要求を publish する（Issue #2990 L5）。
     *
     * <p>業務トランザクションの内側では ID と種別だけを載せたイベントを発行するに留め、
     * 実際の通知生成・配信は {@link EventDelegationNotifier}（{@code AFTER_COMMIT} +
     * {@code @Async("event-pool")}）が行う。これにより通知の失敗が代理の指定・承認・拒否・
     * 取消といった業務処理を巻き戻さなくなる。</p>
     */
    private void publishNotification(EventDelegationEntity delegation,
                                     EventDelegationNotificationEvent.Kind kind) {
        eventPublisher.publishEvent(new EventDelegationNotificationEvent(delegation.getId(), kind));
    }

    /**
     * 委任者の RSVP を NOT_ATTENDING に更新する（RSVP モードのみ・§5.4）。
     */
    private void updateDelegatorNotAttending(EventEntity event, Long delegatorId) {
        if (event.getAttendanceMode() != EventAttendanceMode.RSVP) {
            return;
        }
        rsvpResponseRepository.findByEventIdAndUserId(event.getId(), delegatorId)
                .ifPresent(rsvp -> {
                    rsvp.updateResponse("NOT_ATTENDING", rsvp.getComment());
                    rsvpResponseRepository.save(rsvp);
                });
    }

    /**
     * 代理人の RSVP を ATTENDING に反映する（§5.4）。レコードが無い場合は新規作成する。
     */
    private void applyDelegateRsvpAttending(Long eventId, Long delegateId) {
        EventRsvpResponseEntity rsvp = rsvpResponseRepository
                .findByEventIdAndUserId(eventId, delegateId)
                .orElseGet(() -> EventRsvpResponseEntity.builder()
                        .eventId(eventId)
                        .userId(delegateId)
                        .build());
        rsvp.updateResponse("ATTENDING", rsvp.getComment());
        rsvpResponseRepository.save(rsvp);
    }

    private Long scopeOrgId(EventEntity event) {
        return event.getScopeType() == com.mannschaft.app.event.EventScopeType.ORGANIZATION
                ? event.getScopeId() : null;
    }

    private Long scopeTeamId(EventEntity event) {
        return event.getScopeType() == com.mannschaft.app.event.EventScopeType.TEAM
                ? event.getScopeId() : null;
    }
}
