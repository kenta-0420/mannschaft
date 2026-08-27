package com.mannschaft.app.chat.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * チャットメッセージアーカイブバッチ。
 *
 * <p>6か月以上前のチャットメッセージを {@code chat_messages} から
 * {@code chat_messages_archive} へ移送し、メインテーブルを小さく保つ。</p>
 *
 * <h2>スケジュール</h2>
 * <ul>
 *   <li>毎日 AM 3:30 JST（監査ログアーカイブ AM 2:00 と時間帯をずらす）</li>
 *   <li>ShedLock により複数インスタンス起動時の重複実行を防止</li>
 * </ul>
 *
 * <h2>処理フロー</h2>
 * <ol>
 *   <li>閾値（6か月前）より古い論理削除済みメッセージを INSERT INTO ... SELECT で移送</li>
 *   <li>アーカイブ成功後に元テーブルから物理削除</li>
 *   <li>1バッチあたり最大 5,000 件（メモリ枯渇防止）</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatMessageArchiveBatchService {

    private static final int RETENTION_MONTHS = 6;
    private static final int BATCH_SIZE = 5_000;

    private final JdbcTemplate jdbcTemplate;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チャットメッセージのアーカイブ移送であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "chat-message-archive-daily", description = "6 ヶ月以上前の chat_messages を毎日 03:30 にアーカイブテーブルへ移送する")
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "chatMessageArchiveBatch", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void archiveOldMessages() {
        LocalDateTime threshold = LocalDateTime.now().minusMonths(RETENTION_MONTHS);
        log.info("[ChatMessageArchiveBatch] アーカイブ開始: 基準日時={}", threshold);

        long totalArchived = 0;
        long totalDeleted = 0;

        try {
            while (true) {
                int archived = insertIntoArchive(threshold);
                if (archived == 0) break;

                int deleted = deleteArchived(threshold, archived);
                totalArchived += archived;
                totalDeleted += deleted;

                log.info("[ChatMessageArchiveBatch] バッチ完了: 移送={}件, 削除={}件（累計: 移送={}, 削除={}）",
                        archived, deleted, totalArchived, totalDeleted);

                if (archived < BATCH_SIZE) break;
            }

            log.info("[ChatMessageArchiveBatch] 完了: 総移送={}件, 総削除={}件", totalArchived, totalDeleted);

        } catch (Exception e) {
            log.error("[ChatMessageArchiveBatch] 失敗: 移送済み={}件", totalArchived, e);
        }
    }

    @Transactional
    public int insertIntoArchive(LocalDateTime threshold) {
        return jdbcTemplate.update(
                "INSERT IGNORE INTO chat_messages_archive " +
                "  (id, channel_id, sender_id, parent_id, body, forwarded_from_id, " +
                "   is_edited, is_system, scheduled_at, reply_count, reaction_count, " +
                "   is_pinned, created_at, updated_at, deleted_at) " +
                "SELECT id, channel_id, sender_id, parent_id, body, forwarded_from_id, " +
                "       is_edited, is_system, scheduled_at, reply_count, reaction_count, " +
                "       is_pinned, created_at, updated_at, deleted_at " +
                "FROM chat_messages " +
                "WHERE created_at < ? AND deleted_at IS NOT NULL " +
                "ORDER BY created_at ASC " +
                "LIMIT ?",
                threshold, BATCH_SIZE);
    }

    @Transactional
    public int deleteArchived(LocalDateTime threshold, int limit) {
        return jdbcTemplate.update(
                "DELETE FROM chat_messages " +
                "WHERE created_at < ? AND deleted_at IS NOT NULL " +
                "  AND id IN (SELECT id FROM chat_messages_archive WHERE created_at < ?) " +
                "LIMIT ?",
                threshold, threshold, limit);
    }
}
