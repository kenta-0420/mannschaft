package com.mannschaft.app.social.announcement.audience;

import java.util.UUID;

/**
 * 告知の宛先の「範囲」指定（F01.2.1 §8.1）。並び順（sort_order 昇順）で開始から終了までのグループを含む。
 *
 * <ul>
 *   <li>{@code fromGroupId = null} … 先頭から {@code toGroupId} まで（「A 以前」）</li>
 *   <li>{@code toGroupId = null} … {@code fromGroupId} から末尾まで（「A 以降」）</li>
 *   <li>両方 null は範囲が成り立たないので 400 {@code BROADCAST_008}</li>
 * </ul>
 *
 * @param fromGroupId 開始グループ ID（null 可）
 * @param toGroupId   終了グループ ID（null 可）
 */
public record TargetGroupRange(UUID fromGroupId, UUID toGroupId) {
}
