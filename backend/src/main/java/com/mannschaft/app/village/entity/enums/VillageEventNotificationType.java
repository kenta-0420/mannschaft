package com.mannschaft.app.village.entity.enums;

/**
 * 行事→村フィード自動還流＋通知の種別（F17.2 Wave2 ①フィード還流・設計書 §3.4）。
 *
 * <p>村ドメイン独自 enum として新設し、核 {@code NotificationType} は改変しない
 * （横断 enum への波及回避・設計書 §3.4）。値は次の2箇所で {@code .name()} 文字列として使う:</p>
 * <ul>
 *   <li>{@code timeline_posts.system_post_type}（システム自動投稿の種別・設計書 §3.2）</li>
 *   <li>通知の {@code notificationType}（文字列保存・設計書 §3.6）</li>
 * </ul>
 *
 * <table>
 *   <caption>発火契機（設計書 §3.4）</caption>
 *   <tr><td>{@link #EVENT_CREATED}</td><td>歳時記／祭／寄合を作成したとき</td></tr>
 *   <tr><td>{@link #EVENT_UPCOMING}</td><td>行事の開催前日（接近・前日1回のみ）</td></tr>
 *   <tr><td>{@link #MEETUP_CONFIRMED}</td><td>寄合が CONFIRMED に遷移したとき</td></tr>
 *   <tr><td>{@link #FESTIVAL_STARTED}</td><td>祭が SCHEDULED→ACTIVE に遷移したとき</td></tr>
 * </table>
 */
public enum VillageEventNotificationType {
    EVENT_CREATED,
    EVENT_UPCOMING,
    MEETUP_CONFIRMED,
    FESTIVAL_STARTED
}
