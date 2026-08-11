package com.mannschaft.app.advertising.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.advertising.ReportFrequency;
import com.mannschaft.app.advertising.entity.AdDailyStatsEntity;
import com.mannschaft.app.advertising.entity.AdReportScheduleEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.repository.AdDailyStatsRepository;
import com.mannschaft.app.advertising.repository.AdReportScheduleRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportDeliveryBatchService {

    private final AdReportScheduleRepository adReportScheduleRepository;
    private final AdCampaignRepository adCampaignRepository;
    private final AdDailyStatsRepository adDailyStatsRepository;
    private final ObjectMapper objectMapper;
    private final EmailOutboxService emailOutboxService;

    /**
     * 週次レポート配信バッチ。毎週月曜 AM 9:00 (JST)。
     */
    @BatchEndpoint(name = "advertising-report-delivery-weekly", description = "広告主向け週次レポートを毎週月曜 09:00 に配信する")
    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Tokyo")
    @SchedulerLock(name = "reportDeliveryWeekly", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void deliverWeeklyReports() {
        LocalDate today = LocalDate.now();
        LocalDate from = today.with(TemporalAdjusters.previous(DayOfWeek.MONDAY)).minusWeeks(1);
        LocalDate to = from.plusDays(6);
        deliverReports(ReportFrequency.WEEKLY, from, to);
    }

    /**
     * 月次レポート配信バッチ。毎月1日 AM 9:00 (JST)。
     */
    @BatchEndpoint(name = "advertising-report-delivery-monthly", description = "広告主向け月次レポートを毎月 1 日 09:00 に配信する")
    @Scheduled(cron = "0 0 9 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "reportDeliveryMonthly", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void deliverMonthlyReports() {
        YearMonth lastMonth = YearMonth.now().minusMonths(1);
        LocalDate from = lastMonth.atDay(1);
        LocalDate to = lastMonth.atEndOfMonth();
        deliverReports(ReportFrequency.MONTHLY, from, to);
    }

    private void deliverReports(ReportFrequency frequency, LocalDate from, LocalDate to) {
        log.info("レポート配信バッチ開始: frequency={}, period={} ~ {}", frequency, from, to);

        List<AdReportScheduleEntity> schedules =
                adReportScheduleRepository.findByEnabledTrueAndFrequency(frequency);

        int successCount = 0;
        int errorCount = 0;

        for (AdReportScheduleEntity schedule : schedules) {
            try {
                deliverSingleReport(schedule, from, to);
                schedule.updateLastSentAt();
                successCount++;
            } catch (Exception e) {
                errorCount++;
                log.error("レポート配信エラー: scheduleId={}, error={}", schedule.getId(), e.getMessage(), e);
            }
        }

        log.info("レポート配信バッチ完了: frequency={}, 成功={}, エラー={}", frequency, successCount, errorCount);
    }

    private void deliverSingleReport(AdReportScheduleEntity schedule, LocalDate from, LocalDate to) {
        // 配信先メール取得
        List<String> recipients = parseJsonList(schedule.getRecipients());
        if (recipients.isEmpty()) {
            log.warn("配信先なし: scheduleId={}", schedule.getId());
            return;
        }

        // 対象キャンペーン取得
        List<Long> campaignIds;
        if (schedule.getIncludeCampaigns() != null) {
            campaignIds = parseJsonLongList(schedule.getIncludeCampaigns());
        } else {
            // 全キャンペーン = このスケジュールが属する広告主アカウントの全キャンペーン。
            // プラットフォーム全体を無絞り込みで取得すると、広告主数に比例して肥大化する
            // 上に他広告主のキャンペーンまで集計対象へ混入してしまうため、
            // advertiserAccountId で絞り込む。
            campaignIds = adCampaignRepository.findByAdvertiserAccountId(schedule.getAdvertiserAccountId())
                    .stream()
                    .map(c -> c.getId())
                    .toList();
        }

        if (campaignIds.isEmpty()) {
            log.info("対象キャンペーンなし: scheduleId={}", schedule.getId());
            return;
        }

        // パフォーマンス集計
        List<AdDailyStatsEntity> stats = adDailyStatsRepository.findByCampaignIdsAndDateBetween(campaignIds, from, to);

        long totalImpressions = stats.stream().mapToLong(AdDailyStatsEntity::getImpressions).sum();
        long totalClicks = stats.stream().mapToLong(AdDailyStatsEntity::getClicks).sum();
        BigDecimal totalCost = stats.stream().map(AdDailyStatsEntity::getCost).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal avgCtr = totalImpressions > 0
                ? BigDecimal.valueOf(totalClicks).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalImpressions), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // HTMLメール生成
        String html = buildReportHtml(schedule.getFrequency(), from, to,
                totalImpressions, totalClicks, avgCtr, totalCost);

        // メール送信（EmailOutboxService 経由で非同期・リトライ対応）
        String subject = String.format("[Mannschaft] %s Advertising Report (%s ~ %s)",
                schedule.getFrequency().name(), from, to);

        for (String recipient : recipients) {
            emailOutboxService.enqueue(new EmailOutboxRequest(
                    "ADVERTISING_REPORT",
                    "ja",
                    recipient,
                    Map.of("subject", subject, "body", html),
                    "advertising",
                    "report-" + schedule.getId() + "-" + schedule.getFrequency().name() + "-" + from,
                    null,
                    null,
                    null
            ));
        }

        log.info("レポート配信完了: scheduleId={}, recipients={}, impressions={}, clicks={}, cost={}",
                schedule.getId(), recipients.size(), totalImpressions, totalClicks, totalCost);
    }

    private String buildReportHtml(ReportFrequency frequency, LocalDate from, LocalDate to,
                                    long impressions, long clicks, BigDecimal ctr, BigDecimal cost) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8"></head>
                <body style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
                    <h2 style="color: #333;">Mannschaft Advertising %s Report</h2>
                    <p>Period: %s ~ %s</p>
                    <table style="width: 100%%; border-collapse: collapse; margin: 20px 0;">
                        <tr style="background-color: #f5f5f5;">
                            <th style="padding: 10px; text-align: left; border-bottom: 2px solid #ddd;">Metric</th>
                            <th style="padding: 10px; text-align: right; border-bottom: 2px solid #ddd;">Value</th>
                        </tr>
                        <tr>
                            <td style="padding: 8px; border-bottom: 1px solid #eee;">Impressions</td>
                            <td style="padding: 8px; text-align: right; border-bottom: 1px solid #eee;">%,d</td>
                        </tr>
                        <tr>
                            <td style="padding: 8px; border-bottom: 1px solid #eee;">Clicks</td>
                            <td style="padding: 8px; text-align: right; border-bottom: 1px solid #eee;">%,d</td>
                        </tr>
                        <tr>
                            <td style="padding: 8px; border-bottom: 1px solid #eee;">CTR</td>
                            <td style="padding: 8px; text-align: right; border-bottom: 1px solid #eee;">%s%%</td>
                        </tr>
                        <tr>
                            <td style="padding: 8px; border-bottom: 1px solid #eee;">Total Cost</td>
                            <td style="padding: 8px; text-align: right; border-bottom: 1px solid #eee;">&yen;%s</td>
                        </tr>
                    </table>
                    <p style="color: #666; font-size: 12px;">This is an automated report from Mannschaft Advertising Platform.</p>
                </body>
                </html>
                """.formatted(frequency.name(), from, to, impressions, clicks, ctr, cost.setScale(0, RoundingMode.FLOOR));
    }

    @SuppressWarnings("unchecked")
    private List<String> parseJsonList(String json) {
        try {
            return objectMapper.readValue(json, List.class);
        } catch (JsonProcessingException e) {
            log.error("JSON パースエラー: {}", json, e);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Long> parseJsonLongList(String json) {
        try {
            List<Object> raw = objectMapper.readValue(json, List.class);
            return raw.stream().map(o -> ((Number) o).longValue()).toList();
        } catch (JsonProcessingException e) {
            log.error("JSON パースエラー: {}", json, e);
            return List.of();
        }
    }
}
