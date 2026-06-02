package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.util.LikeEscapeUtil;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F22.1 市/公開募集検索の LIKE ワイルドカードエスケープ回帰テスト（test-first）。
 *
 * <p>keyword に {@code %} / {@code _} を含む入力が <strong>リテラルとして扱われ</strong>、
 * ワイルドカードとして全件マッチに化けない（フィルタ無効化しない）ことを検証する。
 * サービス層が {@link LikeEscapeUtil#escape} を適用した上で Repository へ渡す経路を再現し、
 * JPQL の {@code ESCAPE '\'} と対で正しくリテラル一致することを確認する。</p>
 *
 * <p>親行（recruitment_listings）はエンティティ経由で永続化する（ddl-auto:create 生成スキーマで
 * native INSERT すると {@code @Builder.Default} 列が NOT NULL 落ちするため）。</p>
 */
@Transactional
@DisplayName("市/公開募集検索 LIKE エスケープ回帰テスト (F22.1)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RecruitmentListingSearchLikeEscapeTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private RecruitmentListingRepository listingRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long CATEGORY_ID = 1L;
    private static final Long CREATED_BY = 1L;

    /** PUBLIC / OPEN の公開札をエンティティ経由で永続化し ID を返す。 */
    private Long persistPublicListing(String title, String location, String description) {
        LocalDateTime now = LocalDateTime.now();
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(1L)
                .categoryId(CATEGORY_ID)
                .title(title)
                .description(description)
                .location(location)
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(now.plusDays(7))
                .endAt(now.plusDays(7).plusHours(2))
                .applicationDeadline(now.plusDays(5))
                .autoCancelAt(now.plusDays(5))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.PUBLIC)
                .status(RecruitmentListingStatus.OPEN)
                .createdBy(CREATED_BY)
                .build();
        em.persist(listing);
        em.flush();
        return listing.getId();
    }

    @Test
    @DisplayName("searchMarketListings: keyword=100% は '100%' を含む札のみヒット（% がワイルドカードに化けない）")
    void marketSearch_percentIsLiteral() {
        Long withPercent = persistPublicListing("A100%OFFセール", null, null);
        Long withoutPercent = persistPublicListing("Bテスト100円", null, null);
        em.flush();
        em.clear();

        // サービス層と同じく blankToNull 相当 → escape を適用した keyword を渡す。
        String escaped = LikeEscapeUtil.escape("100%");
        Page<RecruitmentListingEntity> page = listingRepository.searchMarketListings(
                null, null, null, escaped, true, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(RecruitmentListingEntity::getId)
                .contains(withPercent)
                .doesNotContain(withoutPercent);
    }

    @Test
    @DisplayName("searchMarketListings: keyword=A_B は 'A_B' を含む札のみヒット（_ が任意1文字に化けない）")
    void marketSearch_underscoreIsLiteral() {
        Long literal = persistPublicListing("項目A_B掲示", null, null);
        Long wildcardVictim = persistPublicListing("項目AXB掲示", null, null);
        em.flush();
        em.clear();

        String escaped = LikeEscapeUtil.escape("A_B");
        Page<RecruitmentListingEntity> page = listingRepository.searchMarketListings(
                null, null, null, escaped, true, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(RecruitmentListingEntity::getId)
                .contains(literal)
                .doesNotContain(wildcardVictim);
    }

    @Test
    @DisplayName("searchPublicListings: keyword=100% は title '100%' 含みのみヒット（% リテラル）")
    void publicSearch_keywordPercentIsLiteral() {
        Long withPercent = persistPublicListing("A100%OFFセール", null, null);
        Long withoutPercent = persistPublicListing("Bテスト100円", null, null);
        em.flush();
        em.clear();

        String escaped = LikeEscapeUtil.escape("100%");
        Page<RecruitmentListingEntity> page = listingRepository.searchPublicListings(
                null, null, null, null, null, escaped, null, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(RecruitmentListingEntity::getId)
                .contains(withPercent)
                .doesNotContain(withoutPercent);
    }

    @Test
    @DisplayName("searchPublicListings: location=A_B は 'A_B' を含む札のみヒット（_ リテラル）")
    void publicSearch_locationUnderscoreIsLiteral() {
        Long literal = persistPublicListing("場所札L", "会場A_B", null);
        Long wildcardVictim = persistPublicListing("場所札W", "会場AXB", null);
        em.flush();
        em.clear();

        String escaped = LikeEscapeUtil.escape("A_B");
        Page<RecruitmentListingEntity> page = listingRepository.searchPublicListings(
                null, null, null, null, null, null, escaped, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(RecruitmentListingEntity::getId)
                .contains(literal)
                .doesNotContain(wildcardVictim);
    }

    @Test
    @DisplayName("searchPublicListings: keyword は description にもリテラルでマッチする")
    void publicSearch_descriptionPercentIsLiteral() {
        Long withPercent = persistPublicListing("説明札D", null, "本文に50%還元あり");
        Long withoutPercent = persistPublicListing("説明札E", null, "本文に50円還元あり");
        em.flush();
        em.clear();

        String escaped = LikeEscapeUtil.escape("50%");
        Page<RecruitmentListingEntity> page = listingRepository.searchPublicListings(
                null, null, null, null, null, escaped, null, PageRequest.of(0, 50));

        assertThat(page.getContent()).extracting(RecruitmentListingEntity::getId)
                .contains(withPercent)
                .doesNotContain(withoutPercent);
    }
}
