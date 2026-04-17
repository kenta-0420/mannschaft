package com.mannschaft.app.social.announcement;

/**
 * お知らせウィジェットの元コンテンツ種別。
 *
 * <p>
 * ポリモルフィック参照で使用するソース種別を定義する。
 * DB カラムは VARCHAR(30) で保持し、Java 層でこの enum にマッピングすることで
 * 型安全性と将来の拡張性を両立する。
 * </p>
 *
 * <ul>
 *   <li>{@link #BLOG_POST} — ブログ記事（F06.1）</li>
 *   <li>{@link #BULLETIN_THREAD} — 掲示板スレッド（F05.1）</li>
 *   <li>{@link #TIMELINE_POST} — タイムライン投稿（F04.1）</li>
 *   <li>{@link #CIRCULATION_DOCUMENT} — 回覧板（F05.2）</li>
 *   <li>{@link #SURVEY} — アンケート・投票（F05.4）</li>
 * </ul>
 */
public enum AnnouncementSourceType {

    /** ブログ記事 */
    BLOG_POST,

    /** 掲示板スレッド */
    BULLETIN_THREAD,

    /** タイムライン投稿 */
    TIMELINE_POST,

    /** 回覧板 */
    CIRCULATION_DOCUMENT,

    /** アンケート・投票 */
    SURVEY
}
