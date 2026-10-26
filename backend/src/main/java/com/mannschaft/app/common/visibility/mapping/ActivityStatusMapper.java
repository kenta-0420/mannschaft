package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.activity.ActivityStatus;
import com.mannschaft.app.common.visibility.ContentStatus;

/**
 * {@link ActivityStatus} を {@link ContentStatus} に正規化する（F06.4 下書き対応）。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.5 の status 軸に準拠。
 * Survey の {@code SurveyStatus → ContentStatus} 写像を金型とする。</p>
 *
 * <ul>
 *   <li>{@link ActivityStatus#DRAFT} → {@link ContentStatus#DRAFT}（作成者・SystemAdmin のみ可視）</li>
 *   <li>{@link ActivityStatus#PUBLISHED} → {@link ContentStatus#PUBLISHED}（visibility 評価に進む）</li>
 *   <li>{@code null} → {@link ContentStatus#DELETED}（fail-closed）</li>
 * </ul>
 */
public final class ActivityStatusMapper {

    private ActivityStatusMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link ActivityStatus} を共通の {@link ContentStatus} に写像する。
     *
     * @param status 機能側 enum（{@code null} 可）
     * @return 対応する ContentStatus（{@code null} は fail-closed の DELETED）
     */
    public static ContentStatus toContentStatus(ActivityStatus status) {
        if (status == null) {
            // fail-closed: status 欠損は不可視（DELETED 相当）
            return ContentStatus.DELETED;
        }
        return switch (status) {
            case DRAFT -> ContentStatus.DRAFT;
            case PUBLISHED -> ContentStatus.PUBLISHED;
        };
    }
}
