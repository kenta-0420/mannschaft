package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
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
import com.mannschaft.app.event.repository.EventRepository;
import com.mannschaft.app.event.repository.EventTicketRepository;
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
    private final EventTicketService ticketService;
    private final EventMapper eventMapper;

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
     * @param staffUserId スタッフユーザーID
     * @param request     チェックインリクエスト
     * @return チェックインレスポンス
     */
    @Transactional
    public CheckinResponse staffCheckin(Long staffUserId, CheckinRequest request) {
        EventTicketEntity ticket = ticketService.findTicketByQrTokenOrThrow(request.getQrToken());
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

        log.info("スタッフチェックイン: ticketId={}, staffUserId={}", ticket.getId(), staffUserId);
        return eventMapper.toCheckinResponse(saved);
    }

    /**
     * セルフチェックインを実行する。
     *
     * @param request セルフチェックインリクエスト
     * @return チェックインレスポンス
     */
    @Transactional
    public CheckinResponse selfCheckin(SelfCheckinRequest request) {
        EventTicketEntity ticket = ticketService.findTicketByQrTokenOrThrow(request.getQrToken());
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
}
