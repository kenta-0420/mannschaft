package com.mannschaft.app.village.entity.enums;

/**
 * 村ニュースレター号の種別（F17.1 ②-1・案Y）。
 *
 * <p>定期便（集計→凍結→ラグ→配信）か号外（自由記述の即時配信）かを判別する。
 * frequency enum を拡張せず、この列で号外を判別する（設計書 §4.2）。</p>
 */
public enum VillageNewsletterIssueType {

    /** 定期便（集計・凍結ダイジェストを持つ号）。 */
    REGULAR,

    /** 号外（ダイジェストを持たず自由記述のみを即時配信する号）。 */
    EXTRA
}
