package com.mannschaft.app.queue.service;

import com.mannschaft.app.queue.QueueMapper;
import com.mannschaft.app.queue.QueueScopeType;
import com.mannschaft.app.queue.TicketStatus;
import com.mannschaft.app.queue.dto.DailyStatsResponse;
import com.mannschaft.app.queue.dto.QueueStatusResponse;
import com.mannschaft.app.queue.dto.TicketResponse;
import com.mannschaft.app.queue.entity.QueueCounterEntity;
import com.mannschaft.app.queue.entity.QueueTicketEntity;
import com.mannschaft.app.queue.repository.QueueCounterRepository;
import com.mannschaft.app.queue.repository.QueueDailyStatsRepository;
import com.mannschaft.app.queue.repository.QueueTicketRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 順番待ち統計サービス。リアルタイムステータスと日次統計を提供する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QueueStatsService {

    private final QueueTicketRepository ticketRepository;
    private final QueueCounterRepository counterRepository;
    private final QueueDailyStatsRepository dailyStatsRepository;
    private final QueueMapper queueMapper;

    /**
     * チーム全体のリアルタイムキューステータスを取得する。
     * 全カテゴリ配下の全カウンターの待ち状況を集約する。
     *
     * @param categoryIds カテゴリIDリスト
     * @return カウンターごとのキューステータス一覧
     */
    public List<QueueStatusResponse> getQueueStatus(List<Long> categoryIds) {
        List<QueueStatusResponse> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (Long categoryId : categoryIds) {
            List<QueueCounterEntity> counters =
                    counterRepository.findByCategoryIdOrderByDisplayOrderAsc(categoryId);

            for (QueueCounterEntity counter : counters) {
                List<QueueTicketEntity> waitingTickets = ticketRepository
                        .findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                                counter.getId(), today, TicketStatus.WAITING);

                List<TicketResponse> ticketResponses = queueMapper.toTicketResponseList(waitingTickets);

                // 現在対応中のチケット番号を取得
                String currentTicketNumber = null;
                List<QueueTicketEntity> servingTickets = ticketRepository
                        .findByCounterIdAndIssuedDateAndStatusOrderByPositionAsc(
                                counter.getId(), today, TicketStatus.SERVING);
                if (!servingTickets.isEmpty()) {
                    currentTicketNumber = servingTickets.get(0).getTicketNumber();
                }

                int estimatedWait = waitingTickets.size() * counter.getAvgServiceMinutes();

                QueueStatusResponse status = new QueueStatusResponse(
                        counter.getId(),
                        counter.getName(),
                        counter.getIsAccepting(),
                        waitingTickets.size(),
                        estimatedWait,
                        currentTicketNumber,
                        ticketResponses
                );
                result.add(status);
            }
        }
        return result;
    }

    /**
     * 日次統計を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param from      開始日
     * @param to        終了日
     * @return 日次統計一覧
     */
    public List<DailyStatsResponse> getDailyStats(QueueScopeType scopeType, Long scopeId,
                                                   LocalDate from, LocalDate to) {
        return queueMapper.toDailyStatsResponseList(
                dailyStatsRepository.findByScopeTypeAndScopeIdAndStatDateBetweenOrderByStatDateAsc(
                        scopeType, scopeId, from, to));
    }

    /**
     * カウンター別の日次統計を取得する。
     *
     * @param counterId カウンターID
     * @param from      開始日
     * @param to        終了日
     * @return 日次統計一覧
     */
    public List<DailyStatsResponse> getCounterDailyStats(Long counterId, LocalDate from, LocalDate to) {
        return queueMapper.toDailyStatsResponseList(
                dailyStatsRepository.findByCounterIdAndStatDateBetweenOrderByStatDateAsc(
                        counterId, from, to));
    }
}
