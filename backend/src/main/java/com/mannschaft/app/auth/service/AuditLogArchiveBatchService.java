package com.mannschaft.app.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.common.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 監査ログアーカイブバッチ。
 *
 * <p>DB の {@code audit_logs} テーブルから2年以上前のレコードを取得し、
 * Cloudflare R2 の {@code audit-archive/{year}/{month}/audit-{year}-{month}.json}
 * パスに JSON 形式でアーカイブした後、DB から物理削除する。</p>
 *
 * <h2>スケジュール</h2>
 * <ul>
 *   <li>毎月1日 AM 2:00 JST に実行</li>
 *   <li>ShedLock により複数インスタンス起動時の重複実行を防止</li>
 * </ul>
 *
 * <h2>処理フロー</h2>
 * <ol>
 *   <li>基準日時（現在から2年前）より古いログをページング取得</li>
 *   <li>年月ごとにグループ化して JSON ファイルを R2 にアップロード</li>
 *   <li>アーカイブ済みの最大 ID を記録し、DB から物理削除</li>
 * </ol>
 *
 * <h2>設計上の注意</h2>
 * <ul>
 *   <li>R2 キー形式: {@code audit-archive/{year}/{month}/audit-{year}-{month:02d}.json}</li>
 *   <li>1バッチあたり最大 {@code PAGE_SIZE} 件ずつ処理（メモリ枯渇防止）</li>
 *   <li>R2 アップロード失敗時はバッチを中断し、DB 削除を行わない（データ損失防止）</li>
 * </ul>
 *
 * @see F10.3 audit_logs 設計書 §6「保持ポリシー」
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogArchiveBatchService {

    private static final int RETENTION_YEARS = 2;
    private static final int PAGE_SIZE = 1000;

    private final AuditLogRepository auditLogRepository;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;

    @BatchEndpoint(name = "auth-audit-log-archive-monthly", description = "2 年以上前の audit_logs を R2 に毎月 1 日 02:00 アーカイブして物理削除する")
    @Scheduled(cron = "0 0 2 1 * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "auditLogArchiveBatch", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void archiveOldLogs() {
        LocalDateTime threshold = LocalDateTime.now().minusYears(RETENTION_YEARS);
        log.info("[AuditLogArchiveBatch] アーカイブ開始: 基準日時={}", threshold);

        long totalArchived = 0;
        long totalDeleted = 0;

        try {
            // グループ化用バッファ: 年月 → ログリスト
            Map<YearMonth, List<AuditLogEntity>> groupedByMonth = new TreeMap<>();

            boolean hasMore = true;
            while (hasMore) {
                Slice<AuditLogEntity> slice = auditLogRepository.findOlderThan(
                        threshold, PageRequest.of(0, PAGE_SIZE));
                List<AuditLogEntity> logs = slice.getContent();

                if (logs.isEmpty()) {
                    break;
                }

                // 年月ごとにグループ化
                for (AuditLogEntity entry : logs) {
                    YearMonth ym = YearMonth.from(entry.getCreatedAt().toLocalDate());
                    groupedByMonth.computeIfAbsent(ym, k -> new ArrayList<>()).add(entry);
                }

                totalArchived += logs.size();
                hasMore = slice.hasNext();

                if (!hasMore || totalArchived >= 100_000) {
                    // 10万件以上になったら一旦 R2 にアップロードして DB 削除
                    break;
                }
            }

            if (groupedByMonth.isEmpty()) {
                log.info("[AuditLogArchiveBatch] アーカイブ対象なし。スキップします");
                return;
            }

            // 年月ごとに R2 へアップロード
            for (Map.Entry<YearMonth, List<AuditLogEntity>> monthEntry : groupedByMonth.entrySet()) {
                YearMonth ym = monthEntry.getKey();
                List<AuditLogEntity> monthLogs = monthEntry.getValue();

                uploadToR2(ym, monthLogs);
                log.info("[AuditLogArchiveBatch] R2アップロード完了: {}, {}件", ym, monthLogs.size());
            }

            // R2 アップロード完了後、対象年月のパーティションを DROP（瞬時削除）
            for (Map.Entry<YearMonth, List<AuditLogEntity>> monthEntry : groupedByMonth.entrySet()) {
                dropPartition(monthEntry.getKey());
                totalDeleted += monthEntry.getValue().size();
            }

            log.info("[AuditLogArchiveBatch] アーカイブ完了: アーカイブ={}件, DB削除={}件",
                    totalArchived, totalDeleted);

        } catch (Exception e) {
            log.error("[AuditLogArchiveBatch] アーカイブ処理失敗: アーカイブ済み={}件, DB削除={}件",
                    totalArchived, totalDeleted, e);
        }
    }

    /**
     * 指定年月の監査ログを R2 にアップロードする。
     * キー形式: {@code audit-archive/{year}/{month:02d}/audit-{year}-{month:02d}.json}
     *
     * <p>既存のオブジェクトが存在する場合は追記する（同月分の2回目バッチ実行への対応）。
     * R2 は append をサポートしないため、既存オブジェクトの内容は新規の upload で上書きされる。
     * Phase 1 では重複リスクを許容し、シンプルな上書き方式を採用する。</p>
     *
     * @param ym   対象年月
     * @param logs 対象ログ一覧
     */
    private void uploadToR2(YearMonth ym, List<AuditLogEntity> logs) {
        String r2Key = buildR2Key(ym);

        List<Map<String, Object>> records = logs.stream()
                .map(this::toRecord)
                .toList();

        byte[] jsonBytes;
        try {
            jsonBytes = objectMapper.writeValueAsBytes(records);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[AuditLogArchiveBatch] JSON シリアライズ失敗: ym=" + ym, e);
        }

        storageService.upload(r2Key, jsonBytes, "application/json");
    }

    /**
     * アーカイブ済みのログを DB から物理削除する（パーティション DROP の代替手段）。
     * パーティション導入後は通常 {@link #dropPartition(YearMonth)} を使用する。
     *
     * @param maxId     削除対象の最大 ID
     * @param threshold 基準日時（二重チェック用）
     * @return 削除件数
     */
    @Transactional
    public int deleteArchivedFromDb(Long maxId, LocalDateTime threshold) {
        return auditLogRepository.deleteArchivedLogs(maxId, threshold);
    }

    /**
     * アーカイブ完了後、指定年月のパーティションを DROP する（瞬時削除）。
     *
     * <p>パーティション名の形式: {@code p_YYYY_MM}（例: p_2024_01）</p>
     * <p>p_future パーティションは絶対に DROP しない。</p>
     *
     * @param ym 対象年月
     */
    public void dropPartition(YearMonth ym) {
        String partitionName = String.format("p_%d_%02d", ym.getYear(), ym.getMonthValue());
        if ("p_future".equals(partitionName)) {
            log.warn("[AuditLogArchiveBatch] p_future パーティションのDROPはスキップします");
            return;
        }
        String sql = "ALTER TABLE audit_logs DROP PARTITION " + partitionName;
        jdbcTemplate.execute(sql);
        log.info("[AuditLogArchiveBatch] パーティションDROP完了: {}", partitionName);
    }

    /**
     * R2 オブジェクトキーを生成する。
     * 形式: {@code audit-archive/{year}/{month:02d}/audit-{year}-{month:02d}.json}
     *
     * @param ym 対象年月
     * @return R2 オブジェクトキー
     */
    public static String buildR2Key(YearMonth ym) {
        return String.format("audit-archive/%d/%02d/audit-%d-%02d.json",
                ym.getYear(), ym.getMonthValue(),
                ym.getYear(), ym.getMonthValue());
    }

    /**
     * {@link AuditLogEntity} を JSON レコード用の Map に変換する。
     */
    private Map<String, Object> toRecord(AuditLogEntity entity) {
        Map<String, Object> record = new TreeMap<>();
        record.put("id", entity.getId());
        record.put("user_id", entity.getUserId());
        record.put("target_user_id", entity.getTargetUserId());
        record.put("team_id", entity.getTeamId());
        record.put("organization_id", entity.getOrganizationId());
        record.put("event_type", entity.getEventType());
        record.put("ip_address", entity.getIpAddress());
        record.put("user_agent", entity.getUserAgent());
        record.put("session_hash", entity.getSessionHash());
        record.put("metadata", entity.getMetadata());
        record.put("created_at", entity.getCreatedAt() != null
                ? entity.getCreatedAt().toString() : null);
        return record;
    }
}
