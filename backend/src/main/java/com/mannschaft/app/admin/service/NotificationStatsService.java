package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.AdminMapper;
import com.mannschaft.app.admin.NotificationChannel;
import com.mannschaft.app.admin.dto.NotificationStatsResponse;
import com.mannschaft.app.admin.repository.NotificationDeliveryStatsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 通知配信統計サービス。日別・チャネル別の配信状況を取得する。
 *
 * <p><b>{@code notificationStats} キャッシュ（既定 TTL 30分・{@link com.mannschaft.app.config.RedisConfig}）に
 * {@code @CacheEvict} が存在しない理由（issue #2544）:</b> {@code notification_delivery_stats} テーブルへの
 * アプリケーション側の書き込み経路（{@code save}/{@code upsert} 等）が本リポジトリに 1 つも存在しない
 * （{@link NotificationDeliveryStatsRepository} の呼び出しは本サービスの参照メソッドのみ）。
 * 集計値を能動的に書き換える処理が無い以上、能動的に破棄すべき「古いキャッシュ」も発生しない。
 * 将来、配信結果を日次集計するバッチ処理を追加する場合は、その書き込み完了時点で本キャッシュ
 * （{@code notificationStats}）を {@code allEntries = true} で全エビクトすること（管理画面向け参照
 * のみのため、キー単位の粒度は不要）。それまでは既定 30分 TTL の自然失効に委ねる。</p>
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
    @Cacheable(value = "notificationStats", key = "#from + ':' + #to")
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
    @Cacheable(value = "notificationStats", key = "#channel + ':' + #from + ':' + #to")
    public List<NotificationStatsResponse> getStatsByChannel(String channel, LocalDate from, LocalDate to) {
        NotificationChannel ch = NotificationChannel.valueOf(channel);
        return adminMapper.toNotificationStatsResponseList(
                statsRepository.findByChannelAndDateBetweenOrderByDateDesc(ch, from, to));
    }
}
