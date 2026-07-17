package com.mannschaft.app.village.service;

import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import com.mannschaft.app.village.repository.VillageMatchRecruitRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 村ニュースレターの凍結ダイジェスト集計器（F17.1 ②-2・設計書 §5.2）。
 *
 * <p>村史の月次固定集計（{@link VillageChronicleService#generateForVillage}）を、
 * <b>任意の {@code [periodStart, periodEnd)}</b> を受け取れるよう一般化したもの。
 * 掲示板・タイムライン・新メンバー・お祭り・寄合・募集の件数と、掲示板タイトルからの
 * TOP3 トピックを 1 回の集計で {@link NewsletterDigestSnapshot} に束ねる。</p>
 *
 * <h2>TOP3 トピックの流用（設計書 §5.2 / AC-04）</h2>
 * <p>TOP3 抽出は <b>村史の {@link VillageChronicleService#extractTop3Topics} をそのまま呼ぶ</b>。
 * ロジックを重複実装せず、村史側の挙動も 1 ミリも変えない（同一パッケージのため呼び出し可能）。</p>
 *
 * <h2>本ヘルパは {@code @Transactional} を付けない（D-3 越境トランザクション回避）</h2>
 * <p>集計バッチ（{@link com.mannschaft.app.village.batch.VillageNewsletterAggregateBatchService}）は、
 * トランザクションを開始する<b>前</b>に本ヘルパで集計を確定させ、その結果 snapshot だけを村ドメインの
 * トランザクション（{@link VillageNewsletterIssueService#freezeIssue}）へ渡す。これにより掲示板/
 * タイムライン等の<b>他ドメイン読み取りが村の書き込みトランザクションに含まれず</b>、
 * どの取引も複数ドメインをまたがない＝アーキテクチャ番人 D-3（越境 {@code @Transactional}）を
 * <b>構造的に回避</b>する（本クラスに {@code @Transactional} が無いので越境読み取りは autocommit）。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則5: 集計は read-only 呼出のみ。掲示板/タイムラインは他ドメインだが読み取り専用で越境する。
 *       将来は VillagePostCreatedEvent 駆動のカウンタ表へ分離し越境自体を解消する
 *       （設計書 §4.6・村史と同じ TODO）。</li>
 *   <li>タイムゾーン: UTC 固定（村史と同じ。村ローカル TZ は将来 Phase）。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class VillageNewsletterDigestAggregator {

    // TODO: 将来は VillagePostCreatedEvent を購読するカウンタテーブルへ分離し、read-only 越境を解消する（原則5）。
    private final BulletinThreadRepository bulletinThreadRepository;
    private final TimelinePostRepository timelinePostRepository;
    private final VillageMembershipRepository membershipRepository;
    private final VillageFestivalRepository festivalRepository;
    private final VillageMeetupRepository meetupRepository;
    private final VillageMatchRecruitRepository matchRecruitRepository;
    /** TOP3 トピック抽出は村史のロジックを流用する（重複実装禁止・AC-04）。 */
    private final VillageChronicleService chronicleService;

    /**
     * 指定村・指定期間 {@code [from, to)} のダイジェストを集計する。
     *
     * @param villageId 村 ID
     * @param from      集計期間の開始（含む）
     * @param to        集計期間の終了（含まない）
     * @return 集計結果 snapshot
     */
    public NewsletterDigestSnapshot aggregate(UUID villageId, LocalDateTime from, LocalDateTime to) {
        long bulletinCount = bulletinThreadRepository
                .countByVillageIdAndCreatedAtBetween(villageId, from, to);
        long timelineCount = timelinePostRepository
                .countByVillageIdAndCreatedAtBetween(villageId, from, to);
        long newMembers = membershipRepository
                .countByVillageIdAndJoinedAtBetween(villageId, from, to);
        long festivalCount = festivalRepository
                .countByVillageIdAndCreatedAtBetweenAndDeletedAtIsNull(villageId, from, to);
        long meetupCount = meetupRepository
                .countByVillageIdAndCreatedAtBetweenAndDeletedAtIsNull(villageId, from, to);
        long recruitCount = matchRecruitRepository
                .countByVillageIdAndCreatedAtBetweenAndDeletedAtIsNull(villageId, from, to);

        List<String> titles = bulletinThreadRepository
                .findTitlesByVillageIdAndCreatedAtBetween(villageId, from, to);
        List<Map.Entry<String, Integer>> top3 = chronicleService.extractTop3Topics(titles);

        return new NewsletterDigestSnapshot(
                clampToInt(bulletinCount + timelineCount),
                clampToInt(newMembers),
                clampToInt(festivalCount),
                clampToInt(meetupCount),
                clampToInt(recruitCount),
                top3);
    }

    /** long のカウントを Integer カラム範囲に丸める（村史 generateForVillage と同じ作法）。 */
    private static int clampToInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }
}
