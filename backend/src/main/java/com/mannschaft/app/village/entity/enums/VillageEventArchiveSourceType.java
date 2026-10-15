package com.mannschaft.app.village.entity.enums;

/**
 * 村史（行事アーカイブ）の元行事の種別（F17.2 Wave2 ⑦村史・設計書 §7.2）。
 *
 * <ul>
 *   <li>{@link #FESTIVAL}       — お祭り（ENDED 遷移時に自動編纂・③の主眼・設計書 §5.5）</li>
 *   <li>{@link #CALENDAR_EVENT} — 歳時記（年輪の記録・Wave2 以降で編纂契機を実装・設計書 §7.3）</li>
 *   <li>{@link #MEETUP}         — 寄合（決まったこと/コメントが残ったときに編纂・設計書 §7.3）</li>
 * </ul>
 *
 * <p>{@code village_event_archives.source_type} に {@code .name()} で格納する。
 * 集約テーブルは最初から3種を受けられる形で作る（後方互換・設計書 §7.3）。</p>
 */
public enum VillageEventArchiveSourceType {
    FESTIVAL,
    CALENDAR_EVENT,
    MEETUP
}
