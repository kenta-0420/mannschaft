package com.mannschaft.app.social.announcement;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * お知らせフィード同期イベントリスナー（F02.6）。
 *
 * <p>
 * 元コンテンツ（ブログ記事・掲示板スレッドなど）が削除・復元・visibility 変更された場合に
 * {@code announcement_feeds} テーブルを自動的に同期するためのイベントリスナー。
 * メインのトランザクションに影響させないよう、{@code AFTER_COMMIT} フェーズで
 * {@code @Async} 実行する。
 * </p>
 *
 * <p>
 * <b>購読イベント</b>:
 * <ul>
 *   <li>{@link ContentDeletedEvent} — 元コンテンツ削除時に {@code source_deleted_at} をセット</li>
 *   <li>{@link ContentRestoredEvent} — 元コンテンツ復元時に {@code source_deleted_at} を NULL に戻す</li>
 *   <li>{@link ContentVisibilityChangedEvent} — visibility が PRIVATE に変更された場合に物理削除</li>
 * </ul>
 * </p>
 *
 * <p>
 * <b>呼び出し方法</b>:
 * 各 Service（BlogPostService・BulletinThreadService など）から
 * {@code ApplicationEventPublisher.publishEvent()} でイベントを発行すること。
 *
 * <pre>
 * // TODO: 各 Service から ApplicationEventPublisher.publishEvent() を呼ぶこと。例:
 * // eventPublisher.publishEvent(new ContentDeletedEvent(AnnouncementSourceType.BLOG_POST, post.getId()));
 * </pre>
 * </p>
 *
 * <p>
 * <b>設計書参照</b>: docs/features/F02.6_announcement_widget.md §5.2・§5.3・§5.7
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementSyncEventListener {

    private final AnnouncementFeedRepository feedRepository;

    // ═════════════════════════════════════════════════════════════
    // イベントハンドラ
    // ═════════════════════════════════════════════════════════════

    /**
     * 元コンテンツ削除イベントを受信して {@code source_deleted_at} をセットする。
     *
     * <p>
     * 物理削除ではなく {@code source_deleted_at = NOW()} に更新することで
     * ウィジェット一覧から除外する。90日後にバッチで物理削除される（設計書 §5.2）。
     * </p>
     *
     * @param event 元コンテンツ削除イベント
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void handleContentDeleted(ContentDeletedEvent event) {
        try {
            List<AnnouncementFeedEntity> feeds = feedRepository
                    .findAllBySource(event.sourceType(), event.sourceId());

            if (feeds.isEmpty()) {
                return;
            }

            feeds.forEach(AnnouncementFeedEntity::markSourceDeleted);
            feedRepository.saveAll(feeds);

            log.debug("お知らせフィード削除マーク完了 sourceType={}, sourceId={}, count={}",
                    event.sourceType(), event.sourceId(), feeds.size());
        } catch (Exception e) {
            log.warn("お知らせフィード削除マーク失敗 sourceType={}, sourceId={}, error={}",
                    event.sourceType(), event.sourceId(), e.getMessage(), e);
        }
    }

    /**
     * 元コンテンツ復元イベントを受信して {@code source_deleted_at} を NULL に戻す。
     *
     * <p>
     * 論理削除が取り消された場合（deleted_at = NULL に戻された場合）に呼ばれる。
     * </p>
     *
     * @param event 元コンテンツ復元イベント
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void handleContentRestored(ContentRestoredEvent event) {
        try {
            List<AnnouncementFeedEntity> feeds = feedRepository
                    .findAllBySource(event.sourceType(), event.sourceId());

            if (feeds.isEmpty()) {
                return;
            }

            feeds.forEach(AnnouncementFeedEntity::restoreSource);
            feedRepository.saveAll(feeds);

            log.debug("お知らせフィード復元完了 sourceType={}, sourceId={}, count={}",
                    event.sourceType(), event.sourceId(), feeds.size());
        } catch (Exception e) {
            log.warn("お知らせフィード復元失敗 sourceType={}, sourceId={}, error={}",
                    event.sourceType(), event.sourceId(), e.getMessage(), e);
        }
    }

    /**
     * 元コンテンツ visibility 変更イベントを受信して同期する。
     *
     * <p>
     * <b>処理ルール（設計書 §5.3）</b>:
     * <ul>
     *   <li>新しい visibility が {@code PRIVATE} の場合: {@code announcement_feeds} を物理削除
     *       （お知らせ化の前提が崩れるため）</li>
     *   <li>それ以外の場合: {@code announcement_feeds.visibility} を更新して表示範囲を同期</li>
     * </ul>
     * </p>
     *
     * @param event 元コンテンツ visibility 変更イベント
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional
    public void handleContentVisibilityChanged(ContentVisibilityChangedEvent event) {
        try {
            List<AnnouncementFeedEntity> feeds = feedRepository
                    .findAllBySource(event.sourceType(), event.sourceId());

            if (feeds.isEmpty()) {
                return;
            }

            if ("PRIVATE".equals(event.newVisibility())) {
                // PRIVATE に変更された場合は物理削除（設計書 §5.3）
                feedRepository.deleteAll(feeds);
                log.debug("お知らせフィード物理削除（PRIVATE変更） sourceType={}, sourceId={}, count={}",
                        event.sourceType(), event.sourceId(), feeds.size());
            } else {
                // それ以外は visibility を同期更新
                feeds.forEach(feed -> feed.updateVisibility(event.newVisibility()));
                feedRepository.saveAll(feeds);
                log.debug("お知らせフィード visibility 同期完了 sourceType={}, sourceId={}, newVisibility={}, count={}",
                        event.sourceType(), event.sourceId(), event.newVisibility(), feeds.size());
            }
        } catch (Exception e) {
            log.warn("お知らせフィード visibility 同期失敗 sourceType={}, sourceId={}, error={}",
                    event.sourceType(), event.sourceId(), e.getMessage(), e);
        }
    }

    // ═════════════════════════════════════════════════════════════
    // イベント定義（既存の ApplicationEvent が存在しない場合のシンプルな record 定義）
    // ─────────────────────────────────────────────────────────────
    // TODO: 各 Service から ApplicationEventPublisher.publishEvent() を呼ぶこと。
    //   例（BlogPostService 内）:
    //     eventPublisher.publishEvent(new ContentDeletedEvent(AnnouncementSourceType.BLOG_POST, post.getId()));
    //     eventPublisher.publishEvent(new ContentRestoredEvent(AnnouncementSourceType.BLOG_POST, post.getId()));
    //     eventPublisher.publishEvent(new ContentVisibilityChangedEvent(
    //         AnnouncementSourceType.BLOG_POST, post.getId(), "PRIVATE"));
    // ═════════════════════════════════════════════════════════════

    /**
     * 元コンテンツ削除イベント。
     *
     * <p>
     * 元コンテンツが削除された（論理削除または物理削除）場合に発行する。
     * 各 Service（BlogPostService・BulletinThreadService 等）で
     * {@code ApplicationEventPublisher.publishEvent()} により発行すること。
     * </p>
     *
     * @param sourceType 元コンテンツ種別
     * @param sourceId   元コンテンツ ID
     */
    public record ContentDeletedEvent(
            AnnouncementSourceType sourceType,
            Long sourceId) {
    }

    /**
     * 元コンテンツ復元イベント。
     *
     * <p>
     * 元コンテンツの論理削除が取り消された場合に発行する。
     * </p>
     *
     * @param sourceType 元コンテンツ種別
     * @param sourceId   元コンテンツ ID
     */
    public record ContentRestoredEvent(
            AnnouncementSourceType sourceType,
            Long sourceId) {
    }

    /**
     * 元コンテンツ visibility 変更イベント。
     *
     * <p>
     * 元コンテンツの公開範囲が変更された場合に発行する。
     * {@code newVisibility} が {@code "PRIVATE"} の場合はお知らせを物理削除する。
     * </p>
     *
     * @param sourceType    元コンテンツ種別
     * @param sourceId      元コンテンツ ID
     * @param newVisibility 新しい visibility 値（"PUBLIC" / "MEMBERS_ONLY" / "SUPPORTERS_AND_ABOVE" / "PRIVATE"）
     */
    public record ContentVisibilityChangedEvent(
            AnnouncementSourceType sourceType,
            Long sourceId,
            String newVisibility) {
    }
}
