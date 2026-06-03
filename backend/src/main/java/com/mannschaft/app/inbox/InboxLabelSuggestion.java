package com.mannschaft.app.inbox;

/**
 * F04.11 統合通知インボックス：自動ラベリング「提案」キー（案C 静的ルール導出・非永続）。
 *
 * <p>マスター御裁可＝案C「提案＋1タップ付与」。提案は {@code (InboxSourceType, InboxPriority)} から
 * {@link com.mannschaft.app.inbox.service.InboxLabelSuggestionRules} で導出するのみで <b>DB に保存しない</b>。
 * 1 タップ付与 API（{@code POST /api/v1/inbox/labels/suggest-apply}）で初めて実ラベルが作成・付与される。</p>
 *
 * <p><b>UI 表示名は持たない</b>（i18n は FE が {@code suggestionKey} から解決する）。
 * BE は提案キー・既定色（{@link #defaultColor()}）・名寄せ用の既定名（{@link #defaultName()}）だけを持つ。
 * 設計書: README §6 / 03_business_logic.md §10。</p>
 *
 * <p>{@link #defaultName()} は <b>FE 表示用ではなく</b>、ユーザーが既に同義ラベルを手作成している場合の
 * 重複提案抑制（名寄せ）に使う BE 内部の既定値（ja）。FE の i18n 表示名とは独立。</p>
 */
public enum InboxLabelSuggestion {

    /** 「要返信」（メンションへの反応を促す）。既定色 #2563EB（青）。 */
    REPLY_NEEDED("#2563EB", "要返信"),

    /** 「要対応」（緊急/重要な確認必須・緊急通知）。既定色 #DC2626（赤）。 */
    ACTION_NEEDED("#DC2626", "要対応"),

    /** 「期限切れ」（緊急＝期限超過の TODO）。既定色 #EA580C（橙）。 */
    URGENT("#EA580C", "期限切れ"),

    /** 「あとで読む」（お知らせ系）。既定色 #6B7280（灰）。 */
    READ_LATER("#6B7280", "あとで読む");

    /** 既定の表示色（#RRGGBB・FE が初期色として利用。ユーザーは後で変更可）。 */
    private final String defaultColor;

    /** 名寄せ用の既定ラベル名（ja・BE 内部の抑制判定にのみ使用。FE 表示には使わない）。 */
    private final String defaultName;

    InboxLabelSuggestion(String defaultColor, String defaultName) {
        this.defaultColor = defaultColor;
        this.defaultName = defaultName;
    }

    /** 既定の表示色（#RRGGBB）を返す。 */
    public String defaultColor() {
        return defaultColor;
    }

    /** 名寄せ用の既定ラベル名（ja）を返す。 */
    public String defaultName() {
        return defaultName;
    }
}
