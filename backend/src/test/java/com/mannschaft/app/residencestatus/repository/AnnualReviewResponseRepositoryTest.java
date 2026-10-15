package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.residencestatus.entity.AnnualReview;
import com.mannschaft.app.residencestatus.entity.AnnualReviewResponse;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.16 {@link AnnualReviewResponseRepository} の統合テスト。
 */
@Transactional
@DisplayName("AnnualReviewResponseRepository 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AnnualReviewResponseRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private AnnualReviewResponseRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_ID = 9301L;
    private static final Long DWELLING_ID = 5301L;

    private UUID persistAnnualReview() {
        AnnualReview review = AnnualReview.builder()
                .organizationId(ORG_ID)
                .reviewYear(2026)
                .startedAt(LocalDateTime.now())
                .deadlineAt(LocalDateTime.now().plusDays(7))
                .targetCount(5)
                .responseCount(0)
                .createdBy(1L)
                .build();
        em.persist(review);
        em.flush();
        UUID id = review.getId();
        em.clear();
        return id;
    }

    private AnnualReviewResponse persistResponse(UUID reviewId, Long residentRegistryId, String state) {
        AnnualReviewResponse e = AnnualReviewResponse.builder()
                .organizationId(ORG_ID)
                .annualReviewId(reviewId)
                .dwellingUnitId(DWELLING_ID)
                .residentRegistryId(residentRegistryId)
                .respondentUserId(7301L)
                .residenceState(state)
                .contactPhoneVerified(false)
                .contactEmailVerified(false)
                .emergencyContactVerified(false)
                .build();
        em.persist(e);
        em.flush();
        em.clear();
        return e;
    }

    @Test
    @DisplayName("キャンペーン × 居住者で一意な回答を取得できる")
    void findByAnnualReviewIdAndResidentRegistryId_取得できる() {
        UUID reviewId = persistAnnualReview();
        persistResponse(reviewId, 6301L, "OWNER_RESIDING");

        Optional<AnnualReviewResponse> result =
                repository.findByAnnualReviewIdAndResidentRegistryIdAndDeletedAtIsNull(reviewId, 6301L);

        assertThat(result).isPresent();
        assertThat(result.get().getResidenceState()).isEqualTo("OWNER_RESIDING");
    }

    @Test
    @DisplayName("キャンペーンの全回答を一覧取得できる")
    void findByAnnualReviewId_全件取得() {
        UUID reviewId = persistAnnualReview();
        persistResponse(reviewId, 6311L, "OWNER_RESIDING");
        persistResponse(reviewId, 6312L, "RENTED_OUT");
        persistResponse(reviewId, 6313L, "UNRESPONDED");

        List<AnnualReviewResponse> list =
                repository.findByAnnualReviewIdAndDeletedAtIsNull(reviewId);

        assertThat(list).hasSize(3);
    }

    @Test
    @DisplayName("residence_state での絞り込み取得ができる")
    void findByAnnualReviewIdAndResidenceState_絞り込み取得() {
        UUID reviewId = persistAnnualReview();
        persistResponse(reviewId, 6321L, "OWNER_RESIDING");
        persistResponse(reviewId, 6322L, "OWNER_RESIDING");
        persistResponse(reviewId, 6323L, "RENTED_OUT");

        List<AnnualReviewResponse> list =
                repository.findByAnnualReviewIdAndResidenceStateAndDeletedAtIsNull(reviewId, "OWNER_RESIDING");

        assertThat(list).hasSize(2);
    }
}
