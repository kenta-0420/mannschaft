package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventMapper;
import com.mannschaft.app.event.EventStatus;
import com.mannschaft.app.event.RegistrationStatus;
import com.mannschaft.app.event.dto.CreateRegistrationRequest;
import com.mannschaft.app.event.dto.GuestRegistrationRequest;
import com.mannschaft.app.event.dto.RegistrationResponse;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.entity.EventGuestInviteTokenEntity;
import com.mannschaft.app.event.entity.EventRegistrationEntity;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.repository.EventGuestInviteTokenRepository;
import com.mannschaft.app.event.repository.EventRegistrationRepository;
import com.mannschaft.app.event.repository.EventTicketTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * イベント参加登録サービス。参加登録のCRUD・承認・却下を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventRegistrationService {

    private final EventRegistrationRepository registrationRepository;
    private final EventTicketTypeRepository ticketTypeRepository;
    private final EventGuestInviteTokenRepository inviteTokenRepository;
    private final EventService eventService;
    private final EventTicketService ticketService;
    private final EventMapper eventMapper;

    /**
     * イベントの参加登録一覧をページング取得する。
     *
     * @param eventId  イベントID
     * @param status   ステータスフィルタ
     * @param pageable ページング情報
     * @return 参加登録レスポンスのページ
     */
    public Page<RegistrationResponse> listRegistrations(Long eventId, String status, Pageable pageable) {
        Page<EventRegistrationEntity> page;
        if (status != null) {
            RegistrationStatus regStatus = RegistrationStatus.valueOf(status);
            page = registrationRepository.findByEventIdAndStatusOrderByCreatedAtDesc(eventId, regStatus, pageable);
        } else {
            page = registrationRepository.findByEventIdOrderByCreatedAtDesc(eventId, pageable);
        }
        return page.map(eventMapper::toRegistrationResponse);
    }

    /**
     * 参加登録詳細を取得する。
     *
     * @param registrationId 参加登録ID
     * @return 参加登録レスポンス
     */
    public RegistrationResponse getRegistration(Long registrationId) {
        EventRegistrationEntity entity = findRegistrationOrThrow(registrationId);
        return eventMapper.toRegistrationResponse(entity);
    }

    /**
     * 会員の参加登録を作成する。
     *
     * @param eventId イベントID
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成された参加登録レスポンス
     */
    @Transactional
    public RegistrationResponse createRegistration(Long eventId, Long userId, CreateRegistrationRequest request) {
        EventEntity event = eventService.findEventOrThrow(eventId);
        validateRegistrationOpen(event);

        if (registrationRepository.existsByEventIdAndUserId(eventId, userId)) {
            throw new BusinessException(EventErrorCode.ALREADY_REGISTERED);
        }

        EventTicketTypeEntity ticketType = findTicketTypeOrThrow(request.getTicketTypeId());
        int quantity = request.getQuantity() != null ? request.getQuantity() : 1;
        validateTicketTypeStock(ticketType, quantity);
        validateCapacity(event, quantity);

        RegistrationStatus initialStatus = event.getIsApprovalRequired()
                ? RegistrationStatus.PENDING
                : RegistrationStatus.APPROVED;

        EventRegistrationEntity entity = EventRegistrationEntity.builder()
                .eventId(eventId)
                .userId(userId)
                .ticketTypeId(request.getTicketTypeId())
                .status(initialStatus)
                .quantity(quantity)
                .note(request.getNote())
                .build();

        EventRegistrationEntity saved = registrationRepository.save(entity);

        if (initialStatus == RegistrationStatus.APPROVED) {
            ticketType.incrementIssuedCount(quantity);
            ticketTypeRepository.save(ticketType);
            ticketService.issueTickets(saved, ticketType, quantity);
            event.incrementRegistrationCount();
            // eventRepository.save は @Version による楽観ロックでEventService側で保存
        }

        log.info("参加登録作成: eventId={}, userId={}, registrationId={}", eventId, userId, saved.getId());
        return eventMapper.toRegistrationResponse(saved);
    }

    /**
     * ゲストの参加登録を作成する。
     *
     * @param eventId イベントID
     * @param request ゲスト登録リクエスト
     * @return 作成された参加登録レスポンス
     */
    @Transactional
    public RegistrationResponse createGuestRegistration(Long eventId, GuestRegistrationRequest request) {
        EventEntity event = eventService.findEventOrThrow(eventId);
        validateRegistrationOpen(event);

        EventGuestInviteTokenEntity token = inviteTokenRepository.findByToken(request.getInviteToken())
                .orElseThrow(() -> new BusinessException(EventErrorCode.INVALID_INVITE_TOKEN));

        if (!token.isUsable()) {
            throw new BusinessException(EventErrorCode.INVALID_INVITE_TOKEN);
        }
        if (!token.getEventId().equals(eventId)) {
            throw new BusinessException(EventErrorCode.INVALID_INVITE_TOKEN);
        }

        EventTicketTypeEntity ticketType = findTicketTypeOrThrow(request.getTicketTypeId());
        int quantity = request.getQuantity() != null ? request.getQuantity() : 1;
        validateTicketTypeStock(ticketType, quantity);
        validateCapacity(event, quantity);

        EventRegistrationEntity entity = EventRegistrationEntity.builder()
                .eventId(eventId)
                .ticketTypeId(request.getTicketTypeId())
                .guestName(request.getGuestName())
                .guestEmail(request.getGuestEmail())
                .guestPhone(request.getGuestPhone())
                .status(RegistrationStatus.APPROVED)
                .quantity(quantity)
                .note(request.getNote())
                .inviteTokenId(token.getId())
                .build();

        EventRegistrationEntity saved = registrationRepository.save(entity);

        token.incrementUsedCount();
        inviteTokenRepository.save(token);

        ticketType.incrementIssuedCount(quantity);
        ticketTypeRepository.save(ticketType);
        ticketService.issueTickets(saved, ticketType, quantity);
        event.incrementRegistrationCount();

        log.info("ゲスト参加登録作成: eventId={}, guestEmail={}, registrationId={}",
                eventId, request.getGuestEmail(), saved.getId());
        return eventMapper.toRegistrationResponse(saved);
    }

    /**
     * 参加登録を承認する。
     *
     * @param registrationId 参加登録ID
     * @param approvedBy     承認者ユーザーID
     * @return 更新された参加登録レスポンス
     */
    @Transactional
    public RegistrationResponse approveRegistration(Long registrationId, Long approvedBy) {
        EventRegistrationEntity entity = findRegistrationOrThrow(registrationId);
        if (entity.getStatus() != RegistrationStatus.PENDING) {
            throw new BusinessException(EventErrorCode.INVALID_REGISTRATION_STATUS);
        }

        EventTicketTypeEntity ticketType = findTicketTypeOrThrow(entity.getTicketTypeId());
        validateTicketTypeStock(ticketType, entity.getQuantity());

        entity.approve(approvedBy);
        EventRegistrationEntity saved = registrationRepository.save(entity);

        ticketType.incrementIssuedCount(entity.getQuantity());
        ticketTypeRepository.save(ticketType);
        ticketService.issueTickets(saved, ticketType, entity.getQuantity());

        EventEntity event = eventService.findEventOrThrow(entity.getEventId());
        event.incrementRegistrationCount();

        log.info("参加登録承認: registrationId={}, approvedBy={}", registrationId, approvedBy);
        return eventMapper.toRegistrationResponse(saved);
    }

    /**
     * 参加登録を却下する。
     *
     * @param registrationId 参加登録ID
     * @param rejectedBy     却下者ユーザーID
     * @return 更新された参加登録レスポンス
     */
    @Transactional
    public RegistrationResponse rejectRegistration(Long registrationId, Long rejectedBy) {
        EventRegistrationEntity entity = findRegistrationOrThrow(registrationId);
        if (entity.getStatus() != RegistrationStatus.PENDING) {
            throw new BusinessException(EventErrorCode.INVALID_REGISTRATION_STATUS);
        }

        entity.reject(rejectedBy);
        EventRegistrationEntity saved = registrationRepository.save(entity);
        log.info("参加登録却下: registrationId={}, rejectedBy={}", registrationId, rejectedBy);
        return eventMapper.toRegistrationResponse(saved);
    }

    /**
     * 参加登録をキャンセルする。
     *
     * @param registrationId 参加登録ID
     * @param reason         キャンセル理由
     * @return 更新された参加登録レスポンス
     */
    @Transactional
    public RegistrationResponse cancelRegistration(Long registrationId, String reason) {
        EventRegistrationEntity entity = findRegistrationOrThrow(registrationId);
        if (entity.getStatus() == RegistrationStatus.CANCELLED
                || entity.getStatus() == RegistrationStatus.REJECTED) {
            throw new BusinessException(EventErrorCode.INVALID_REGISTRATION_STATUS);
        }

        entity.cancel(reason);
        EventRegistrationEntity saved = registrationRepository.save(entity);

        // チケットもキャンセル
        ticketService.cancelTicketsByRegistration(registrationId);

        log.info("参加登録キャンセル: registrationId={}", registrationId);
        return eventMapper.toRegistrationResponse(saved);
    }

    /**
     * 参加登録をキャンセル待ちに変更する。
     *
     * @param registrationId 参加登録ID
     * @return 更新された参加登録レスポンス
     */
    @Transactional
    public RegistrationResponse waitlistRegistration(Long registrationId) {
        EventRegistrationEntity entity = findRegistrationOrThrow(registrationId);
        if (entity.getStatus() != RegistrationStatus.PENDING) {
            throw new BusinessException(EventErrorCode.INVALID_REGISTRATION_STATUS);
        }

        entity.waitlist();
        EventRegistrationEntity saved = registrationRepository.save(entity);
        log.info("参加登録キャンセル待ち: registrationId={}", registrationId);
        return eventMapper.toRegistrationResponse(saved);
    }

    /**
     * 参加登録を取得する。存在しない場合は例外をスローする。
     */
    private EventRegistrationEntity findRegistrationOrThrow(Long registrationId) {
        return registrationRepository.findById(registrationId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.REGISTRATION_NOT_FOUND));
    }

    /**
     * チケット種別を取得する。存在しない場合は例外をスローする。
     */
    private EventTicketTypeEntity findTicketTypeOrThrow(Long ticketTypeId) {
        return ticketTypeRepository.findById(ticketTypeId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.TICKET_TYPE_NOT_FOUND));
    }

    /**
     * 参加登録が受付中であることを検証する。
     */
    private void validateRegistrationOpen(EventEntity event) {
        if (event.getStatus() != EventStatus.REGISTRATION_OPEN) {
            throw new BusinessException(EventErrorCode.REGISTRATION_CLOSED);
        }
    }

    /**
     * チケット種別の在庫を検証する。
     */
    private void validateTicketTypeStock(EventTicketTypeEntity ticketType, int quantity) {
        if (!ticketType.hasStock(quantity)) {
            throw new BusinessException(EventErrorCode.TICKET_TYPE_SOLD_OUT);
        }
    }

    /**
     * イベントの定員を検証する。
     */
    private void validateCapacity(EventEntity event, int quantity) {
        if (event.getMaxCapacity() != null
                && event.getRegistrationCount() + quantity > event.getMaxCapacity()) {
            throw new BusinessException(EventErrorCode.CAPACITY_FULL);
        }
    }
}
