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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.YearMonth;
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
 *   <li>基準日時（現在から2年前）より古い最古のログから、経過しきった月を1ヶ月ずつ走査</li>
 *   <li>月内をキーセットページング（{@code id > cursor}）で全件取得し、ページ単位で R2 へアップロード</li>
 *   <li>当該月を書き切った後にのみ、その月のパーティションを DROP</li>
 * </ol>
 *
 * <h2>設計上の注意</h2>
 * <ul>
 *   <li>R2 キー形式: {@code audit-archive/{year}/{month}/audit-{year}-{month:02d}.json}</li>
 *   <li>1回の取得は最大 {@code PAGE_SIZE} 件（メモリ枯渇防止）。1ヶ月が複数ページに
 *       またがる場合は {@code .part{n}} 付きキーへ分割して書き出す</li>
 *   <li><b>アーカイブ内容と削除範囲は常に一致させる。</b>R2 アップロードが1ページでも失敗したら
 *       例外を送出し、当該月のパーティション DROP には決して進まない（未アーカイブのまま
 *       削除する事故の防止）</li>
 *   <li>基準日時を含む月は経過しきっていないため DROP せず次回へ持ち越す
 *       （パーティション単位の削除が基準日時より新しい行を巻き込むのを防ぐ）</li>
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
            LocalDateTime oldest = auditLogRepository.findOldestCreatedAtBefore(threshold);
            if (oldest == null) {
                log.info("[AuditLogArchiveBatch] アーカイブ対象なし。スキップします");
                return;
            }

            // 閾値を含む月は「まだ経過しきっていない」ため、パーティションを落とすと
            // 閾値より新しい行まで巻き込む。当該月は次回以降のバッチへ持ち越す。
            YearMonth cutoffMonth = YearMonth.from(threshold.toLocalDate());

            for (YearMonth ym = YearMonth.from(oldest.toLocalDate());
                 ym.isBefore(cutoffMonth);
                 ym = ym.plusMonths(1)) {

                long archivedInMonth = archiveMonth(ym);
                if (archivedInMonth == 0) {
                    // 当該月に行が無い。空ファイルも作らず、パーティションも落とさない。
                    continue;
                }
                totalArchived += archivedInMonth;

                // ここに到達するのは当該月の全行を R2 へ書き切った場合のみ。
                // アップロードが1ページでも失敗すれば例外が送出され DROP には至らない。
                dropPartition(ym);
                totalDeleted += archivedInMonth;
            }

            log.info("[AuditLogArchiveBatch] アーカイブ完了: アーカイブ={}件, DB削除={}件",
                    totalArchived, totalDeleted);

        } catch (Exception e) {
            log.error("[AuditLogArchiveBatch] アーカイブ処理失敗: アーカイブ済み={}件, DB削除={}件",
                    totalArchived, totalDeleted, e);
        }
    }

    /**
     * 指定年月の監査ログを<b>キーセットページング</b>で全件走査し、ページ単位で R2 へ書き出す。
     *
     * <p>カーソル（直前ページの最終 {@code id}）を必ず前進させるため、同一レコードが
     * 二重に出力されることはなく、当該月の全行が漏れなく書き出される。走査中に行を
     * 削除しないバッチでオフセットページングを使うと、毎周同じ先頭ページを取り直して
     * 出力が重複し、かつ2ページ目以降が永久に書き出されない。後段でパーティションごと
     * 削除する以上、それは監査ログの欠損に直結する。</p>
     *
     * <p>ページごとに別オブジェクトへ書くため、1ヶ月分をメモリに載せきる必要が無い。
     * 例外を握り潰さずそのまま送出し、呼び出し側でパーティション DROP を行わせない。</p>
     *
     * @param ym 対象年月
     * @return 当該月で R2 へ書き出した件数（0 なら対象行なし）
     */
    private long archiveMonth(YearMonth ym) {
        LocalDateTime from = ym.atDay(1).atStartOfDay();
        LocalDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay();

        long cursor = 0L;
        int part = 0;
        long archived = 0;

        while (true) {
            List<AuditLogEntity> page = auditLogRepository.findMonthSliceAfterId(
                    from, to, cursor, PageRequest.of(0, PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }

            uploadToR2(ym, part, page);
            archived += page.size();
            cursor = page.get(page.size() - 1).getId();
            part++;

            if (page.size() < PAGE_SIZE) {
                break;
            }
        }

        if (archived > 0) {
            log.info("[AuditLogArchiveBatch] R2アップロード完了: {}, {}件（{}オブジェクト）",
                    ym, archived, part);
        }
        return archived;
    }

    /**
     * 指定年月・指定パートの監査ログを R2 にアップロードする。
     *
     * <p>1ヶ月分が1ページに収まらない場合、パート番号ごとに別オブジェクトへ書き出す。
     * 同一キーへ上書きすると先に書いたページが失われ、パーティション DROP 後に
     * 復元不能な欠損となるため、パートごとにキーを分けること。</p>
     *
     * @param ym   対象年月
     * @param part パート番号（0 起点）
     * @param logs 対象ログ一覧
     */
    private void uploadToR2(YearMonth ym, int part, List<AuditLogEntity> logs) {
        String r2Key = buildR2Key(ym, part);

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
        return buildR2Key(ym, 0);
    }

    /**
     * パート番号付きの R2 オブジェクトキーを生成する。
     * 形式: {@code audit-archive/{year}/{month:02d}/audit-{year}-{month:02d}[.part{n}].json}
     *
     * <p>パート 0 は従来どおりサフィックス無し（既存アーカイブとの互換）。</p>
     *
     * @param ym   対象年月
     * @param part パート番号（0 起点）
     * @return R2 オブジェクトキー
     */
    public static String buildR2Key(YearMonth ym, int part) {
        String suffix = part == 0 ? "" : String.format(".part%d", part);
        return String.format("audit-archive/%d/%02d/audit-%d-%02d%s.json",
                ym.getYear(), ym.getMonthValue(),
                ym.getYear(), ym.getMonthValue(), suffix);
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
