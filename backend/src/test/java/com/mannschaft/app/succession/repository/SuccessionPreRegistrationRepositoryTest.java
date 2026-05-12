package com.mannschaft.app.succession.repository;

import com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity;
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
 * F09.15 S1-A {@link SuccessionPreRegistrationRepository} 結合テスト。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.4</p>
 */
@Transactional
@DisplayName("SuccessionPreRegistrationRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class SuccessionPreRegistrationRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private SuccessionPreRegistrationRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_A = 9101L;
    private static final Long ORG_B = 9102L;
    private static final Long DWELLING = 11_101L;
    private static final Long OWNER_USER = 13_101L;

    private SuccessionPreRegistrationEntity persistPreRegistration(
            Long residentRegistryId, Long ownerUserId, String sealStatus) {
        SuccessionPreRegistrationEntity entity = SuccessionPreRegistrationEntity.builder()
                .organizationId(ORG_A)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(residentRegistryId)
                .ownerUserId(ownerUserId)
                .sealStatus(sealStatus)
                .emergencyContacts("[{\"name\":\"山田太郎\",\"phone\":\"090-0000-0000\"}]")
                .inheritanceCandidates("[{\"name\":\"山田次郎\"}]")
                .willMemo("葬儀社は○○を希望")
                .frozenAccountInfo(null)
                .expectedAbsencePeriods("[]")
                .build();
        em.persist(entity);
        em.flush();
        em.clear();
        return entity;
    }

    @Test
    @DisplayName("保存_暗号化フィールドが復号できる")
    void 保存_暗号化フィールドが復号できる() {
        SuccessionPreRegistrationEntity saved =
                persistPreRegistration(12_101L, OWNER_USER, "SEALED");

        SuccessionPreRegistrationEntity found =
                em.find(SuccessionPreRegistrationEntity.class, saved.getId());
        assertThat(found).isNotNull();
        // EncryptedStringConverter による透過暗号化/復号
        assertThat(found.getEmergencyContacts()).contains("山田太郎");
        assertThat(found.getInheritanceCandidates()).contains("山田次郎");
        assertThat(found.getWillMemo()).isEqualTo("葬儀社は○○を希望");
        assertThat(found.getSealStatus()).isEqualTo("SEALED");
    }

    @Test
    @DisplayName("UNIQUE制約_1居住者1事前登録")
    void UNIQUE制約_1居住者1事前登録() {
        Long residentId = 12_102L;
        persistPreRegistration(residentId, OWNER_USER, "SEALED");

        assertThatThrownBy(() -> persistPreRegistration(residentId, OWNER_USER, "SEALED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("findBySealStatusAndAutoResealAtBefore_72h再封バッチで対象を抽出する")
    void findBySealStatusAndAutoResealAtBefore_72h再封バッチで対象を抽出する() {
        // 過去の auto_reseal_at（再封対象）
        SuccessionPreRegistrationEntity past = persistPreRegistration(12_103L, OWNER_USER, "UNSEALED");
        SuccessionPreRegistrationEntity managed1 =
                em.find(SuccessionPreRegistrationEntity.class, past.getId());
        managed1.setAutoResealAt(LocalDateTime.now().minusHours(1));
        em.flush();

        // 未来の auto_reseal_at（再封対象外）
        SuccessionPreRegistrationEntity future = persistPreRegistration(12_104L, OWNER_USER, "UNSEALED");
        SuccessionPreRegistrationEntity managed2 =
                em.find(SuccessionPreRegistrationEntity.class, future.getId());
        managed2.setAutoResealAt(LocalDateTime.now().plusHours(10));
        em.flush();
        em.clear();

        List<SuccessionPreRegistrationEntity> targets =
                repository.findBySealStatusAndAutoResealAtBeforeAndDeletedAtIsNull(
                                "UNSEALED", LocalDateTime.now());

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).getId()).isEqualTo(past.getId());
    }

    @Test
    @DisplayName("findByResidentRegistryId_居住者単位で取得できる")
    void findByResidentRegistryId_居住者単位で取得できる() {
        SuccessionPreRegistrationEntity saved =
                persistPreRegistration(12_105L, OWNER_USER, "SEALED");

        Optional<SuccessionPreRegistrationEntity> found =
                repository.findByResidentRegistryIdAndDeletedAtIsNull(12_105L);

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(saved.getId());
    }
}
