package com.mannschaft.app.common.visibility.mapping;

import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;

/**
 * {@link ScheduleKeepStatus} を {@link ContentStatus} に正規化する（F03.17 §4.6.4 手順7）。
 *
 * <p>マッピング:
 * <ul>
 *   <li>{@link ScheduleKeepStatus#KEPT} → {@link ContentStatus#PUBLISHED}</li>
 *   <li>{@link ScheduleKeepStatus#SCHEDULED} → {@link ContentStatus#PUBLISHED}</li>
 *   <li>{@link ScheduleKeepStatus#ARCHIVED} → {@link ContentStatus#PUBLISHED}</li>
 * </ul>
 *
 * <p><strong>キープの {@code ARCHIVED} を {@link ContentStatus#ARCHIVED} に写像しない理由</strong>:
 * F00 の {@link ContentStatus#ARCHIVED} は「SystemAdmin のみ可視」という強い意味を持つ。
 * 一方 F03.17 の {@code ARCHIVED} は「見送り／完了」を表す利用者向けの状態であり、
 * メンバーは一覧（{@code status=ARCHIVED} 絞り込み）で参照し、{@code restore} で戻し、
 * {@code DELETE} で片付ける（設計書 §5.3 の状態遷移表）。F00 の ARCHIVED に写像すると
 * これらの正当な操作の入口が可視性段階で消え、機能そのものが成立しない。
 * キープの ARCHIVED が制限するのは<strong>内容の編集</strong>であって<strong>閲覧</strong>ではない。</p>
 *
 * <p>{@link ContentStatus#DELETED} は {@code schedule_keeps.deleted_at} 列を
 * {@code @SQLRestriction} が取得時にフィルタするため、本マッパーで扱う必要は無い
 * （取得不可 → 自然に fail-closed。{@code EventStatusMapper} と同じ流儀）。</p>
 *
 * <p>設計書: {@code docs/features/F03.17_schedule_keep.md} §4.6.4 / §5.3。</p>
 */
public final class ScheduleKeepStatusMapper {

    private ScheduleKeepStatusMapper() {
        throw new AssertionError("utility class");
    }

    /**
     * 機能側の {@link ScheduleKeepStatus} を共通の {@link ContentStatus} に写像する。
     *
     * @param status 機能側 enum（non-null）
     * @return 対応する ContentStatus 値
     */
    public static ContentStatus toStandard(ScheduleKeepStatus status) {
        return switch (status) {
            case KEPT, SCHEDULED, ARCHIVED -> ContentStatus.PUBLISHED;
        };
    }
}
