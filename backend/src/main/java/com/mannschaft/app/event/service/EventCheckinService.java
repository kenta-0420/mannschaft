package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.event.CheckinType;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventMapper;
import com.mannschaft.app.event.TicketStatus;
import com.mannschaft.app.event.dto.CheckinRequest;
import com.mannschaft.app.event.dto.CheckinResponse;
import com.mannschaft.app.event.dto.SelfCheckinRequest;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventTicketEntity;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRegistrationRepository;
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventTicketRepository;
import com.mannschaft.app.family.service.CareEventNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * イベントチェックインサービス。QRスキャン・セルフチェックインを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventCheckinService {

    private final EventCheckinRepository checkinRepository;
    private final EventTicketRepository ticketRepository;
    private final EventRepository eventRepository;
    private final EventRegistrationRepository registrationRepository;
    private final EventTicketService ticketService;
    private final EventMapper eventMapper;
    private final CareEventNotificationService careEventNotificationService;
    private final EventScopeAccessGuard eventScopeAccessGuard;

    /**
     * イベントのチェックイン一覧をページング取得する。
     *
     * @param eventId  イベントID
     * @param pageable ページング情報
     * @return チェックインレスポンスのページ
     */
    public Page<CheckinResponse> listCheckins(Long eventId, Pageable pageable) {
        return checkinRepository.findByEventIdOrderByCheckedInAtDesc(eventId, pageable)
                .map(eventMapper::toCheckinResponse);
    }

    /**
     * スタッフスキャンによるチェックインを実行する。
     *
     * <p>認可: URL に eventId を持たない QR スキャン API のため、QR トークンからチケットを解決した
     * 直後に得られる {@code ticket.getEventId()} を信頼できる帰属源として、当該イベントスコープの
     * ADMIN/DEPUTY_ADMIN（SYSTEM_ADMIN 含む）であることを検証する（スタッフ操作のなりすまし・
     * 無関係な認証ユーザーによる不正チェックイン記録を防止）。</p>
     *
     * @param staffUserId スタッフユーザーID
     * @param request     チェックインリクエスト
     * @return チェックインレスポンス
     */
    @Transactional
    public CheckinResponse staffCheckin(Long staffUserId, CheckinRequest request) {
        EventTicketEntity ticket = ticketService.findTicketByQrTokenOrThrow(request.getQrToken());
        eventScopeAccessGuard.requireAdminByEventId(staffUserId, ticket.getEventId());
        validateTicketForCheckin(ticket);

        ticket.use();
        ticketRepository.save(ticket);

        EventCheckinEntity checkin = EventCheckinEntity.builder()
                .eventId(ticket.getEventId())
                .ticketId(ticket.getId())
                .checkinType(CheckinType.STAFF_SCAN)
                .checkedInBy(staffUserId)
                .note(request.getNote())
                .build();

        EventCheckinEntity saved = checkinRepository.save(checkin);

        incrementEventCheckinCount(ticket.getEventId());

        // F03.12 ケア対象者見守り通知: 登録ユーザーへのチェックインフック
        resolveTicketUserId(ticket).ifPresent(userId ->
                careEventNotificationService.notifyCheckin(userId, ticket.getEventId()));

        log.info("スタッフチェックイン: ticketId={}, staffUserId={}", ticket.getId(), staffUserId);
        return eventMapper.toCheckinResponse(saved);
    }

    /**
     * セルフチェックインを実行する。
     *
     * <p>認可: 本人（チケットの参加登録に紐付く {@code userId}）のみ実行可能。
     * ゲスト参加（{@code userId=null}）や他ユーザーのチケットでの自己チェックインは 403 で拒否する
     * （本人の QR トークンを他人に横流しされた場合の悪用抑止・チェックイン記録の帰属正確性維持）。</p>
     *
     * @param currentUserId 操作者（セルフチェックイン実行者）のユーザーID
     * @param request       セルフチェックインリクエスト
     * @return チェックインレスポンス
     */
    @Transactional
    public CheckinResponse selfCheckin(Long currentUserId, SelfCheckinRequest request) {
        EventTicketEntity ticket = ticketService.findTicketByQrTokenOrThrow(request.getQrToken());
        requireTicketOwner(ticket, currentUserId);
        validateTicketForCheckin(ticket);

        ticket.use();
        ticketRepository.save(ticket);

        EventCheckinEntity checkin = EventCheckinEntity.builder()
                .eventId(ticket.getEventId())
                .ticketId(ticket.getId())
                .checkinType(CheckinType.SELF)
                .build();

        EventCheckinEntity saved = checkinRepository.save(checkin);

        incrementEventCheckinCount(ticket.getEventId());

        // F03.12 ケア対象者見守り通知: 登録ユーザーへのチェックインフック
        resolveTicketUserId(ticket).ifPresent(userId ->
                careEventNotificationService.notifyCheckin(userId, ticket.getEventId()));

        log.info("セルフチェックイン: ticketId={}", ticket.getId());
        return eventMapper.toCheckinResponse(saved);
    }

    /**
     * イベントのチェックイン数を取得する。
     *
     * @param eventId イベントID
     * @return チェックイン数
     */
    public long getCheckinCount(Long eventId) {
        return checkinRepository.countByEventId(eventId);
    }

    /**
     * セルフチェックインの操作者がチケットの本人（参加登録の {@code userId}）であることを検証する。
     * ゲスト参加（{@code userId=null}）・他ユーザーのチケットは 403 COMMON_002 で拒否する。
     */
    private void requireTicketOwner(EventTicketEntity ticket, Long currentUserId) {
        Long ownerUserId = resolveTicketUserId(ticket).orElse(null);
        if (currentUserId == null || ownerUserId == null || !ownerUserId.equals(currentUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * チケットのチェックイン可能性を検証する。
     */
    private void validateTicketForCheckin(EventTicketEntity ticket) {
        if (ticket.getStatus() != TicketStatus.VALID) {
            throw new BusinessException(EventErrorCode.TICKET_ALREADY_USED);
        }
        if (checkinRepository.existsByTicketId(ticket.getId())) {
            throw new BusinessException(EventErrorCode.TICKET_ALREADY_USED);
        }
    }

    /**
     * イベントのチェックイン数をインクリメントする。
     */
    private void incrementEventCheckinCount(Long eventId) {
        eventRepository.findById(eventId).ifPresent(event -> {
            event.incrementCheckinCount();
            eventRepository.save(event);
        });
    }

    /**
     * チケットの参加登録からユーザーIDを解決する。
     * ゲスト参加（userId=null）の場合は空のOptionalを返す。
     *
     * @param ticket チェックイン対象チケット
     * @return ユーザーIDのOptional。ゲスト参加の場合は空。
     */
    private java.util.Optional<Long> resolveTicketUserId(EventTicketEntity ticket) {
        return registrationRepository.findById(ticket.getRegistrationId())
                .map(reg -> reg.getUserId());
    }
}
