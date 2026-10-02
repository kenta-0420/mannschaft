package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.ApplyToRecruitmentRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.event.MarketListingReachedFullEvent;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F22.1 市: 最終認証連携（🔴-1 一次キャッシュ汚染根治）の結合テスト。
 *
 * <p>検分指摘 🔴-1: native {@code @Modifying} UPDATE（{@code incrementConfirmedAtomic}）後に
 * 永続化コンテキストへ古い {@code OPEN} エンティティが残ると {@code findById} がそれを返し、
 * {@code reachedFull} が常に false → 確認通知が永久に飛ばない。
 * {@code @Modifying(clearAutomatically=true, flushAutomatically=true)} で一次キャッシュを
 * クリアし、DB 確定状態を読むことで OPEN→FULL 境界を検知できることを検証する。</p>
 */
@Transactional
@RecordApplicationEvents
@DisplayName("MarketFinalize 結合テスト (F22.1 市・🔴-1 キャッシュ汚染根治)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MarketFinalizeIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private RecruitmentParticipantService participantService;

    @Autowired
    private RecruitmentListingRepository listingRepository;

    /**
     * CMP-260930-1932: FULL 到達時は最終認証通知を同期送信せず {@link MarketListingReachedFullEvent} を
     * publish する。本クラスは {@code @Transactional}（テスト終了でロールバック）で AFTER_COMMIT が発火しないため、
     * 業務TX内で観測できる契約（イベントの publish）までを検証する。配送そのものは
     * {@code MarketFinalizeNotificationTransactionIT}（実DB・AFTER_COMMIT）と
     * {@code MarketFinalizeConfirmationListenerTest} が受け持つ。
     */
    @Autowired
    private ApplicationEvents applicationEvents;

    @PersistenceContext
    private EntityManager em;

    private static final Long OWNER_TEAM_ID = 9001L;
    private static final Long APPLICANT_USER_ID = 7001L;

    /**
     * 公開（PUBLIC）募集枠を 1 件永続化する。capacity / confirmedCount を指定できる。
     * PUBLIC のため可視性ガードは任意ユーザーで通過する。
     */
    private Long persistPublicListing(String title, int capacity, int confirmedCount) {
        LocalDateTime now = LocalDateTime.now();
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(OWNER_TEAM_ID)
                .categoryId(1L)
                .title(title)
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(now.plusDays(7))
                .endAt(now.plusDays(7).plusHours(2))
                .applicationDeadline(now.plusDays(5))
                .autoCancelAt(now.plusDays(5))
                .capacity(capacity)
                .minCapacity(1)
                .confirmedCount(confirmedCount)
                .visibility(RecruitmentVisibility.PUBLIC)
                .status(RecruitmentListingStatus.OPEN)
                .createdBy(OWNER_TEAM_ID)
                .build();
        em.persist(listing);
        em.flush();
        return listing.getId();
    }

    @Test
    @DisplayName("incrementConfirmedAtomic 後の findById が DB 確定の FULL を返す（一次キャッシュ汚染なし）")
    void incrementThenFindById_returnsFullAfterCacheClear() {
        // capacity=1: この 1 件で OPEN→FULL に遷移する。
        Long id = persistPublicListing("rl-cache-1", 1, 0);

        // 一次キャッシュを汚染: findById で OPEN エンティティをコンテキストに載せる。
        RecruitmentListingEntity cached = listingRepository.findById(id).orElseThrow();
        assertThat(cached.getStatus()).isEqualTo(RecruitmentListingStatus.OPEN);

        // native UPDATE（status=CASE で FULL に遷移）。
        int updated = listingRepository.incrementConfirmedAtomic(id);
        assertThat(updated).isEqualTo(1);

        // clearAutomatically により一次キャッシュはクリア済み → DB 確定の FULL を読む。
        RecruitmentListingEntity after = listingRepository.findById(id).orElseThrow();
        assertThat(after.getStatus())
                .as("native UPDATE 後の findById は古い OPEN ではなく DB 確定の FULL を返すこと")
                .isEqualTo(RecruitmentListingStatus.FULL);
        assertThat(after.getConfirmedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("申込で OPEN→FULL に遷移したら MarketListingReachedFullEvent が1回 publish される")
    void apply_reachesFull_publishesReachedFullEvent() {
        Long id = persistPublicListing("rl-finalize-1", 1, 0);
        em.clear();

        ApplyToRecruitmentRequest request = new ApplyToRecruitmentRequest(
                RecruitmentParticipantType.USER, null, null);
        participantService.apply(id, APPLICANT_USER_ID, request);

        assertThat(applicationEvents.stream(MarketListingReachedFullEvent.class))
                .as("OPEN→FULL 境界を検知し、最終認証通知のイベントを publish する")
                .containsExactly(new MarketListingReachedFullEvent(id));
    }

    @Test
    @DisplayName("申込で FULL に達しない（定員に余裕あり）なら MarketListingReachedFullEvent は publish されない")
    void apply_doesNotReachFull_noReachedFullEvent() {
        // capacity=3, confirmed=0 → 1 件目では FULL にならない。
        Long id = persistPublicListing("rl-finalize-2", 3, 0);
        em.clear();

        ApplyToRecruitmentRequest request = new ApplyToRecruitmentRequest(
                RecruitmentParticipantType.USER, null, null);
        participantService.apply(id, APPLICANT_USER_ID, request);

        assertThat(applicationEvents.stream(MarketListingReachedFullEvent.class)).isEmpty();
    }
}
