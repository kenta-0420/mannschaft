package com.mannschaft.app.reflection;

/**
 * F06.5 アクティブリコール学習機能の定数。
 *
 * <p>想起間隔・通知時刻・DoS/スパム対策上限・JSON サイズ上限などを集約する（設計書 §2.3 / §2.5.1 / §5.3）。</p>
 */
public final class ReflectionConstants {

    private ReflectionConstants() {
    }

    // ─── 想起・通知 ───────────────────────────────────────────────
    /** 想起通知の既定時刻（ユーザー TZ・未設定ユーザーに適用・§5.3）。 */
    public static final int DEFAULT_REMIND_HOUR = 8;

    /** 想起間隔の既定 CSV（昇順・§2.6）。 */
    public static final String DEFAULT_RECALL_INTERVALS = "1,3,7,14";

    /** 定期考査前リマインドの N 日前（14/7/3/1・§5.5）。 */
    public static final int[] PRE_EXAM_DAYS_BEFORE = {14, 7, 3, 1};

    // ─── DoS / スパム対策上限（§2.5.1） ───────────────────────────
    /** ユーザーあたり PENDING リマインダー総数上限（超過は 400）。 */
    public static final int MAX_PENDING_REMINDERS = 1000;

    /** ユーザーあたりテーマ数上限（超過は 400）。 */
    public static final int MAX_THEMES_PER_USER = 100;

    /** target_date 許容範囲: 過去日数（ユーザー TZ の今日基準・範囲外は 400）。 */
    public static final int TARGET_DATE_PAST_DAYS = 365;

    /** target_date 許容範囲: 未来日数（ユーザー TZ の今日基準・範囲外は 400）。 */
    public static final int TARGET_DATE_FUTURE_DAYS = 30;

    // ─── structured_content バリデーション（§2.3） ───────────────
    /** structured_content JSON のサイズ上限（バイト）。 */
    public static final int MAX_STRUCTURED_CONTENT_BYTES = 64 * 1024;

    /** sections の最大件数。 */
    public static final int MAX_SECTIONS = 30;

    /** 各 section の subsections の最大件数。 */
    public static final int MAX_SUBSECTIONS = 30;

    /** main_theme / heading / sub_heading の最大文字数。 */
    public static final int MAX_HEADING_LENGTH = 200;

    /** detail / supplement の最大文字数。 */
    public static final int MAX_DETAIL_LENGTH = 2000;

    /** free_note の最大文字数。 */
    public static final int MAX_FREE_NOTE_LENGTH = 10000;

    // ─── Phase 4: 暗記カード（TERM_CARD）バリデーション（§13-A-3） ───
    /** 1 section あたりの cards（暗記カード）枚数の上限。 */
    public static final int MAX_CARDS_PER_SECTION = 50;

    /** card.term の最大文字数（heading と同水準）。 */
    public static final int MAX_CARD_TERM_LENGTH = 200;

    /** card.meaning の最大文字数。 */
    public static final int MAX_CARD_MEANING_LENGTH = 200;

    // ─── Phase 4: 期間横断 単語帳ビュー（§13-F・EP #23） ───
    /** 期間横断 単語帳ビューの期間幅（from〜to）の上限日数（超過は 400・REFLECTION_015）。 */
    public static final int MAX_VOCAB_DATE_RANGE_DAYS = 366;

    /** 期間横断 単語帳ビューの既定ページサイズ。 */
    public static final int DEFAULT_VOCAB_PAGE_SIZE = 200;

    /** 期間横断 単語帳ビューのページサイズ上限。 */
    public static final int MAX_VOCAB_PAGE_SIZE = 500;

    // ─── recall_interval_days（§2.6） ─────────────────────────────
    /** recall_interval_days の各値の最小（1 日）。 */
    public static final int MIN_RECALL_INTERVAL = 1;

    /** recall_interval_days の各値の最大（365 日）。 */
    public static final int MAX_RECALL_INTERVAL = 365;

    /** recall_interval_days の最大個数。 */
    public static final int MAX_RECALL_INTERVAL_COUNT = 8;

    // ─── 通知種別 ───────────────────────────────────────────────
    /** 想起リマインドの通知種別。 */
    public static final String NOTIFICATION_TYPE_RECALL_REMINDER = "REFLECTION_RECALL_REMINDER";

    /** 通知の sourceType。 */
    public static final String NOTIFICATION_SOURCE_TYPE = "REFLECTION";
}
