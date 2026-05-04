package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.corkboard.entity.CorkboardCardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardCardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F09.8 Phase A-4: コルクボードのバッチ処理。
 *
 * <p>3 つのバッチを {@code @Scheduled} + {@code @SchedulerLock} で提供する:</p>
 * <ol>
 *   <li>{@link #autoArchiveCards()} — 毎時 0 分: {@code auto_archive_at <= NOW()}
 *       のカードを {@code is_archived=true} へ</li>
 *   <li>{@link #detectDeadReferences()} — 日次 03:00:
 *       REFERENCE カードの参照先を referenceType 別に IN 句確認し、
 *       存在しなければ {@code is_ref_deleted=true} を設定</li>
 *   <li>{@link #purgeSoftDeletedCards()} — 日次 04:00:
 *       {@code deleted_at < NOW() - 90 days} のカードを物理削除</li>
 * </ol>
 *
 * <p>デッドリファレンス検知は referenceType ごとに対応テーブルへ {@code SELECT id FROM ... WHERE id IN (..) AND deleted_at IS NULL}
 * を実行し、返ってこなかった ID を「削除済み」として扱う。
 * 各機能テーブルに依存リポジトリを増やさず JdbcTemplate でバッチ確認することで、
 * Phase A スコープ内で完結する設計とする。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CorkboardBatchService {

    /** 論理削除から物理削除までの保持日数（設計書 §A-4）。 */
    private static final int RETENTION_DAYS = 90;

    /** 1 回の IN 句で確認する最大件数（プレースホルダ上限の安全側）。 */
    private static final int IN_BATCH_SIZE = 500;

    /** 1 回の物理削除で扱う最大件数。 */
    private static final int PURGE_BATCH_SIZE = 500;

    /**
     * 参照タイプ → 対応テーブル名のマップ。
     * 各テーブルは id (PK) と deleted_at（論理削除カラム）を持つ前提。
     * deleted_at を持たないテーブルは {@code null} を入れて存在確認のみ行う。
     */
    private static final Map<String, RefTable> REF_TABLES = buildRefTables();

    private static Map<String, RefTable> buildRefTables() {
        Map<String, RefTable> m = new LinkedHashMap<>();
        m.put("CHAT_MESSAGE", new RefTable("chat_messages", true));
        m.put("TIMELINE_POST", new RefTable("timeline_posts", true));
        m.put("BULLETIN_THREAD", new RefTable("bulletin_threads", true));
        m.put("BLOG_POST", new RefTable("blog_posts", true));
        m.put("FILE", new RefTable("files", true));
        return m;
    }

    private final CorkboardCardRepository cardRepository;
    private final JdbcTemplate jdbcTemplate;

    // =====================================================================
    // バッチ 1: 自動アーカイブ（毎時 0 分）
    // =====================================================================

    /**
     * {@code auto_archive_at <= now} のカードを {@code is_archived=true} へ更新する。
     * 設計書 §A-4 自動アーカイブバッチ。
     */
    @Scheduled(cron = "0 0 * * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "corkboardAutoArchiveBatch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT0S")
    @Transactional
    public void autoArchiveCards() {
        executeAutoArchive(LocalDateTime.now());
    }

    /** テスト可能な実装本体（時刻を引数で受ける）。 */
    int executeAutoArchive(LocalDateTime now) {
        List<CorkboardCardEntity> targets = cardRepository.findByIsArchivedFalseAndAutoArchiveAtBefore(now);
        if (targets.isEmpty()) {
            return 0;
        }
        for (CorkboardCardEntity card : targets) {
            card.archive(true);
        }
        cardRepository.saveAll(targets);
        log.info("コルクボード自動アーカイブ完了: {} 件", targets.size());
        return targets.size();
    }

    // =====================================================================
    // バッチ 2: デッドリファレンス検知（日次 03:00）
    // =====================================================================

    /**
     * REFERENCE カードの参照先存在を referenceType 別に IN 句で確認し、
     * 存在しないものを {@code is_ref_deleted=true} に設定する。
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "corkboardDeadReferenceDetectBatch", lockAtMostFor = "PT15M", lockAtLeastFor = "PT0S")
    @Transactional
    public void detectDeadReferences() {
        executeDeadReferenceDetection();
    }

    /** テスト可能な実装本体。検知件数の合計を返す。 */
    int executeDeadReferenceDetection() {
        int totalMarked = 0;
        for (Map.Entry<String, RefTable> entry : REF_TABLES.entrySet()) {
            String type = entry.getKey();
            RefTable table = entry.getValue();
            List<CorkboardCardEntity> cards = cardRepository.findActiveReferenceCardsByType(type);
            if (cards.isEmpty()) {
                continue;
            }
            Set<Long> existingIds = collectExistingIds(table, cards);
            int markedForType = 0;
            for (CorkboardCardEntity card : cards) {
                Long refId = card.getReferenceId();
                if (refId == null || !existingIds.contains(refId)) {
                    card.markRefDeleted(true);
                    markedForType++;
                }
            }
            if (markedForType > 0) {
                cardRepository.saveAll(cards);
                log.info("コルクボード デッドリファレンス検知: type={}, marked={} 件", type, markedForType);
                totalMarked += markedForType;
            }
        }
        log.info("コルクボード デッドリファレンス検知完了: 合計 {} 件", totalMarked);
        return totalMarked;
    }

    /**
     * referenceId 集合をバッチで IN 句確認し、現存する ID のみ返す。
     * deleted_at カラムを持つテーブルは {@code deleted_at IS NULL} で絞る。
     */
    private Set<Long> collectExistingIds(RefTable table, List<CorkboardCardEntity> cards) {
        Set<Long> requested = new HashSet<>();
        for (CorkboardCardEntity card : cards) {
            if (card.getReferenceId() != null) {
                requested.add(card.getReferenceId());
            }
        }
        if (requested.isEmpty()) {
            return Set.of();
        }
        Set<Long> existing = new HashSet<>();
        List<Long> all = List.copyOf(requested);
        for (int i = 0; i < all.size(); i += IN_BATCH_SIZE) {
            List<Long> chunk = all.subList(i, Math.min(i + IN_BATCH_SIZE, all.size()));
            String placeholders = String.join(",", chunk.stream().map(x -> "?").toList());
            String sql = "SELECT id FROM " + table.tableName()
                    + " WHERE id IN (" + placeholders + ")"
                    + (table.hasDeletedAt() ? " AND deleted_at IS NULL" : "");
            try {
                List<Long> rows = jdbcTemplate.query(sql,
                        (rs, rn) -> rs.getLong(1),
                        chunk.toArray());
                existing.addAll(rows);
            } catch (Exception e) {
                // テーブル不在等はバッチ全体を止めない（WARN ログのみ）
                log.warn("デッドリファレンス確認 SQL 失敗: table={}, cause={}",
                        table.tableName(), e.getClass().getSimpleName());
                // 失敗時は安全側として「存在する」とみなして全件加える（誤って削除フラグを立てない）
                existing.addAll(chunk);
            }
        }
        return existing;
    }

    // =====================================================================
    // バッチ 3: 論理削除カードの物理削除（日次 04:00）
    // =====================================================================

    /**
     * {@code deleted_at < NOW() - 90 days} のカードを物理削除する。
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "corkboardPurgeSoftDeletedBatch", lockAtMostFor = "PT15M", lockAtLeastFor = "PT0S")
    @Transactional
    public void purgeSoftDeletedCards() {
        executePurge(LocalDateTime.now());
    }

    /** テスト可能な実装本体。削除件数を返す。 */
    int executePurge(LocalDateTime now) {
        LocalDateTime threshold = now.minusDays(RETENTION_DAYS);
        List<Long> ids = cardRepository.findCardIdsDeletedBefore(threshold);
        if (ids.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < ids.size(); i += PURGE_BATCH_SIZE) {
            List<Long> chunk = ids.subList(i, Math.min(i + PURGE_BATCH_SIZE, ids.size()));
            total += cardRepository.hardDeleteByIds(chunk);
        }
        log.info("コルクボード論理削除カードの物理削除完了: 対象 {} 件, 削除 {} 件", ids.size(), total);
        return total;
    }

    /**
     * 参照タイプに対応するテーブル設定。
     */
    record RefTable(String tableName, boolean hasDeletedAt) {
    }
}
