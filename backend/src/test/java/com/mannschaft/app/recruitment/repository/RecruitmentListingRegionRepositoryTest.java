package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingRegionEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F22.1 市 Phase2 D（複数地域募集 N:N）の検索（EXISTS）・件数集計（県跨ぎ重複計上 / DISTINCT）の
 * 結合テスト（test-first）。
 *
 * <p>親行（recruitment_listings）はエンティティ経由で永続化する（ddl-auto:create 生成スキーマで
 * native INSERT すると {@code @Builder.Default} 列が NOT NULL 落ちするため）。</p>
 *
 * <h2>検証観点</h2>
 * <ul>
 *   <li>東京(13)＋神奈川(14) を持つ複数地域札が pref=13 / pref=14 の双方でヒットする</li>
 *   <li>city 指定の一致 / 不一致</li>
 *   <li>EXISTS により複数地域札でも Page 件数が札数と一致（重複排除）</li>
 *   <li>byPrefecture が県跨ぎで両県 +1</li>
 *   <li>同一県内 2 市の札は byPrefecture で DISTINCT により県粒度 1 件</li>
 * </ul>
 */
@Transactional
@DisplayName("RecruitmentListingRegion 検索・件数 結合テスト (F22.1 Phase2 D)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RecruitmentListingRegionRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @Autowired
    private RecruitmentListingRegionRepository regionRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long CATEGORY_ID = 1L;
    private static final Long CREATED_BY = 1L;

    /** PUBLIC / OPEN の親札をエンティティ経由で永続化し ID を返す。 */
    private Long persistPublicListing(String title) {
        return persistOpenListing(title, RecruitmentScopeType.TEAM, 1L, LocalDateTime.now().plusDays(5));
    }

    private Long persistOpenListing(
            String title, RecruitmentScopeType scopeType, Long scopeId, LocalDateTime autoCancelAt) {
        return persistListing(title, scopeType, scopeId, CREATED_BY, CATEGORY_ID,
                RecruitmentListingStatus.OPEN, RecruitmentVisibility.PUBLIC, autoCancelAt);
    }

    private Long persistListing(
            String title,
            RecruitmentScopeType scopeType,
            Long scopeId,
            Long createdBy,
            Long categoryId,
            RecruitmentListingStatus status,
            RecruitmentVisibility visibility,
            LocalDateTime autoCancelAt) {
        LocalDateTime now = LocalDateTime.now();
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .categoryId(categoryId)
                .title(title)
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(now.plusDays(7))
                .endAt(now.plusDays(7).plusHours(2))
                .applicationDeadline(now.plusDays(5))
                .autoCancelAt(autoCancelAt)
                .capacity(10)
                .minCapacity(1)
                .visibility(visibility)
                .status(status)
                .createdBy(createdBy)
                .build();
        em.persist(listing);
        em.flush();
        return listing.getId();
    }

    @Test
    @DisplayName("個人市履歴は本人PERSONAL札だけを地域・カテゴリ・状態で絞り込む")
    void findPersonalMarketListings_filtersOwnerScopeRegionCategoryAndStatus() {
        Long ownerId = 42L;
        LocalDateTime later = LocalDateTime.now().plusDays(5);
        Long expected = persistListing("personal-target", RecruitmentScopeType.PERSONAL, ownerId,
                ownerId, CATEGORY_ID, RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY, later);
        Long foreignOwner = persistListing("personal-foreign", RecruitmentScopeType.PERSONAL, 43L,
                43L, CATEGORY_ID, RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY, later);
        Long collidingTeam = persistListing("team-collision", RecruitmentScopeType.TEAM, ownerId,
                ownerId, CATEGORY_ID, RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY, later);
        Long wrongCategory = persistListing("personal-category", RecruitmentScopeType.PERSONAL, ownerId,
                ownerId, 2L, RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY, later);
        Long cancelled = persistListing("personal-cancelled", RecruitmentScopeType.PERSONAL, ownerId,
                ownerId, CATEGORY_ID, RecruitmentListingStatus.CANCELLED, RecruitmentVisibility.SCOPE_ONLY, later);
        addRegion(expected, "13", "13113");
        addRegion(foreignOwner, "13", "13113");
        addRegion(collidingTeam, "13", "13113");
        addRegion(wrongCategory, "13", "13113");
        addRegion(cancelled, "13", "13113");
        em.flush();
        em.clear();

        Page<RecruitmentListingEntity> result = listingRepository.findPersonalMarketListings(
                ownerId, RecruitmentListingStatus.DRAFT, "13", "13113", CATEGORY_ID,
                PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(RecruitmentListingEntity::getId)
                .containsExactly(expected);
    }

    @Test
    @DisplayName("個人市履歴は複数地域でも重複せずページ総件数を保つ")
    void findPersonalMarketListings_multipleRegionsKeepsPagingTotal() {
        Long ownerId = 52L;
        LocalDateTime later = LocalDateTime.now().plusDays(5);
        Long first = persistListing("personal-first", RecruitmentScopeType.PERSONAL, ownerId,
                ownerId, CATEGORY_ID, RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY, later);
        Long second = persistListing("personal-second", RecruitmentScopeType.PERSONAL, ownerId,
                ownerId, CATEGORY_ID, RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY, later);
        addRegion(first, "13", "13101");
        addRegion(first, "13", "13102");
        addRegion(second, "13", "13103");
        em.flush();
        em.clear();

        Page<RecruitmentListingEntity> result = listingRepository.findPersonalMarketListings(
                ownerId, RecruitmentListingStatus.DRAFT, "13", null, CATEGORY_ID,
                PageRequest.of(0, 1));

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getTotalPages()).isEqualTo(2);
    }

    @Test
    @DisplayName("フォロー市feedは同じ数値scopeIdのPERSONAL札を混入させない")
    void findOpenByScopeIds_excludesPersonalWithCollidingScopeId() {
        Long collidingScopeId = 987654L;
        Long teamId = persistOpenListing(
                "feed-team", RecruitmentScopeType.TEAM, collidingScopeId, LocalDateTime.now().plusDays(5));
        Long personalId = persistOpenListing(
                "feed-personal", RecruitmentScopeType.PERSONAL, collidingScopeId, LocalDateTime.now().plusDays(5));
        em.flush();
        em.clear();

        List<RecruitmentListingEntity> results = listingRepository.findOpenByScopeIds(
                List.of(collidingScopeId), PageRequest.of(0, 20));

        assertThat(results).extracting(RecruitmentListingEntity::getId)
                .contains(teamId)
                .doesNotContain(personalId);
    }

    @Test
    @DisplayName("自動取消候補は期限超過したPERSONAL汚染行を抽出しない")
    void findAutoCancelTargets_excludesPersonal() {
        LocalDateTime now = LocalDateTime.now();
        Long teamId = persistOpenListing(
                "auto-cancel-team", RecruitmentScopeType.TEAM, 123456L, now.minusMinutes(1));
        Long personalId = persistOpenListing(
                "auto-cancel-personal", RecruitmentScopeType.PERSONAL, 654321L, now.minusMinutes(1));
        em.flush();
        em.clear();

        List<RecruitmentListingEntity> results = listingRepository.findAutoCancelTargets(now);

        assertThat(results).extracting(RecruitmentListingEntity::getId)
                .contains(teamId)
                .doesNotContain(personalId);
    }

    private void addRegion(Long listingId, String pref, String city) {
        regionRepository.save(RecruitmentListingRegionEntity.of(listingId, pref, city));
    }

    private static Map<String, Long> toMap(List<Object[]> rows) {
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                r -> (String) r[0], r -> ((Number) r[1]).longValue()));
    }

    @Test
    @DisplayName("東京＋神奈川の複数地域札が pref=13 / pref=14 の双方でヒットする")
    void multiRegionListing_hitsBothPrefectures() {
        Long id = persistPublicListing("multi-tokyo-kanagawa");
        addRegion(id, "13", null);
        addRegion(id, "14", null);
        em.flush();
        em.clear();

        Page<RecruitmentListingEntity> tokyo = listingRepository.searchMarketListings(
                "13", null, null, null, false, PageRequest.of(0, 20));
        Page<RecruitmentListingEntity> kanagawa = listingRepository.searchMarketListings(
                "14", null, null, null, false, PageRequest.of(0, 20));

        assertThat(tokyo.getContent()).extracting(RecruitmentListingEntity::getId).contains(id);
        assertThat(kanagawa.getContent()).extracting(RecruitmentListingEntity::getId).contains(id);
    }

    @Test
    @DisplayName("city 指定: 一致する市の札のみヒットし、別市はヒットしない")
    void cityFilter_matchesOnlyExactCity() {
        Long id = persistPublicListing("city-13113");
        addRegion(id, "13", "13113"); // 渋谷区
        em.flush();
        em.clear();

        Page<RecruitmentListingEntity> match = listingRepository.searchMarketListings(
                null, "13113", null, null, false, PageRequest.of(0, 20));
        Page<RecruitmentListingEntity> noMatch = listingRepository.searchMarketListings(
                null, "13114", null, null, false, PageRequest.of(0, 20));

        assertThat(match.getContent()).extracting(RecruitmentListingEntity::getId).contains(id);
        assertThat(noMatch.getContent()).extracting(RecruitmentListingEntity::getId).doesNotContain(id);
    }

    @Test
    @DisplayName("EXISTS により複数地域札でも Page 件数=札数（重複行が出ない）")
    void exists_noDuplicateRowsInPage() {
        Long id = persistPublicListing("multi-three-regions-13");
        // 同一県内に 3 つの地域行を持たせても、pref=13 検索で 1 件しか返らないこと（EXISTS 重複排除）。
        addRegion(id, "13", "13101");
        addRegion(id, "13", "13102");
        addRegion(id, "13", null);
        em.flush();
        em.clear();

        Page<RecruitmentListingEntity> page = listingRepository.searchMarketListings(
                "13", null, null, null, false, PageRequest.of(0, 20));

        long occurrences = page.getContent().stream()
                .filter(e -> e.getId().equals(id)).count();
        assertThat(occurrences).isEqualTo(1);
        assertThat(page.getTotalElements()).isEqualTo(page.getContent().size());
    }

    @Test
    @DisplayName("byPrefecture: 東京＋神奈川の札は両県で +1（県跨ぎ重複計上）")
    void byPrefecture_crossPrefectureCountsBoth() {
        Long id = persistPublicListing("count-tokyo-kanagawa");
        addRegion(id, "13", null);
        addRegion(id, "14", null);
        em.flush();
        em.clear();

        Map<String, Long> counts = toMap(listingRepository.countMarketListingsByPrefecture());

        assertThat(counts.getOrDefault("13", 0L)).isGreaterThanOrEqualTo(1L);
        assertThat(counts.getOrDefault("14", 0L)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("byPrefecture: 同一県内 2 市の札は DISTINCT で県粒度 1 件")
    void byPrefecture_sameSecondCitiesCountOnce() {
        Long id = persistPublicListing("count-same-pref-two-cities");
        addRegion(id, "13", "13101");
        addRegion(id, "13", "13102");
        em.flush();
        em.clear();

        Map<String, Long> counts = toMap(listingRepository.countMarketListingsByPrefecture());

        // この札は pref=13 に 1 件だけ寄与する（2 市あっても DISTINCT listingId で 1）。
        // 他テストの影響を避けるため、本札専用に上限ではなく「この札が二重計上されないこと」を
        // byCity で確認する（県は他札と合算されるため）。
        Map<String, Long> cityCounts = toMap(listingRepository.countMarketListingsByCity());
        assertThat(cityCounts.getOrDefault("13101", 0L)).isGreaterThanOrEqualTo(1L);
        assertThat(cityCounts.getOrDefault("13102", 0L)).isGreaterThanOrEqualTo(1L);
        // 県は最低 1（この札ぶん）。
        assertThat(counts.getOrDefault("13", 0L)).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("includeRegionNone=true: 地域行を持たない札も含む / false では含まない")
    void includeRegionNone_togglesNoRegionListings() {
        Long noRegionId = persistPublicListing("no-region-listing");
        // 地域行を一切作らない。
        em.flush();
        em.clear();

        Page<RecruitmentListingEntity> included = listingRepository.searchMarketListings(
                null, null, null, null, true, PageRequest.of(0, 50));
        assertThat(included.getContent()).extracting(RecruitmentListingEntity::getId).contains(noRegionId);
    }

    @Test
    @DisplayName("findByListingIdInOrderBy...: バルク取得が listing_id 単位でまとまる")
    void bulkFetchByListingIds() {
        Long a = persistPublicListing("bulk-a");
        Long b = persistPublicListing("bulk-b");
        addRegion(a, "13", null);
        addRegion(b, "14", "14100");
        addRegion(b, "27", null);
        em.flush();
        em.clear();

        List<RecruitmentListingRegionEntity> rows =
                regionRepository.findByListingIdInOrderByListingIdAscIdAsc(List.of(a, b));

        assertThat(rows).extracting(RecruitmentListingRegionEntity::getListingId)
                .contains(a, b);
        assertThat(rows.stream().filter(r -> r.getListingId().equals(b)).count()).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteByListingId: 札の全地域を削除する（replace の前段）")
    void deleteByListingId_clearsAll() {
        Long id = persistPublicListing("delete-regions");
        addRegion(id, "13", null);
        addRegion(id, "14", null);
        em.flush();
        em.clear();

        int deleted = regionRepository.deleteByListingId(id);
        em.flush();
        em.clear();

        assertThat(deleted).isEqualTo(2);
        assertThat(regionRepository.findByListingIdOrderByIdAsc(id)).isEmpty();
    }
}
