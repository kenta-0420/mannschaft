package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.residencestatus.entity.ResidentActivitySnapshot;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.16 {@link ResidentActivitySnapshotRepository} の統合テスト。
 */
@Transactional
@DisplayName("ResidentActivitySnapshotRepository 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ResidentActivitySnapshotRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ResidentActivitySnapshotRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_ID = 9001L;
    private static final Long DWELLING_ID = 5001L;
    private static final Long REGISTRY_ID = 6001L;
    private static final Long USER_ID = 7001L;

    private ResidentActivitySnapshot persistSnapshot(LocalDate snapshotDate, int score) {
        ResidentActivitySnapshot e = ResidentActivitySnapshot.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(DWELLING_ID)
                .residentRegistryId(REGISTRY_ID)
                .subjectUserId(USER_ID)
                .snapshotDate(snapshotDate)
                .activityScoreTotal(score)
                .activityBreakdownJson("{\"login\":1}")
                .build();
        em.persist(e);
        em.flush();
        em.clear();
        return e;
    }

    @Test
    @DisplayName("subjectUserId + snapshotDate で一意取得できる")
    void findBySubjectUserIdAndSnapshotDate_取得できる() {
        LocalDate today = LocalDate.now();
        persistSnapshot(today, 5);

        Optional<ResidentActivitySnapshot> result =
                repository.findBySubjectUserIdAndSnapshotDateAndDeletedAtIsNull(USER_ID, today);

        assertThat(result).isPresent();
        assertThat(result.get().getActivityScoreTotal()).isEqualTo(5);
    }

    @Test
    @DisplayName("resident_registry_id で直近順 snapshot を取得できる")
    void findByResidentRegistryId_新しい順で取得() {
        persistSnapshot(LocalDate.now().minusDays(2), 3);
        persistSnapshot(LocalDate.now().minusDays(1), 4);
        persistSnapshot(LocalDate.now(), 5);

        List<ResidentActivitySnapshot> list =
                repository.findByResidentRegistryIdAndDeletedAtIsNullOrderBySnapshotDateDesc(REGISTRY_ID);

        assertThat(list).hasSize(3);
        assertThat(list.get(0).getActivityScoreTotal()).isEqualTo(5);
        assertThat(list.get(2).getActivityScoreTotal()).isEqualTo(3);
    }

    @Test
    @DisplayName("論理削除後は findBySubjectUserId* で見えなくなる")
    void 論理削除後は見えない() {
        LocalDate today = LocalDate.now();
        ResidentActivitySnapshot saved = persistSnapshot(today, 5);

        ResidentActivitySnapshot managed = em.find(ResidentActivitySnapshot.class, saved.getId());
        managed.softDelete();
        em.flush();
        em.clear();

        Optional<ResidentActivitySnapshot> result =
                repository.findBySubjectUserIdAndSnapshotDateAndDeletedAtIsNull(USER_ID, today);
        assertThat(result).isEmpty();
    }
}
