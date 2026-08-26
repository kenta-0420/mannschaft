package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventMapper;
import com.mannschaft.app.event.TicketStatus;
import com.mannschaft.app.event.dto.TicketResponse;
import com.mannschaft.app.event.entity.EventRegistrationEntity;
import com.mannschaft.app.event.entity.EventTicketEntity;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.repository.EventTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * イベントチケットサービス。チケットの発行・照会・キャンセルを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventTicketService {

    private final EventTicketRepository ticketRepository;
    private final EventMapper eventMapper;

    /**
     * イベントのチケット一覧をページング取得する。
     *
     * @param eventId  イベントID
     * @param pageable ページング情報
     * @return チケットレスポンスのページ
     */
    public Page<TicketResponse> listTickets(Long eventId, Pageable pageable) {
        return ticketRepository.findByEventIdOrderByCreatedAtDesc(eventId, pageable)
                .map(eventMapper::toTicketResponse);
    }

    /**
     * チケット詳細を取得する。
     *
     * <p>親子BOLA根治: {@code ticketId} が {@code eventId} に属さない場合は
     * 存在を漏らさないため TICKET_NOT_FOUND（404）で秘匿する。</p>
     *
     * @param eventId  イベントID（URL パス由来。親子突合に使用）
     * @param ticketId チケットID
     * @return チケットレスポンス
     */
    public TicketResponse getTicket(Long eventId, Long ticketId) {
        EventTicketEntity entity = findTicketOrThrow(eventId, ticketId);
        return eventMapper.toTicketResponse(entity);
    }

    /**
     * QRトークンでチケットを検索する。
     *
     * <p>親子BOLA根治: QR トークンで引けたチケットの {@code eventId} が URL パスの
     * {@code eventId} と一致しない場合は TICKET_NOT_FOUND（404）で秘匿する。</p>
     *
     * @param eventId イベントID（URL パス由来。親子突合に使用）
     * @param qrToken QRトークン
     * @return チケットレスポンス
     */
    public TicketResponse getTicketByQrToken(Long eventId, String qrToken) {
        EventTicketEntity entity = ticketRepository.findByQrToken(qrToken)
                .filter(t -> t.getEventId().equals(eventId))
                .orElseThrow(() -> new BusinessException(EventErrorCode.TICKET_NOT_FOUND));
        return eventMapper.toTicketResponse(entity);
    }

    /**
     * チケットをキャンセルする。
     *
     * <p>親子BOLA根治: {@code ticketId} が {@code eventId} に属さない場合は 404 で秘匿する。</p>
     *
     * @param eventId  イベントID（URL パス由来。親子突合に使用）
     * @param ticketId チケットID
     * @return 更新されたチケットレスポンス
     */
    @Transactional
    public TicketResponse cancelTicket(Long eventId, Long ticketId) {
        EventTicketEntity entity = findTicketOrThrow(eventId, ticketId);
        if (entity.getStatus() != TicketStatus.VALID) {
            throw new BusinessException(EventErrorCode.INVALID_TICKET_STATUS);
        }
        entity.cancel();
        EventTicketEntity saved = ticketRepository.save(entity);
        log.info("チケットキャンセル: ticketId={}", ticketId);
        return eventMapper.toTicketResponse(saved);
    }

    /**
     * 参加登録に基づきチケットを発行する（内部用）。
     *
     * @param registration 参加登録エンティティ
     * @param ticketType   チケット種別エンティティ
     * @param quantity     発行数
     */
    @Transactional
    public void issueTickets(EventRegistrationEntity registration, EventTicketTypeEntity ticketType, int quantity) {
        for (int i = 0; i < quantity; i++) {
            EventTicketEntity ticket = EventTicketEntity.builder()
                    .registrationId(registration.getId())
                    .eventId(registration.getEventId())
                    .ticketTypeId(ticketType.getId())
                    .qrToken(UUID.randomUUID().toString())
                    .ticketNumber(generateTicketNumber(registration.getEventId()))
                    .build();
            ticketRepository.save(ticket);
        }
        log.info("チケット発行: registrationId={}, quantity={}", registration.getId(), quantity);
    }

    /**
     * 参加登録に紐付くチケットを全てキャンセルする（内部用）。
     *
     * @param registrationId 参加登録ID
     */
    @Transactional
    public void cancelTicketsByRegistration(Long registrationId) {
        List<EventTicketEntity> tickets = ticketRepository.findByRegistrationId(registrationId);
        for (EventTicketEntity ticket : tickets) {
            if (ticket.getStatus() == TicketStatus.VALID) {
                ticket.cancel();
                ticketRepository.save(ticket);
            }
        }
        log.info("参加登録のチケット一括キャンセル: registrationId={}", registrationId);
    }

    /**
     * チケットエンティティを取得する（内部用）。
     *
     * @param qrToken QRトークン
     * @return チケットエンティティ
     */
    public EventTicketEntity findTicketByQrTokenOrThrow(String qrToken) {
        return ticketRepository.findByQrToken(qrToken)
                .orElseThrow(() -> new BusinessException(EventErrorCode.TICKET_NOT_FOUND));
    }

    /**
     * チケットIDとイベントIDでチケットを取得する。存在しない・イベント帰属不一致の場合は
     * 存在を漏らさないため同一の TICKET_NOT_FOUND（404）で例外をスローする（親子BOLA根治）。
     */
    private EventTicketEntity findTicketOrThrow(Long eventId, Long ticketId) {
        return ticketRepository.findByIdAndEventId(ticketId, eventId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.TICKET_NOT_FOUND));
    }

    /**
     * チケット番号を生成する。
     */
    private String generateTicketNumber(Long eventId) {
        long count = ticketRepository.countByEventIdAndStatus(eventId, TicketStatus.VALID)
                + ticketRepository.countByEventIdAndStatus(eventId, TicketStatus.USED)
                + ticketRepository.countByEventIdAndStatus(eventId, TicketStatus.CANCELLED)
                + ticketRepository.countByEventIdAndStatus(eventId, TicketStatus.EXPIRED);
        return String.format("EVT%d-%04d", eventId, count + 1);
    }
}
