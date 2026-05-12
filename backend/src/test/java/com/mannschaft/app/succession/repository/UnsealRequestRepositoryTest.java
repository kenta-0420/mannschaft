package com.mannschaft.app.succession.repository;

import com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F09.15 S1-A {@link UnsealRequestRepository} 結合テスト。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.5</p>
 *
 * <p>3 者別人保証 (chk_ur_three_distinct) の DB CHECK 制約も検証する。</p>
 */
@Transactional
@DisplayName("UnsealRequestRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UnsealRequestRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private UnsealRequestRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_A = 9201L;
    private static final Long DWELLING = 11_201L;
    private static final Long RESIDENT = 12_201L;
    private static final Long REQUESTER = 13_201L;
    private static final Long FIRST_APPROVER = 13_202L;
    private static final Long SECOND_APPROVER = 13_203L;

    private UUID persistPreRegistration() {
        SuccessionPreRegistrationEntity pre = SuccessionPreRegistrationEntity.builder()
                .organizationId(ORG_A)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(RESIDENT + System.nanoTime() % 100000L)
                .ownerUserId(REQUESTER)
                .sealStatus("SEALED")
                .build();
        em.persist(pre);
        em.flush();
        return pre.getId();
    }

    private UnsealRequestEntity buildRequest(UUID preId, Long requestedBy,
                                             Long firstApprover, Long secondApprover) {
        return UnsealRequestEntity.builder()
                .organizationId(ORG_A)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(RESIDENT)
                .preRegistrationId(preId)
                .requestedBy(requestedBy)
                .requestReason("死亡確認のため封緘解除を申請する")
                .firstApproverUserId(firstApprover)
                .secondApproverUserId(secondApprover)
                .build();
    }

    @Test
    @DisplayName("保存_主要フィールドが永続化される")
    void 保存_主要フィールドが永続化される() {
        UUID preId = persistPreRegistration();
        UnsealRequestEntity req = buildRequest(preId, REQUESTER, null, null);
        em.persist(req);
        em.flush();
        em.clear();

        UnsealRequestEntity found = em.find(UnsealRequestEntity.class, req.getId());
        assertThat(found).isNotNull();
        assertThat(found.getPreRegistrationId()).isEqualTo(preId);
        assertThat(found.getRequestedBy()).isEqualTo(REQUESTER);
        assertThat(found.getRequestReason()).contains("封緘解除");
    }

    @Test
    @DisplayName("DB_CHECK_起票者と一次承認者が同一なら例外")
    void DB_CHECK_起票者と一次承認者が同一なら例外() {
        UUID preId = persistPreRegistration();
        UnsealRequestEntity req = buildRequest(preId, REQUESTER, REQUESTER, null);

        assertThatThrownBy(() -> {
            em.persist(req);
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB_CHECK_一次承認者と二次承認者が同一なら例外")
    void DB_CHECK_一次承認者と二次承認者が同一なら例外() {
        UUID preId = persistPreRegistration();
        UnsealRequestEntity req = buildRequest(preId, REQUESTER, FIRST_APPROVER, FIRST_APPROVER);

        assertThatThrownBy(() -> {
            em.persist(req);
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("DB_CHECK_3者別人なら成功")
    void DB_CHECK_3者別人なら成功() {
        UUID preId = persistPreRegistration();
        UnsealRequestEntity req =
                buildRequest(preId, REQUESTER, FIRST_APPROVER, SECOND_APPROVER);
        em.persist(req);
        em.flush();

        assertThat(req.getId()).isNotNull();
    }

    @Test
    @DisplayName("findByAutoResealAtBefore_72h再封バッチで対象を抽出する")
    void findByAutoResealAtBefore_72h再封バッチで対象を抽出する() {
        UUID preId = persistPreRegistration();

        UnsealRequestEntity past = buildRequest(preId, REQUESTER, FIRST_APPROVER, SECOND_APPROVER);
        past.setAutoResealAt(LocalDateTime.now().minusHours(1));
        em.persist(past);

        UnsealRequestEntity future = buildRequest(preId, REQUESTER, FIRST_APPROVER, SECOND_APPROVER);
        future.setAutoResealAt(LocalDateTime.now().plusHours(24));
        em.persist(future);
        em.flush();
        em.clear();

        List<UnsealRequestEntity> targets =
                repository.findByAutoResealAtBeforeAndReSealedAtIsNullAndDeletedAtIsNull(
                        LocalDateTime.now());

        assertThat(targets)
                .extracting(UnsealRequestEntity::getId)
                .contains(past.getId())
                .doesNotContain(future.getId());
    }
}
