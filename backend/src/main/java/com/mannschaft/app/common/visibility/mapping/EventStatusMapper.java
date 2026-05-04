package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.event.EventStatus;

/**
 * {@link com.mannschaft.app.event.EventStatus} を {@link ContentStatus} に正規化する。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.5
 * 「各機能 status の正規化」表に準拠する。</p>
 *
 * <p>マッピング:
 * <ul>
 *   <li>{@link EventStatus#DRAFT} → {@link ContentStatus#DRAFT}（作成者と SystemAdmin のみ可視）</li>
 *   <li>{@link EventStatus#PUBLISHED} / {@link EventStatus#REGISTRATION_OPEN}
 *       / {@link EventStatus#REGISTRATION_CLOSED} / {@link EventStatus#IN_PROGRESS}
 *       / {@link EventStatus#COMPLETED} → {@link ContentStatus#PUBLISHED}（visibility 評価へ）</li>
 *   <li>{@link EventStatus#CANCELLED} → {@link ContentStatus#ARCHIVED}（SystemAdmin のみ可視）</li>
 * </ul>
 *
 * <p>{@link ContentStatus#DELETED} は events.deleted_at 列を {@code @SQLRestriction} が
 * 取得時にフィルタするため、本マッパーで扱う必要は無い（取得不可 → 自然に fail-closed）。
 *
 * <p>EventEntity に {@code published_at} 列が無いため、設計書 §7.5 表の SCHEDULED 区分
 * （PUBLISHED かつ published_at が未来）はマッピング対象外。
 */
public final class EventStatusMapper {

    private EventStatusMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link EventStatus} を共通の {@link ContentStatus} に写像する。
     *
     * @param status 機能側 enum (non-null)
     * @return 対応する ContentStatus 値
     */
    public static ContentStatus toStandard(EventStatus status) {
        return switch (status) {
            case DRAFT -> ContentStatus.DRAFT;
            case PUBLISHED,
                 REGISTRATION_OPEN,
                 REGISTRATION_CLOSED,
                 IN_PROGRESS,
                 COMPLETED -> ContentStatus.PUBLISHED;
            case CANCELLED -> ContentStatus.ARCHIVED;
        };
    }
}
