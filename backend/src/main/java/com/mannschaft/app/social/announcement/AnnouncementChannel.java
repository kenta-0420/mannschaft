package com.mannschaft.app.social.announcement;

/**
 * F02.8 告知ウィザードで選択可能なチャネル種別。
 *
 * <p>各値は {@link AnnouncementSourceType} の対応する値と名前が一致するよう設計されており、
 * {@code AnnouncementChannelAdapterRegistry} でアダプターを解決する際の対応キーとして使用する。</p>
 */
public enum AnnouncementChannel {

    /** 掲示板スレッドチャネル */
    BULLETIN_THREAD,

    /** タイムライン投稿チャネル */
    TIMELINE_POST,

    /** ブログ記事チャネル */
    BLOG_POST,

    /** TODO タスクチャネル */
    TODO,

    /** スケジュールチャネル */
    SCHEDULE,

    /** アンケートチャネル */
    SURVEY
}
