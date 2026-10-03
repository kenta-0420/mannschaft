package com.mannschaft.app.village.entity.enums;

/**
 * 村ニュースレター号のライフサイクル状態（F17.1 ②-1・案Y）。
 *
 * <p>定期便は {@code AGGREGATED → FROZEN → PUBLISHED} と遷移する。
 * FROZEN 以降はダイジェスト snapshot（{@code digest_*}）が改ざん不可になる（設計書 §4.2）。
 * 号外は集計・凍結・ラグを経ず、生成時から {@code PUBLISHED} で作られる。</p>
 */
public enum VillageNewsletterIssueStatus {

    /** 集計完了（凍結前）。この状態でのみダイジェスト値を確定できる。 */
    AGGREGATED,

    /** 凍結済み（ダイジェスト snapshot は以後書き換え不可）。配信待ち。 */
    FROZEN,

    /** 配信済み。 */
    PUBLISHED,

    /** 取消（配信されずに終わった号）。 */
    CANCELED
}
