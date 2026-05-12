package com.mannschaft.app.succession.repository;

import com.mannschaft.app.succession.entity.SuccessionCovenantEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.15 S1-A {@link SuccessionCovenantRepository} 結合テスト。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.3</p>
 */
@Transactional
@DisplayName("SuccessionCovenantRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SuccessionCovenantRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private SuccessionCovenantRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_A = 9001L;
    private static final Long ORG_B = 9002L;
    private static final Long DWELLING = 11_001L;
    private static final Long RESIDENT = 12_001L;
    private static final Long SIGNER = 13_001L;

    private SuccessionCovenantEntity persistCovenant(Long organizationId, Long signer,
                                                     String covenantType, String pdfKey) {
        SuccessionCovenantEntity entity = SuccessionCovenantEntity.builder()
                .organizationId(organizationId)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(RESIDENT)
                .signerUserId(signer)
                .covenantType(covenantType)
                .covenantVersion("v1.0.0")
                .pdfS3Key(pdfKey)
                .pdfSha256("a".repeat(64))
                .internalSignatureToken("tok-" + pdfKey)
                .signedAt(LocalDateTime.now())
                .build();
        em.persist(entity);
        em.flush();
        em.clear();
        return entity;
    }

    @Test
    @DisplayName("保存_主要フィールドが永続化される")
    void 保存_主要フィールドが永続化される() {
        SuccessionCovenantEntity saved = persistCovenant(
                ORG_A, SIGNER, "SUCCESSION_PRE_REGISTRATION", "succession/cov/01.pdf");

        SuccessionCovenantEntity found = em.find(SuccessionCovenantEntity.class, saved.getId());
        assertThat(found).isNotNull();
        assertThat(found.getId()).isNotNull();
        assertThat(found.getOrganizationId()).isEqualTo(ORG_A);
        assertThat(found.getCovenantType()).isEqualTo("SUCCESSION_PRE_REGISTRATION");
        assertThat(found.getPdfS3Key()).isEqualTo("succession/cov/01.pdf");
        assertThat(found.getPdfSha256()).hasSize(64);
        assertThat(found.getInternalSignatureToken()).startsWith("tok-");
        assertThat(found.getRevokedAt()).isNull();
        assertThat(found.getDeletedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("organization_id_別組織のレコードは見えない")
    void organization_id_別組織のレコードは見えない() {
        SuccessionCovenantEntity saved = persistCovenant(
                ORG_A, SIGNER, "SUCCESSION_PRE_REGISTRATION", "succession/cov/02.pdf");

        Optional<SuccessionCovenantEntity> visibleFromOwn =
                repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_A);
        Optional<SuccessionCovenantEntity> visibleFromOther =
                repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_B);

        assertThat(visibleFromOwn).isPresent();
        assertThat(visibleFromOther)
                .as("別組織から ORG_A の covenant は見えてはならない（IDOR 対策）")
                .isEmpty();
    }

    @Test
    @DisplayName("findByResidentRegistryId_居住者単位で取得できる")
    void findByResidentRegistryId_居住者単位で取得できる() {
        persistCovenant(ORG_A, SIGNER, "SUCCESSION_PRE_REGISTRATION", "succession/cov/p1.pdf");
        persistCovenant(ORG_A, SIGNER, "PRIVACY_CONSENT", "succession/cov/p2.pdf");
        persistCovenant(ORG_A, SIGNER, "MONITORING_CONSENT", "succession/cov/p3.pdf");

        List<SuccessionCovenantEntity> all =
                repository.findByResidentRegistryIdAndDeletedAtIsNull(RESIDENT);

        assertThat(all).hasSize(3);
        assertThat(all)
                .extracting(SuccessionCovenantEntity::getCovenantType)
                .containsExactlyInAnyOrder(
                        "SUCCESSION_PRE_REGISTRATION",
                        "PRIVACY_CONSENT",
                        "MONITORING_CONSENT");
    }

    @Test
    @DisplayName("CHECK制約_covenant_type_不正値は例外")
    void CHECK制約_covenant_type_不正値は例外() {
        SuccessionCovenantEntity entity = SuccessionCovenantEntity.builder()
                .organizationId(ORG_A)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(RESIDENT)
                .signerUserId(SIGNER)
                .covenantType("INVALID_TYPE")
                .covenantVersion("v1.0.0")
                .pdfS3Key("succession/cov/ng.pdf")
                .pdfSha256("a".repeat(64))
                .internalSignatureToken("tok-ng")
                .signedAt(LocalDateTime.now())
                .build();

        assertThatThrownBy(() -> {
            em.persist(entity);
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
