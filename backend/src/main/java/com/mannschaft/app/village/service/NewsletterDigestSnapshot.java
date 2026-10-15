package com.mannschaft.app.village.service;

import java.util.List;
import java.util.Map;

/**
 * 村ニュースレター号の凍結ダイジェスト snapshot（F17.1 ②-2・設計書 §5.2）。
 *
 * <p>{@link VillageNewsletterDigestAggregator#aggregate} が任意の {@code [periodStart, periodEnd)}
 * について集計した値を持つ不変レコード。{@link VillageNewsletterIssueService} がこの値を
 * {@code @SuperBuilder} 経由で号エンティティの {@code digest_*} カラムへ複写し、凍結する。</p>
 *
 * @param postCount      掲示板スレッド数 + タイムライン投稿数
 * @param newMemberCount 新規参加メンバー数
 * @param festivalCount  期間内に作成されたお祭り数（設計書 §5.3）
 * @param meetupCount    期間内に作成された寄合数（設計書 §5.3）
 * @param recruitCount   期間内に作成された募集数（設計書 §5.3）
 * @param top3Topics     TOP3 トピック（{@code VillageChronicleService#extractTop3Topics} 流用・count 降順）
 */
public record NewsletterDigestSnapshot(
        int postCount,
        int newMemberCount,
        int festivalCount,
        int meetupCount,
        int recruitCount,
        List<Map.Entry<String, Integer>> top3Topics) {
}
