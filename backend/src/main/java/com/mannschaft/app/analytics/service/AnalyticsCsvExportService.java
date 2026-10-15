package com.mannschaft.app.analytics.service;

import com.mannschaft.app.analytics.AnalyticsErrorCode;
import com.mannschaft.app.analytics.DatePreset;
import com.mannschaft.app.analytics.entity.AnalyticsDailyAdsEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyModulesEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyRevenueEntity;
import com.mannschaft.app.analytics.entity.AnalyticsDailyUsersEntity;
import com.mannschaft.app.analytics.entity.AnalyticsFunnelSnapshotEntity;
import com.mannschaft.app.analytics.entity.AnalyticsMonthlyCohortEntity;
import com.mannschaft.app.analytics.repository.AnalyticsDailyAdsRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyModulesRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyRevenueRepository;
import com.mannschaft.app.analytics.repository.AnalyticsDailyUsersRepository;
import com.mannschaft.app.analytics.repository.AnalyticsFunnelSnapshotRepository;
import com.mannschaft.app.analytics.repository.AnalyticsMonthlyCohortRepository;
import com.mannschaft.app.analytics.service.DateRangeResolver.DateRange;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * CSVエクスポートを実行するサービス。
 *
 * <p>各種分析データをCSV形式の文字列として返す。
 * PII（ユーザーID、メール、氏名）は一切含めず、集計データのみを出力する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class AnalyticsCsvExportService {

    private static final String LINE_SEP = "\n";
    private static final String COL_SEP = ",";

    private final AnalyticsDailyRevenueRepository revenueRepository;
    private final AnalyticsDailyUsersRepository usersRepository;
    private final AnalyticsDailyModulesRepository modulesRepository;
    private final AnalyticsDailyAdsRepository adsRepository;
    private final AnalyticsFunnelSnapshotRepository funnelRepository;
    private final AnalyticsMonthlyCohortRepository cohortRepository;
    private final DateRangeResolver dateRangeResolver;

    /**
     * 指定タイプのデータをCSV形式の文字列で返す。
     *
     * @param type   エクスポートタイプ（REVENUE, USERS, MODULES, COHORTS, FUNNEL, ADS）
     * @param from   開始日
     * @param to     終了日
     * @param preset 日付プリセット（from/to より優先）
     * @return CSV文字列
     */
    public String exportCsv(String type, LocalDate from, LocalDate to, DatePreset preset) {
        DateRange range = dateRangeResolver.resolve(from, to, preset);
        dateRangeResolver.validateExportRange(range);

        log.debug("CSVエクスポート開始: type={}, from={}, to={}", type, range.getFrom(), range.getTo());

        return switch (type) {
            case "REVENUE" -> exportRevenue(range);
            case "USERS" -> exportUsers(range);
            case "MODULES" -> exportModules(range);
            case "COHORTS" -> exportCohorts(range);
            case "FUNNEL" -> exportFunnel(range);
            case "ADS" -> exportAds(range);
            default -> throw new BusinessException(AnalyticsErrorCode.ANALYTICS_004);
        };
    }

    /**
     * 収益データをCSVに変換する。
     */
    private String exportRevenue(DateRange range) {
        List<AnalyticsDailyRevenueEntity> records = revenueRepository
                .findByDateBetweenOrderByDateAsc(range.getFrom(), range.getTo());

        StringBuilder sb = new StringBuilder();
        sb.append("date").append(COL_SEP)
          .append("revenue_source").append(COL_SEP)
          .append("gross_revenue").append(COL_SEP)
          .append("refunds").append(COL_SEP)
          .append("net_revenue").append(COL_SEP)
          .append("transaction_count")
          .append(LINE_SEP);

        for (AnalyticsDailyRevenueEntity r : records) {
            sb.append(r.getDate()).append(COL_SEP)
              .append(r.getRevenueSource()).append(COL_SEP)
              .append(r.getGrossRevenue()).append(COL_SEP)
              .append(r.getRefundAmount()).append(COL_SEP)
              .append(r.getNetRevenue()).append(COL_SEP)
              .append(r.getTransactionCount())
              .append(LINE_SEP);
        }

        log.debug("収益CSVエクスポート完了: records={}", records.size());
        return sb.toString();
    }

    /**
     * ユーザーデータをCSVに変換する。
     */
    private String exportUsers(DateRange range) {
        List<AnalyticsDailyUsersEntity> records = usersRepository
                .findByDateBetweenOrderByDateAsc(range.getFrom(), range.getTo());

        StringBuilder sb = new StringBuilder();
        sb.append("date").append(COL_SEP)
          .append("active_users").append(COL_SEP)
          .append("new_users").append(COL_SEP)
          .append("churned_users").append(COL_SEP)
          .append("paying_users").append(COL_SEP)
          .append("reactivated_users").append(COL_SEP)
          .append("total_users")
          .append(LINE_SEP);

        for (AnalyticsDailyUsersEntity r : records) {
            sb.append(r.getDate()).append(COL_SEP)
              .append(r.getActiveUsers()).append(COL_SEP)
              .append(r.getNewUsers()).append(COL_SEP)
              .append(r.getChurnedUsers()).append(COL_SEP)
              .append(r.getPayingUsers()).append(COL_SEP)
              .append(r.getReactivatedUsers()).append(COL_SEP)
              .append(r.getTotalUsers())
              .append(LINE_SEP);
        }

        log.debug("ユーザーCSVエクスポート完了: records={}", records.size());
        return sb.toString();
    }

    /**
     * モジュールデータをCSVに変換する。
     */
    private String exportModules(DateRange range) {
        List<AnalyticsDailyModulesEntity> records = modulesRepository
                .findByDateBetweenOrderByDateAscModuleIdAsc(range.getFrom(), range.getTo());

        StringBuilder sb = new StringBuilder();
        sb.append("date").append(COL_SEP)
          .append("module_key").append(COL_SEP)
          .append("active_teams").append(COL_SEP)
          .append("revenue")
          .append(LINE_SEP);

        for (AnalyticsDailyModulesEntity r : records) {
            sb.append(r.getDate()).append(COL_SEP)
              .append(r.getModuleId()).append(COL_SEP)
              .append(r.getActiveTeams()).append(COL_SEP)
              .append(r.getRevenue())
              .append(LINE_SEP);
        }

        log.debug("モジュールCSVエクスポート完了: records={}", records.size());
        return sb.toString();
    }

    /**
     * コホートデータをCSVに変換する。
     */
    private String exportCohorts(DateRange range) {
        String fromCohort = YearMonth.from(range.getFrom()).toString();
        String toCohort = YearMonth.from(range.getTo()).toString();

        List<AnalyticsMonthlyCohortEntity> records = cohortRepository
                .findByCohortMonthBetweenOrderByCohortMonthAscMonthsElapsedAsc(
                        YearMonth.parse(fromCohort).atDay(1), YearMonth.parse(toCohort).atEndOfMonth());

        StringBuilder sb = new StringBuilder();
        sb.append("cohort_month").append(COL_SEP)
          .append("cohort_size").append(COL_SEP)
          .append("offset_months").append(COL_SEP)
          .append("retained_users").append(COL_SEP)
          .append("revenue")
          .append(LINE_SEP);

        for (AnalyticsMonthlyCohortEntity r : records) {
            sb.append(r.getCohortMonth()).append(COL_SEP)
              .append(r.getCohortSize()).append(COL_SEP)
              .append(r.getMonthsElapsed()).append(COL_SEP)
              .append(r.getRetainedUsers()).append(COL_SEP)
              .append(r.getRevenue())
              .append(LINE_SEP);
        }

        log.debug("コホートCSVエクスポート完了: records={}", records.size());
        return sb.toString();
    }

    /**
     * ファネルデータをCSVに変換する。
     */
    private String exportFunnel(DateRange range) {
        // ファネルは日単位なので、範囲内の全日分を出力
        StringBuilder sb = new StringBuilder();
        sb.append("date").append(COL_SEP)
          .append("stage").append(COL_SEP)
          .append("user_count")
          .append(LINE_SEP);

        LocalDate current = range.getFrom();
        while (!current.isAfter(range.getTo())) {
            List<AnalyticsFunnelSnapshotEntity> stages = funnelRepository
                    .findByDateOrderByStageAsc(current);
            for (AnalyticsFunnelSnapshotEntity stage : stages) {
                sb.append(current).append(COL_SEP)
                  .append(escapeCsv(stage.getStage().name())).append(COL_SEP)
                  .append(stage.getUserCount())
                  .append(LINE_SEP);
            }
            current = current.plusDays(1);
        }

        log.debug("ファネルCSVエクスポート完了");
        return sb.toString();
    }

    /**
     * 広告データをCSVに変換する。
     */
    private String exportAds(DateRange range) {
        List<AnalyticsDailyAdsEntity> records = adsRepository
                .findByDateBetweenOrderByDateAsc(range.getFrom(), range.getTo());

        StringBuilder sb = new StringBuilder();
        sb.append("date").append(COL_SEP)
          .append("impressions").append(COL_SEP)
          .append("clicks").append(COL_SEP)
          .append("revenue").append(COL_SEP)
          .append("fill_rate")
          .append(LINE_SEP);

        for (AnalyticsDailyAdsEntity r : records) {
            sb.append(r.getDate()).append(COL_SEP)
              .append(r.getImpressions()).append(COL_SEP)
              .append(r.getClicks()).append(COL_SEP)
              .append(r.getAdRevenue()).append(COL_SEP)
              .append(r.getEcpm())
              .append(LINE_SEP);
        }

        log.debug("広告CSVエクスポート完了: records={}", records.size());
        return sb.toString();
    }

    /**
     * CSV値のエスケープ処理を行う。カンマ・改行・ダブルクォートを含む値をクォートする。
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
