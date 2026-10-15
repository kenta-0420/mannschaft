package com.mannschaft.app.residencestatus.repository;

import com.mannschaft.app.residencestatus.entity.OrgWideSafetyCheck;
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
 * F09.16 {@link OrgWideSafetyCheckRepository} の統合テスト。
 */
@Transactional
@DisplayName("OrgWideSafetyCheckRepository 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class OrgWideSafetyCheckRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private OrgWideSafetyCheckRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_ID = 9501L;
    private static final Long TRIGGERED_BY = 7501L;

    private OrgWideSafetyCheck persistOwsc(Long safetyCheckId, String reason, LocalDateTime closedAt) {
        OrgWideSafetyCheck e = OrgWideSafetyCheck.builder()
                .organizationId(ORG_ID)
                .safetyCheckId(safetyCheckId)
                .triggeredBy(TRIGGERED_BY)
                .triggeredAt(LocalDateTime.now())
                .triggerReason(reason)
                .closedAt(closedAt)
                .build();
        em.persist(e);
        em.flush();
        em.clear();
        return e;
    }

    @Test
    @DisplayName("safety_check_id からの逆引きができる")
    void findBySafetyCheckId_逆引き() {
        persistOwsc(12345L, "地震", null);

        Optional<OrgWideSafetyCheck> result =
                repository.findBySafetyCheckIdAndDeletedAtIsNull(12345L);

        assertThat(result).isPresent();
        assertThat(result.get().getTriggerReason()).isEqualTo("地震");
    }

    @Test
    @DisplayName("organization_id × 未クローズ条件で取得できる")
    void findByOrganizationIdAndClosedAtIsNull_未クローズ取得() {
        persistOwsc(11111L, "地震", null);                              // 未クローズ → ヒット
        persistOwsc(22222L, "火災", null);                              // 未クローズ → ヒット
        persistOwsc(33333L, "組合判断", LocalDateTime.now());           // クローズ済 → 除外

        List<OrgWideSafetyCheck> list =
                repository.findByOrganizationIdAndClosedAtIsNullAndDeletedAtIsNull(ORG_ID);

        assertThat(list).hasSize(2);
    }

    @Test
    @DisplayName("close() で closedAt が設定される")
    void close_クローズ() {
        OrgWideSafetyCheck saved = persistOwsc(44444L, "地震", null);

        OrgWideSafetyCheck managed = em.find(OrgWideSafetyCheck.class, saved.getId());
        managed.close();
        em.flush();
        em.clear();

        Optional<OrgWideSafetyCheck> result =
                repository.findBySafetyCheckIdAndDeletedAtIsNull(44444L);

        assertThat(result).isPresent();
        assertThat(result.get().getClosedAt()).isNotNull();
    }
}
