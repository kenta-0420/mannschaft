package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.gdpr.dto.PurgeStatusRow;
import com.mannschaft.app.gdpr.dto.PurgeStatusSummaryData;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GDPR パージ状況照会サービス（Phase E 読み取り専用）。
 *
 * <p>システム管理者が {@code account_purge_completion_status} テーブルの内容を確認するための
 * 読み取り専用クエリを提供する。再実行機能は Phase F スコープ。</p>
 *
 * <h2>アラート判定基準</h2>
 * <p>PENDING かつ {@code attemptedAt} が現在時刻から {@value #ALERT_THRESHOLD_MINUTES} 分以上前の
 * レコードをアラート対象とする。GDPR Art.17「30日以内削除完了」の早期警戒指標として使用する。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase E</p>
 */
@Service
@RequiredArgsConstructor
public class GdprPurgeStatusQueryService {

    /** アラート判定の閾値（分）: PENDING かつこの分数を超過したレコードをアラート対象とする。 */
    private static final long ALERT_THRESHOLD_MINUTES = 30L;

    /** CSV エクスポート用の日時フォーマット。 */
    private static final DateTimeFormatter CSV_DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AccountPurgeCompletionStatusRepository repo;

    /**
     * 一覧取得（ページネーション + 動的フィルタ）。
     *
     * <p>全パラメータが null の場合は全件対象となる。</p>
     *
     * @param status   ステータスフィルタ（PENDING / SUCCESS）。null で全件
     * @param domain   ドメイン名フィルタ。null で全件
     * @param dateFrom attemptedAt の開始日時フィルタ。null で制限なし
     * @param dateTo   attemptedAt の終了日時フィルタ。null で制限なし
     * @param pageable ページネーション設定
     * @return ページネーション済みの PurgeStatusRow
     */
    @Transactional(readOnly = true)
    public Page<PurgeStatusRow> list(
            String status,
            String domain,
            LocalDateTime dateFrom,
            LocalDateTime dateTo,
            Pageable pageable) {
        Specification<AccountPurgeCompletionStatusEntity> spec = buildSpec(status, domain, dateFrom, dateTo);
        return repo.findAll(spec, pageable).map(this::toRow);
    }

    /**
     * サマリー取得（ドメイン別 × ステータス別集計 + アラート件数）。
     *
     * @return GDPR パージ状況サマリー
     */
    @Transactional(readOnly = true)
    public PurgeStatusSummaryData summary() {
        // ドメイン × ステータス別集計
        List<Object[]> rawCounts = repo.countByDomainAndStatus();

        // ドメイン別 Map: key=domainName, value=[pendingCount, successCount]
        Map<String, long[]> domainMap = new HashMap<>();
        long totalPending = 0L;
        long totalSuccess = 0L;

        for (Object[] row : rawCounts) {
            String domainName = (String) row[0];
            String statusVal = (String) row[1];
            long count = ((Number) row[2]).longValue();

            domainMap.computeIfAbsent(domainName, k -> new long[]{0L, 0L});
            if ("PENDING".equals(statusVal)) {
                domainMap.get(domainName)[0] += count;
                totalPending += count;
            } else if ("SUCCESS".equals(statusVal)) {
                domainMap.get(domainName)[1] += count;
                totalSuccess += count;
            }
        }

        // ドメイン別集計リストを組み立てる（ドメイン名昇順）
        List<PurgeStatusSummaryData.DomainCount> byDomain = domainMap.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new PurgeStatusSummaryData.DomainCount(
                        e.getKey(),
                        e.getValue()[0],
                        e.getValue()[1]))
                .toList();

        // アラート件数: PENDING かつ閾値超過
        long alertCount = repo.countAlerting(LocalDateTime.now().minusMinutes(ALERT_THRESHOLD_MINUTES));

        return new PurgeStatusSummaryData(totalPending, totalSuccess, alertCount, byDomain);
    }

    /**
     * ユーザー詳細取得（userId に紐づく全ドメイン行）。
     *
     * @param userId 対象ユーザー ID
     * @return 対象ユーザーの全 per-domain レコード（ドメイン名昇順）
     */
    @Transactional(readOnly = true)
    public List<PurgeStatusRow> detail(Long userId) {
        return repo.findByUserIdOrderByDomainName(userId)
                .stream()
                .map(this::toRow)
                .toList();
    }

    /**
     * CSV エクスポート（全件を StreamingResponseBody で出力）。
     *
     * <p>CSV 列: userId, emailHash, domainName, status, attemptedAt, completedAt, isAlert</p>
     *
     * @param out 書き込み先 OutputStream
     * @throws IOException I/O エラー時
     */
    @Transactional(readOnly = true)
    public void writeCsv(OutputStream out) throws IOException {
        // UTF-8 BOM を付与して Excel 等の文字化けを防ぐ
        out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});

        try (PrintWriter writer = new PrintWriter(out, false, StandardCharsets.UTF_8)) {
            // ヘッダー行
            writer.println("userId,emailHash,domainName,status,attemptedAt,completedAt,isAlert,retryCount,lastRetriedAt");

            // 全件を Specification=null（条件なし）でストリーム処理
            // 件数が大きい場合でもページングせずバッチフェッチで対応（監査用途のため全件出力が前提）
            // Specification.where(null) は deprecated のため Specification.allOf(List.of()) を使用
            repo.findAll(Specification.allOf(List.of())).forEach(entity -> {
                PurgeStatusRow row = toRow(entity);
                writer.println(buildCsvLine(row));
            });

            writer.flush();
        }
    }

    /**
     * Specification を動的に組み立てる。
     * すべてのパラメータが null の場合は {@code WHERE 1=1}（全件）相当になる。
     */
    private Specification<AccountPurgeCompletionStatusEntity> buildSpec(
            String status,
            String domain,
            LocalDateTime dateFrom,
            LocalDateTime dateTo) {

        List<Specification<AccountPurgeCompletionStatusEntity>> specs = new ArrayList<>();

        if (status != null && !status.isBlank()) {
            specs.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (domain != null && !domain.isBlank()) {
            specs.add((root, query, cb) -> cb.equal(root.get("domainName"), domain));
        }
        if (dateFrom != null) {
            specs.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("attemptedAt"), dateFrom));
        }
        if (dateTo != null) {
            specs.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("attemptedAt"), dateTo));
        }

        // Specification.allOf は空リストで conjunction（WHERE 1=1）を返す
        return Specification.allOf(specs);
    }

    /**
     * エンティティを DTO に変換する。
     * アラートフラグ: PENDING かつ {@code attemptedAt} が閾値より古い場合に true。
     */
    private PurgeStatusRow toRow(AccountPurgeCompletionStatusEntity entity) {
        boolean isAlert = "PENDING".equals(entity.getStatus())
                && entity.getAttemptedAt() != null
                && entity.getAttemptedAt().isBefore(
                        LocalDateTime.now().minusMinutes(ALERT_THRESHOLD_MINUTES));
        return new PurgeStatusRow(
                entity.getUserId(),
                entity.getEmailHash(),
                entity.getDomainName(),
                entity.getStatus(),
                entity.getAttemptedAt(),
                entity.getCompletedAt(),
                isAlert,
                entity.getRetryCount(),
                entity.getLastRetriedAt());
    }

    /**
     * PurgeStatusRow を CSV の 1 行文字列に変換する。
     */
    private String buildCsvLine(PurgeStatusRow row) {
        return String.join(",",
                safeStr(row.userId()),
                safeStr(row.emailHash()),
                safeStr(row.domainName()),
                safeStr(row.status()),
                row.attemptedAt() != null ? row.attemptedAt().format(CSV_DATETIME_FORMAT) : "",
                row.completedAt() != null ? row.completedAt().format(CSV_DATETIME_FORMAT) : "",
                String.valueOf(row.isAlert()),
                safeStr(row.retryCount()),
                row.lastRetriedAt() != null ? row.lastRetriedAt().format(CSV_DATETIME_FORMAT) : "");
    }

    private String safeStr(Object val) {
        return val == null ? "" : val.toString();
    }
}
