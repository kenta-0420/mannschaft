package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminMapper;
import com.mannschaft.app.admin.NotificationChannel;
import com.mannschaft.app.admin.dto.NotificationStatsResponse;
import com.mannschaft.app.admin.repository.NotificationDeliveryStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 通知配信統計サービス。日別・チャネル別の配信状況を取得する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationStatsService {

    private final NotificationDeliveryStatsRepository statsRepository;
    private final AdminMapper adminMapper;

    /**
     * 日付範囲の通知配信統計を取得する。
     *
     * @param from 開始日
     * @param to   終了日
     * @return 統計一覧
     */
    public List<NotificationStatsResponse> getStats(LocalDate from, LocalDate to) {
        return adminMapper.toNotificationStatsResponseList(
                statsRepository.findByDateBetweenOrderByDateDescChannelAsc(from, to));
    }

    /**
     * チャネル別に通知配信統計を取得する。
     *
     * @param channel チャネル
     * @param from    開始日
     * @param to      終了日
     * @return 統計一覧
     */
    public List<NotificationStatsResponse> getStatsByChannel(String channel, LocalDate from, LocalDate to) {
        NotificationChannel ch = NotificationChannel.valueOf(channel);
        return adminMapper.toNotificationStatsResponseList(
                statsRepository.findByChannelAndDateBetweenOrderByDateDesc(ch, from, to));
    }
}
