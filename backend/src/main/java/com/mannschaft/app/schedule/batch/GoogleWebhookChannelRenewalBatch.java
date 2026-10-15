package com.mannschaft.app.schedule.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.schedule.entity.GoogleCalendarWebhookChannelEntity;
import com.mannschaft.app.schedule.repository.GoogleCalendarWebhookChannelRepository;
import com.mannschaft.app.schedule.service.GoogleCalendarWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Google Calendar Phase 4 — Webhook チャンネル日次更新バッチ。
 *
 * <p>毎日 02:00 JST に実行し、有効期限が 3 日以内に迫ったチャンネルを全件再登録する。
 * ユーザーがダッシュボードを開かない場合でもチャンネルが期限切れにならないフォールバック機構。</p>
 *
 * <p>対象 AC: AC-9 — 日次バッチが期限切れ前（3 日以内）のチャンネルを全件更新する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleWebhookChannelRenewalBatch {

    private final GoogleCalendarWebhookChannelRepository webhookChannelRepository;
    private final GoogleCalendarWebhookService webhookService;

    /**
     * 毎日 02:00 JST に実行される日次バッチ。
     * {@code expires_at <= NOW() + 3日} のチャンネルを全件再登録する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると Google カレンダーの webhook チャネルが失効し、失効後は再開しても自動復旧せず同期が恒久的に切れる")
    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Tokyo")
    // 起動間隔は日次 02:00。1 チャンネルにつき Google API を 2 回（旧 stop・新 watch）
    // 呼ぶため、最悪ケースを 1 件 2 秒 × 数千ユーザーと見積もり 2 時間を上限とする。
    @SchedulerLock(name = "googleWebhookChannelRenewalDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT2H")
    @BatchEndpoint(name = "google-webhook-channel-renewal-daily",
            description = "有効期限が3日以内に迫ったGoogleカレンダーWebhookチャンネルを毎日02:00に全件再登録する")
    public void renewExpiringChannels() {
        log.info("Webhook チャンネル日次更新バッチ開始");

        LocalDateTime threshold = LocalDateTime.now().plusDays(GoogleCalendarWebhookService.CHANNEL_RENEWAL_THRESHOLD_DAYS);
        List<GoogleCalendarWebhookChannelEntity> expiringChannels =
                webhookChannelRepository.findByExpiresAtLessThanEqual(threshold);

        if (expiringChannels.isEmpty()) {
            log.info("Webhook チャンネル日次更新バッチ: 更新対象なし");
            return;
        }

        log.info("Webhook チャンネル日次更新バッチ: 更新対象 {} 件", expiringChannels.size());
        int successCount = 0;
        int failCount = 0;

        for (GoogleCalendarWebhookChannelEntity channel : expiringChannels) {
            try {
                webhookService.renewChannel(channel);
                successCount++;
                log.debug("チャンネル更新完了: userId={}", channel.getUserId());
            } catch (Exception e) {
                failCount++;
                log.error("チャンネル更新失敗: userId={}", channel.getUserId(), e);
            }
        }

        log.info("Webhook チャンネル日次更新バッチ完了: 成功={}, 失敗={}", successCount, failCount);
    }
}
