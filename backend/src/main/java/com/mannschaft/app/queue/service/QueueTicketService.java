package com.mannschaft.app.queue.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.queue.QueueErrorCode;
import com.mannschaft.app.queue.QueueMapper;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.TicketSource;
import com.mannschaft.app.queue.TicketStatus;
import com.mannschaft.app.queue.dto.AdminTicketRequest;
import com.mannschaft.app.queue.dto.CreateTicketRequest;
import com.mannschaft.app.queue.dto.TicketResponse;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.entity.QueueSettingsEntity;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import com.mannschaft.app.queue.repository.QueueSettingsRepository;
import com.mannschaft.app.queue.repository.QueueTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * 順番待ちチケットサービス。チケットの発行・状態遷移・一覧取得を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QueueTicketService {

    private static final String ACTION_CALL = "CALL";
    private static final String ACTION_START_SERVING = "START_SERVING";
    private static final String ACTION_COMPLETE = "COMPLETE";
    private static final String ACTION_CANCEL = "CANCEL";
    private static final String ACTION_NO_SHOW = "NO_SHOW";
    private static final String ACTION_HOLD = "HOLD";
    private static final String ACTION_RECALL = "RECALL";

    private final QueueTicketRepository ticketRepository;
    private final QueueSettingsRepository settingsRepository;
    private final QueueCounterService counterService;
    private final QueueMapper queueMapper;

    /**
     * チケットを発行する。
     *
     * @param counterId カウンターID
     * @param request   発行リクエスト
     * @param userId    ユーザーID（ゲストの場合null）
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 発行されたチケット
     */
    @Transactional
    public TicketResponse issueTicket(Long counterId, CreateTicketRequest request,
                                      Long userId, QueueScopeType scopeType, Long scopeId) {
        QueueCounterEntity counter = counterService.findEntityOrThrow(counterId);
        validateCounterAccepting(counter);

        // ゲスト受付チェック
        if (userId == null) {
            validateGuestAllowed(scopeType, scopeId);
        }

        // ユーザーのアクティブチケット上限チェック
        LocalDate today = LocalDate.now();
        if (userId != null) {
            validateActiveTicketLimit(userId, today, scopeType, scopeId);
        }

        // キュー上限チェック
        long waitingCount = ticketRepository.countByCounterIdAndIssuedDateAndStatus(
                counterId, today, TicketStatus.WAITING);
        if (waitingCount >= counter.getMaxQueueSize()) {
            throw new BusinessException(QueueErrorCode.QUEUE_FULL);
        }

        // ポジションとチケット番号を決定
        int nextPosition = ticketRepository.findMaxPositionByCounterIdAndIssuedDate(counterId, today) + 1;
        long ticketSequence = ticketRepository.countByCounterIdAndIssuedDate(counterId, today) + 1;
        String ticketNumber = generateTicketNumber(counter, ticketSequence);

        TicketSource source = request.getSource() != null
                ? TicketSource.valueOf(request.getSource()) : TicketSource.ONLINE;

        // 推定待ち時間の算出
        Short estimatedWait = calculateEstimatedWait(counter, (int) waitingCount);

        QueueTicketEntity entity = QueueTicketEntity.builder()
                .categoryId(counter.getCategoryId())
                .counterId(counterId)
                .ticketNumber(ticketNumber)
                .userId(userId)
                .guestName(request.getGuestName())
                .partySize(request.getPartySize() != null ? request.getPartySize() : (short) 1)
                .source(source)
                .status(TicketStatus.WAITING)
                .position(nextPosition)
                .estimatedWaitMinutes(estimatedWait)
                .note(request.getNote())
                .issuedDate(today)
                .build();

        QueueTicketEntity saved = ticketRepository.save(entity);
        log.info("チケット発行: id={}, number={}, counterId={}", saved.getId(), saved.getTicketNumber(), counterId);
        return queueMapper.toTicketResponse(saved);
    }

    /**
     * カウンターの待ちチケット一覧を取得する。
     *
     * @param counterId カウンターID
     * @return チケット一覧
     */
    public List<TicketResponse> listWaitingTickets(Long counterId) {
        counterService.findEntityOrThrow(counterId);
        List<QueueTicketEntity> tickets = ticketRepository
                .findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                        counterId, LocalDate.now(), TicketStatus.WAITING);
        return queueMapper.toTicketResponseList(tickets);
    }

    /**
     * カウンターの当日全チケット一覧を取得する。
     *
     * @param counterId カウンターID
     * @return チケット一覧
     */
    public List<TicketResponse> listAllTickets(Long counterId) {
        counterService.findEntityOrThrow(counterId);
        List<QueueTicketEntity> tickets = ticketRepository
                .findByCounterIdAndIssuedDateOrderByPositionAsc(counterId, LocalDate.now());
        return queueMapper.toTicketResponseList(tickets);
    }

    /**
     * チケット詳細を取得する。
     *
     * @param ticketId チケットID
     * @return チケット
     */
    public TicketResponse getTicket(Long ticketId) {
        QueueTicketEntity entity = findTicketOrThrow(ticketId);
        return queueMapper.toTicketResponse(entity);
    }

    /**
     * ユーザーの当日チケット一覧を取得する。
     *
     * @param userId ユーザーID
     * @return チケット一覧
     */
    public List<TicketResponse> listMyTickets(Long userId) {
        List<QueueTicketEntity> tickets = ticketRepository
                .findByUserIdAndIssuedDateOrderByCreatedAtDesc(userId, LocalDate.now());
        return queueMapper.toTicketResponseList(tickets);
    }

    /**
     * チケットをキャンセルする（ユーザー自身）。
     *
     * @param ticketId チケットID
     * @param userId   ユーザーID
     */
    @Transactional
    public void cancelMyTicket(Long ticketId, Long userId) {
        QueueTicketEntity entity = findTicketOrThrow(ticketId);
        if (entity.getStatus() != TicketStatus.WAITING && entity.getStatus() != TicketStatus.CALLED) {
            throw new BusinessException(QueueErrorCode.INVALID_TICKET_TRANSITION);
        }
        entity.cancel(userId);
        ticketRepository.save(entity);
        log.info("チケットキャンセル（ユーザー）: id={}, userId={}", ticketId, userId);
    }

    /**
     * 管理者によるチケット操作を実行する。
     *
     * @param ticketId チケットID
     * @param request  操作リクエスト
     * @param userId   操作者ID
     * @return 更新後のチケット
     */
    @Transactional
    public TicketResponse adminAction(Long ticketId, AdminTicketRequest request, Long userId) {
        QueueTicketEntity entity = findTicketOrThrow(ticketId);

        switch (request.getAction()) {
            case ACTION_CALL -> {
                validateTransition(entity, TicketStatus.WAITING);
                entity.call();
            }
            case ACTION_START_SERVING -> {
                validateTransition(entity, TicketStatus.CALLED);
                entity.startServing();
            }
            case ACTION_COMPLETE -> {
                validateTransition(entity, TicketStatus.SERVING);
                entity.complete(request.getActualServiceMinutes());
            }
            case ACTION_CANCEL -> {
                if (entity.getStatus() == TicketStatus.COMPLETED || entity.getStatus() == TicketStatus.CANCELLED) {
                    throw new BusinessException(QueueErrorCode.INVALID_TICKET_TRANSITION);
                }
                entity.cancel(userId);
            }
            case ACTION_NO_SHOW -> {
                validateTransition(entity, TicketStatus.CALLED);
                entity.markNoShow();
            }
            case ACTION_HOLD -> {
                validateTransition(entity, TicketStatus.CALLED);
                int extensionMinutes = request.getHoldExtensionMinutes() != null
                        ? request.getHoldExtensionMinutes() : 5;
                entity.hold(LocalDateTime.now().plusMinutes(extensionMinutes));
            }
            case ACTION_RECALL -> {
                if (entity.getStatus() != TicketStatus.NO_SHOW) {
                    throw new BusinessException(QueueErrorCode.INVALID_TICKET_TRANSITION);
                }
                entity.call();
            }
            default -> throw new BusinessException(QueueErrorCode.INVALID_TICKET_TRANSITION);
        }

        QueueTicketEntity saved = ticketRepository.save(entity);
        log.info("チケット操作: id={}, action={}, operator={}", ticketId, request.getAction(), userId);
        return queueMapper.toTicketResponse(saved);
    }

    /**
     * 次の待ちチケットを呼び出す。
     *
     * @param counterId カウンターID
     * @param userId    操作者ID
     * @return 呼び出されたチケット（待ちなしの場合null）
     */
    @Transactional
    public TicketResponse callNext(Long counterId, Long userId) {
        counterService.findEntityOrThrow(counterId);
        List<QueueTicketEntity> waiting = ticketRepository
                .findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                        counterId, LocalDate.now(), TicketStatus.WAITING);
        if (waiting.isEmpty()) {
            return null;
        }
        QueueTicketEntity next = waiting.get(0);
        next.call();
        QueueTicketEntity saved = ticketRepository.save(next);
        log.info("次のチケット呼び出し: id={}, number={}, counterId={}", saved.getId(), saved.getTicketNumber(), counterId);
        return queueMapper.toTicketResponse(saved);
    }

    /**
     * カテゴリ配下の待ちチケット一覧を取得する。
     *
     * @param categoryId カテゴリID
     * @return チケット一覧
     */
    public List<TicketResponse> listCategoryTickets(Long categoryId) {
        List<QueueTicketEntity> tickets = ticketRepository
                .findByCategoryIdAndIssuedDateAndStatusOrderByPositionAsc(
                        categoryId, LocalDate.now(), TicketStatus.WAITING);
        return queueMapper.toTicketResponseList(tickets);
    }

    private QueueTicketEntity findTicketOrThrow(Long ticketId) {
        return ticketRepository.findById(ticketId)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.TICKET_NOT_FOUND));
    }

    private void validateCounterAccepting(QueueCounterEntity counter) {
        if (!counter.getIsActive()) {
            throw new BusinessException(QueueErrorCode.COUNTER_INACTIVE);
        }
        if (!counter.getIsAccepting()) {
            throw new BusinessException(QueueErrorCode.COUNTER_NOT_ACCEPTING);
        }
        // 営業時間チェック
        if (counter.getOperatingTimeFrom() != null && counter.getOperatingTimeTo() != null) {
            LocalTime now = LocalTime.now();
            if (now.isBefore(counter.getOperatingTimeFrom()) || now.isAfter(counter.getOperatingTimeTo())) {
                throw new BusinessException(QueueErrorCode.OUTSIDE_OPERATING_HOURS);
            }
        }
    }

    private void validateGuestAllowed(QueueScopeType scopeType, Long scopeId) {
        settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .ifPresent(settings -> {
                    if (!settings.getAllowGuestQueue()) {
                        throw new BusinessException(QueueErrorCode.GUEST_NOT_ALLOWED);
                    }
                });
    }

    private void validateActiveTicketLimit(Long userId, LocalDate today,
                                           QueueScopeType scopeType, Long scopeId) {
        long activeCount = ticketRepository.countActiveTicketsByUserIdAndIssuedDate(userId, today);
        short maxActive = settingsRepository.findByScopeTypeAndScopeId(scopeType, scopeId)
                .map(QueueSettingsEntity::getMaxActiveTicketsPerUser)
                .orElse((short) 1);
        if (activeCount >= maxActive) {
            throw new BusinessException(QueueErrorCode.MAX_ACTIVE_TICKETS_EXCEEDED);
        }
    }

    private void validateTransition(QueueTicketEntity ticket, TicketStatus expectedCurrent) {
        if (ticket.getStatus() != expectedCurrent) {
            throw new BusinessException(QueueErrorCode.INVALID_TICKET_TRANSITION);
        }
    }

    private String generateTicketNumber(QueueCounterEntity counter, long sequence) {
        // カテゴリのプレフィックス文字を使用。未設定の場合はカウンターID先頭を使用
        String prefix = "Q";
        // カテゴリの prefixChar 取得は QueueCategoryService 連携時に実装予定
        return prefix + String.format("%03d", sequence);
    }

    private Short calculateEstimatedWait(QueueCounterEntity counter, int waitingCount) {
        return (short) (waitingCount * counter.getAvgServiceMinutes());
    }
}
