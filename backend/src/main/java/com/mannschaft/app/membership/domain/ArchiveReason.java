package com.mannschaft.app.membership.domain;

/**
 * アーカイブ在籍の事由。
 *
 * <p>{@code memberships.archive_reason} の写像。逝去と転出は独立した 2 つの事実であり
 * 同時に成立しうるが、列は単一値のため「その時点の台帳事実から導出される値」として
 * 記録・取消のたびに再計算する（優先順位: {@link #DECEASED} > {@link #RELOCATED}）。</p>
 *
 * <p>設計書: docs/features/F14.3_resident_life_events.md §5.2 / §5.2.0</p>
 */
public enum ArchiveReason {

    /** 逝去。 */
    DECEASED,

    /** 転出。 */
    RELOCATED
}
