package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventMapper;
import com.mannschaft.app.event.dto.CreateTicketTypeRequest;
import com.mannschaft.app.event.dto.TicketTypeResponse;
import com.mannschaft.app.event.dto.UpdateTicketTypeRequest;
import com.mannschaft.app.event.entity.EventTicketTypeEntity;
import com.mannschaft.app.event.repository.EventTicketTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * イベントチケット種別サービス。チケット種別のCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventTicketTypeService {

    private static final int MAX_TICKET_TYPES = 20;

    private final EventTicketTypeRepository ticketTypeRepository;
    private final EventMapper eventMapper;

    /**
     * イベントのチケット種別一覧を取得する。
     *
     * @param eventId イベントID
     * @return チケット種別レスポンスリスト
     */
    public List<TicketTypeResponse> listTicketTypes(Long eventId) {
        List<EventTicketTypeEntity> types = ticketTypeRepository.findByEventIdOrderBySortOrder(eventId);
        return eventMapper.toTicketTypeResponseList(types);
    }

    /**
     * チケット種別を作成する。
     *
     * @param eventId イベントID
     * @param request 作成リクエスト
     * @return 作成されたチケット種別レスポンス
     */
    @Transactional
    public TicketTypeResponse createTicketType(Long eventId, CreateTicketTypeRequest request) {
        long count = ticketTypeRepository.countByEventId(eventId);
        if (count >= MAX_TICKET_TYPES) {
            throw new BusinessException(EventErrorCode.MAX_TICKET_TYPES);
        }

        EventTicketTypeEntity entity = EventTicketTypeEntity.builder()
                .eventId(eventId)
                .name(request.getName())
                .description(request.getDescription())
                .price(request.getPrice() != null ? request.getPrice() : BigDecimal.ZERO)
                .currency(request.getCurrency() != null ? request.getCurrency() : "JPY")
                .maxQuantity(request.getMaxQuantity())
                .minRegistrationRole(request.getMinRegistrationRole() != null
                        ? request.getMinRegistrationRole() : "MEMBER_PLUS")
                .sortOrder(request.getSortOrder() != null ? request.getSortOrder() : (int) count)
                .build();

        EventTicketTypeEntity saved = ticketTypeRepository.save(entity);
        log.info("チケット種別作成: eventId={}, ticketTypeId={}", eventId, saved.getId());
        return eventMapper.toTicketTypeResponse(saved);
    }

    /**
     * チケット種別を更新する。
     *
     * <p>親子BOLA根治: {@code ticketTypeId} が {@code eventId} に属さない場合は 404 で秘匿する。</p>
     *
     * @param eventId      イベントID（URL パス由来。親子突合に使用）
     * @param ticketTypeId チケット種別ID
     * @param request      更新リクエスト
     * @return 更新されたチケット種別レスポンス
     */
    @Transactional
    public TicketTypeResponse updateTicketType(Long eventId, Long ticketTypeId, UpdateTicketTypeRequest request) {
        EventTicketTypeEntity entity = findTicketTypeOrThrow(eventId, ticketTypeId);

        // managed entity を直接ミューテートして save → UPDATE が保証される（id=null INSERT 化バグ根治）
        entity.applyUpdate(request);
        EventTicketTypeEntity saved = ticketTypeRepository.save(entity);
        log.info("チケット種別更新: ticketTypeId={}", ticketTypeId);
        return eventMapper.toTicketTypeResponse(saved);
    }

    /**
     * チケット種別詳細を取得する。
     *
     * <p>親子BOLA根治: {@code ticketTypeId} が {@code eventId} に属さない場合は 404 で秘匿する。</p>
     *
     * @param eventId      イベントID（URL パス由来。親子突合に使用）
     * @param ticketTypeId チケット種別ID
     * @return チケット種別レスポンス
     */
    public TicketTypeResponse getTicketType(Long eventId, Long ticketTypeId) {
        EventTicketTypeEntity entity = findTicketTypeOrThrow(eventId, ticketTypeId);
        return eventMapper.toTicketTypeResponse(entity);
    }

    /**
     * チケット種別IDとイベントIDでチケット種別を取得する。存在しない・イベント帰属不一致の場合は
     * 存在を漏らさないため同一の TICKET_TYPE_NOT_FOUND（404）で例外をスローする（親子BOLA根治）。
     */
    private EventTicketTypeEntity findTicketTypeOrThrow(Long eventId, Long ticketTypeId) {
        return ticketTypeRepository.findByIdAndEventId(ticketTypeId, eventId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.TICKET_TYPE_NOT_FOUND));
    }
}
