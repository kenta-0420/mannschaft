package com.mannschaft.app.succession.repository;

import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.15 S1-A {@link DelinquencyEscalationRepository} 結合テスト。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.7</p>
 */
@Transactional
@DisplayName("DelinquencyEscalationRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DelinquencyEscalationRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private DelinquencyEscalationRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_A = 9401L;
    private static final Long ORG_B = 9402L;
    private static final Long DWELLING = 11_401L;

    private DelinquencyEscalationEntity persistEscalation(
            Long organizationId, Long residentRegistryId, String stage) {
        DelinquencyEscalationEntity entity = DelinquencyEscalationEntity.builder()
                .organizationId(organizationId)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(residentRegistryId)
                .currentStage(stage)
                .delinquencyStartedAt(LocalDate.of(2026, 1, 1))
                .build();
        em.persist(entity);
        em.flush();
        em.clear();
        return entity;
    }

    @Test
    @DisplayName("保存_主要フィールドが永続化される")
    void 保存_主要フィールドが永続化される() {
        DelinquencyEscalationEntity saved =
                persistEscalation(ORG_A, 12_401L, "STAGE_1_REMINDER");

        DelinquencyEscalationEntity found =
                em.find(DelinquencyEscalationEntity.class, saved.getId());
        assertThat(found).isNotNull();
        assertThat(found.getOrganizationId()).isEqualTo(ORG_A);
        assertThat(found.getCurrentStage()).isEqualTo("STAGE_1_REMINDER");
        assertThat(found.getDelinquencyStartedAt()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    @DisplayName("UNIQUE制約_1居住者1エスカ")
    void UNIQUE制約_1居住者1エスカ() {
        Long residentId = 12_402L;
        persistEscalation(ORG_A, residentId, "STAGE_1_REMINDER");

        assertThatThrownBy(() -> persistEscalation(ORG_A, residentId, "STAGE_2_EMERGENCY_CONTACT"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CHECK制約_current_stage_不正値は例外")
    void CHECK制約_current_stage_不正値は例外() {
        DelinquencyEscalationEntity entity = DelinquencyEscalationEntity.builder()
                .organizationId(ORG_A)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(12_403L)
                .currentStage("INVALID_STAGE")
                .delinquencyStartedAt(LocalDate.of(2026, 1, 1))
                .build();
        assertThatThrownBy(() -> {
            em.persist(entity);
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByCurrentStage_進行中のもののみ抽出")
    void findByCurrentStage_進行中のもののみ抽出() {
        persistEscalation(ORG_A, 12_404L, "STAGE_3_WATCHER_VISIT");
        // 凍結中は除外
        DelinquencyEscalationEntity frozen =
                persistEscalation(ORG_A, 12_405L, "STAGE_3_WATCHER_VISIT");
        DelinquencyEscalationEntity managed1 =
                em.find(DelinquencyEscalationEntity.class, frozen.getId());
        managed1.setFrozenAt(java.time.LocalDateTime.now());
        em.flush();
        // 解決済みも除外
        DelinquencyEscalationEntity resolved =
                persistEscalation(ORG_A, 12_406L, "STAGE_3_WATCHER_VISIT");
        DelinquencyEscalationEntity managed2 =
                em.find(DelinquencyEscalationEntity.class, resolved.getId());
        managed2.setResolvedAt(java.time.LocalDateTime.now());
        em.flush();
        em.clear();

        List<DelinquencyEscalationEntity> targets =
                repository.findByCurrentStageAndFrozenAtIsNullAndResolvedAtIsNullAndDeletedAtIsNull(
                        "STAGE_3_WATCHER_VISIT");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).getResidentRegistryId()).isEqualTo(12_404L);
    }

    @Test
    @DisplayName("organization_id_別組織のレコードは見えない")
    void organization_id_別組織のレコードは見えない() {
        DelinquencyEscalationEntity saved =
                persistEscalation(ORG_A, 12_407L, "STAGE_1_REMINDER");

        Optional<DelinquencyEscalationEntity> visibleFromOther =
                repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_B);

        assertThat(visibleFromOther).isEmpty();
    }
}
