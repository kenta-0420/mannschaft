package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.residencestatus.entity.AnnualReview;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.16 {@link AnnualReviewRepository} の統合テスト。
 */
@Transactional
@DisplayName("AnnualReviewRepository 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AnnualReviewRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private AnnualReviewRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_ID = 9201L;

    private AnnualReview persistReview(Integer year, LocalDateTime deadline, LocalDateTime closedAt) {
        AnnualReview e = AnnualReview.builder()
                .organizationId(ORG_ID)
                .reviewYear(year)
                .startedAt(LocalDateTime.now())
                .deadlineAt(deadline)
                .closedAt(closedAt)
                .targetCount(10)
                .responseCount(0)
                .createdBy(1L)
                .build();
        em.persist(e);
        em.flush();
        em.clear();
        return e;
    }

    @Test
    @DisplayName("組織×年度で一意な年次キャンペーンを取得できる")
    void findByOrganizationIdAndReviewYear_取得できる() {
        persistReview(2026, LocalDateTime.now().plusDays(7), null);

        Optional<AnnualReview> result =
                repository.findByOrganizationIdAndReviewYearAndDeletedAtIsNull(ORG_ID, 2026);

        assertThat(result).isPresent();
        assertThat(result.get().getReviewYear()).isEqualTo(2026);
        assertThat(result.get().getTargetCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("締切過ぎ未クローズのキャンペーンのみ取得できる（締切バッチ用）")
    void 締切過ぎ未クローズ取得() {
        LocalDateTime past = LocalDateTime.now().minusDays(1);
        LocalDateTime future = LocalDateTime.now().plusDays(7);

        persistReview(2024, past, null);                       // 締切過ぎ未クローズ → ヒット
        persistReview(2025, past, LocalDateTime.now());        // 締切過ぎクローズ済み → 除外
        persistReview(2026, future, null);                     // 締切未到来 → 除外

        List<AnnualReview> list =
                repository.findByDeadlineAtLessThanEqualAndClosedAtIsNullAndDeletedAtIsNull(LocalDateTime.now());

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getReviewYear()).isEqualTo(2024);
    }

    @Test
    @DisplayName("論理削除後は findByOrganizationIdAndReviewYear で見えなくなる")
    void 論理削除後は見えない() {
        AnnualReview saved = persistReview(2026, LocalDateTime.now().plusDays(7), null);

        AnnualReview managed = em.find(AnnualReview.class, saved.getId());
        managed.softDelete();
        em.flush();
        em.clear();

        Optional<AnnualReview> result =
                repository.findByOrganizationIdAndReviewYearAndDeletedAtIsNull(ORG_ID, 2026);
        assertThat(result).isEmpty();
    }
}
