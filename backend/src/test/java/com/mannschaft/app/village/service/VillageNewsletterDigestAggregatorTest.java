package com.mannschaft.app.village.service;

import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import com.mannschaft.app.village.repository.VillageFestivalRepository;
import com.mannschaft.app.village.repository.VillageMatchRecruitRepository;
import com.mannschaft.app.village.repository.VillageMeetupRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link VillageNewsletterDigestAggregator} 単体テスト（F17.1 ②-2・設計書 §11.1）。
 *
 * <p>集計器が各リポジトリのカウントを snapshot に正しく束ねること、および
 * TOP3 トピックが村史の {@link VillageChronicleService#extractTop3Topics} と同一結果になること
 * （＝ロジック流用の回帰・AC-04）を検証する。</p>
 *
 * <h3>流用元の結線</h3>
 * <p>{@link VillageChronicleService} は <b>実インスタンス</b>（依存は不要なため全 null）を使う。
 * {@code extractTop3Topics} は静的なパターンのみで動く純メソッドのため、リポジトリ無しでも呼べる。
 * これにより「集計器が村史と<em>同じロジック</em>を通す」ことを実物で担保する。</p>
 *
 * <h3>受け入れ条件との対応</h3>
 * <ul>
 *   <li>AC-01（構成要素）: postCount = 掲示板 + タイムライン、newMemberCount = メンバー</li>
 *   <li>AC-05（構成要素）: festival/meetup/recruit のカウントが snapshot に載る</li>
 *   <li>AC-04: TOP3 が {@code VillageChronicleService.extractTop3Topics} と同一結果</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VillageNewsletterDigestAggregator 単体テスト（F17.1 ②-2）")
class VillageNewsletterDigestAggregatorTest {

    private static final UUID VILLAGE_ID = UUID.fromString("01956c00-0000-7000-8000-000000000c01");
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 6, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 6, 8, 0, 0);

    @Mock
    private BulletinThreadRepository bulletinThreadRepository;
    @Mock
    private TimelinePostRepository timelinePostRepository;
    @Mock
    private VillageMembershipRepository membershipRepository;
    @Mock
    private VillageFestivalRepository festivalRepository;
    @Mock
    private VillageMeetupRepository meetupRepository;
    @Mock
    private VillageMatchRecruitRepository matchRecruitRepository;

    /** 流用元（実インスタンス・依存不要）。extractTop3Topics は純メソッド。 */
    private final VillageChronicleService chronicleService =
            new VillageChronicleService(null, null, null, null, null, null, null);

    private VillageNewsletterDigestAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = new VillageNewsletterDigestAggregator(
                bulletinThreadRepository, timelinePostRepository, membershipRepository,
                festivalRepository, meetupRepository, matchRecruitRepository, chronicleService);
    }

    private void stubCounts(long bulletin, long timeline, long members,
                            long festival, long meetup, long recruit) {
        given(bulletinThreadRepository.countByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class))).willReturn(bulletin);
        given(timelinePostRepository.countByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class))).willReturn(timeline);
        given(membershipRepository.countByVillageIdAndJoinedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class))).willReturn(members);
        given(festivalRepository.countByVillageIdAndCreatedAtBetweenAndDeletedAtIsNull(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class))).willReturn(festival);
        given(meetupRepository.countByVillageIdAndCreatedAtBetweenAndDeletedAtIsNull(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class))).willReturn(meetup);
        given(matchRecruitRepository.countByVillageIdAndCreatedAtBetweenAndDeletedAtIsNull(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class))).willReturn(recruit);
    }

    @Test
    @DisplayName("AC-01/05: 各リポジトリのカウントが snapshot に正しく束ねられる（post=掲示板+タイムライン）")
    void aggregate_bundlesAllCounts() {
        stubCounts(12L, 8L, 3L, 2L, 1L, 4L);
        given(bulletinThreadRepository.findTitlesByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(List.of());

        NewsletterDigestSnapshot snap = aggregator.aggregate(VILLAGE_ID, FROM, TO);

        assertThat(snap.postCount()).isEqualTo(20);        // 12 + 8
        assertThat(snap.newMemberCount()).isEqualTo(3);
        assertThat(snap.festivalCount()).isEqualTo(2);
        assertThat(snap.meetupCount()).isEqualTo(1);
        assertThat(snap.recruitCount()).isEqualTo(4);
        assertThat(snap.top3Topics()).isEmpty();
    }

    @Test
    @DisplayName("AC-04: TOP3 トピックは VillageChronicleService.extractTop3Topics と同一結果（流用の回帰）")
    void aggregate_top3MatchesChronicleLogic() {
        List<String> titles = List.of(
                "夏祭り お知らせ",
                "夏祭り 準備会",
                "夏祭り 報告",
                "清掃 案内",
                "清掃 結果");
        stubCounts(5L, 0L, 0L, 0L, 0L, 0L);
        given(bulletinThreadRepository.findTitlesByVillageIdAndCreatedAtBetween(
                eq(VILLAGE_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                .willReturn(titles);

        NewsletterDigestSnapshot snap = aggregator.aggregate(VILLAGE_ID, FROM, TO);

        // 流用元の実ロジックが返す結果と 1:1 で一致すること（重複実装していないことの回帰柵）
        List<Map.Entry<String, Integer>> expected = chronicleService.extractTop3Topics(titles);
        assertThat(snap.top3Topics()).isEqualTo(expected);
        // 期待値の中身も明示（"夏祭り"=3 が TOP1）
        assertThat(snap.top3Topics().get(0).getKey()).isEqualTo("夏祭り");
        assertThat(snap.top3Topics().get(0).getValue()).isEqualTo(3);
    }
}
