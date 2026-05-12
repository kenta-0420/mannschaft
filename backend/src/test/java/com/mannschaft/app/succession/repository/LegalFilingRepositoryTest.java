package com.mannschaft.app.succession.repository;

import com.mannschaft.app.succession.entity.LegalFilingEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.15 S1-A {@link LegalFilingRepository} 結合テスト。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.8</p>
 */
@Transactional
@DisplayName("LegalFilingRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class LegalFilingRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private LegalFilingRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_A = 9501L;
    private static final Long DWELLING = 11_501L;
    private static final Long RESIDENT = 12_501L;

    private LegalFilingEntity persistFiling(String filingType) {
        LegalFilingEntity entity = LegalFilingEntity.builder()
                .organizationId(ORG_A)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(RESIDENT)
                .filingType(filingType)
                .build();
        em.persist(entity);
        em.flush();
        em.clear();
        return entity;
    }

    @Test
    @DisplayName("保存_主要フィールドが永続化される")
    void 保存_主要フィールドが永続化される() {
        LegalFilingEntity saved = persistFiling("ABSENTEE_PROPERTY_MANAGER");

        LegalFilingEntity found = em.find(LegalFilingEntity.class, saved.getId());
        assertThat(found).isNotNull();
        assertThat(found.getOrganizationId()).isEqualTo(ORG_A);
        assertThat(found.getFilingType()).isEqualTo("ABSENTEE_PROPERTY_MANAGER");
        assertThat(found.getResidentRegistryId()).isEqualTo(RESIDENT);
    }

    @Test
    @DisplayName("CHECK制約_filing_type_不正値は例外")
    void CHECK制約_filing_type_不正値は例外() {
        LegalFilingEntity entity = LegalFilingEntity.builder()
                .organizationId(ORG_A)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(RESIDENT)
                .filingType("INVALID_TYPE")
                .build();
        assertThatThrownBy(() -> {
            em.persist(entity);
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findByResidentRegistryId_複数申立を取得")
    void findByResidentRegistryId_複数申立を取得() {
        persistFiling("ABSENTEE_PROPERTY_MANAGER");
        persistFiling("INHERITANCE_LIQUIDATOR");

        List<LegalFilingEntity> all =
                repository.findByResidentRegistryIdAndDeletedAtIsNullOrderByCreatedAtDesc(RESIDENT);

        assertThat(all).hasSize(2);
        assertThat(all)
                .extracting(LegalFilingEntity::getFilingType)
                .containsExactlyInAnyOrder("ABSENTEE_PROPERTY_MANAGER", "INHERITANCE_LIQUIDATOR");
    }

    @Test
    @DisplayName("findByResidentRegistryIdAndFilingType_種別絞り込み")
    void findByResidentRegistryIdAndFilingType_種別絞り込み() {
        persistFiling("ABSENTEE_PROPERTY_MANAGER");
        persistFiling("INHERITANCE_LIQUIDATOR");

        List<LegalFilingEntity> filings =
                repository.findByResidentRegistryIdAndFilingTypeAndDeletedAtIsNull(
                        RESIDENT, "INHERITANCE_LIQUIDATOR");

        assertThat(filings).hasSize(1);
        assertThat(filings.get(0).getFilingType()).isEqualTo("INHERITANCE_LIQUIDATOR");
    }
}
