package com.mannschaft.app.schedule.repository;

import com.mannschaft.app.schedule.ScheduleDelegationStatus;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.10 第一陣 — {@link ScheduleDelegationRepository} 結合テスト。
 *
 * <p>Entity マッピング・{@link com.mannschaft.app.common.repository.AbstractTenantAwareRepository} 継承の
 * 派生クエリ・カスタム finder がランタイムで解決され、永続化/検索が通ることを検証する。
 * （生成カラム + UNIQUE の DB 挙動は {@code ScheduleDelegationMigrationIntegrationTest} で別途検証。）</p>
 */
@Transactional
@DisplayName("ScheduleDelegationRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ScheduleDelegationRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ScheduleDelegationRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long SCHEDULE_ID = 9001L;
    private static final Long ORG_A = 1001L;
    private static final Long ORG_B = 1002L;

    private ScheduleDelegationEntity persist(Long delegatorId, Long delegateId,
                                             ScheduleDelegationStatus status, Long orgId) {
        ScheduleDelegationEntity e = ScheduleDelegationEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .delegatorId(delegatorId)
                .delegateId(delegateId)
                .organizationId(orgId)
                .status(status)
                .reason("出張のため")
                .build();
        em.persist(e);
        em.flush();
        em.clear();
        return e;
    }

    @Test
    @DisplayName("保存_全フィールドが永続化される")
    void 保存_全フィールドが永続化される() {
        ScheduleDelegationEntity saved = persist(7001L, 7002L, ScheduleDelegationStatus.PENDING, ORG_A);

        ScheduleDelegationEntity found = em.find(ScheduleDelegationEntity.class, saved.getId());
        assertThat(found).isNotNull();
        assertThat(found.getScheduleId()).isEqualTo(SCHEDULE_ID);
        assertThat(found.getDelegatorId()).isEqualTo(7001L);
        assertThat(found.getDelegateId()).isEqualTo(7002L);
        assertThat(found.getOrganizationId()).isEqualTo(ORG_A);
        assertThat(found.getStatus()).isEqualTo(ScheduleDelegationStatus.PENDING);
        assertThat(found.getReason()).isEqualTo("出張のため");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("findFirstByScheduleIdAndDelegatorIdAndStatusIn — アクティブ委任を解決できる")
    void アクティブ委任を委任者視点で解決できる() {
        persist(7001L, 7002L, ScheduleDelegationStatus.PENDING, ORG_A);

        Optional<ScheduleDelegationEntity> result =
                repository.findFirstByScheduleIdAndDelegatorIdAndStatusIn(
                        SCHEDULE_ID, 7001L,
                        List.of(ScheduleDelegationStatus.PENDING, ScheduleDelegationStatus.ACCEPTED));

        assertThat(result).isPresent();
        assertThat(result.get().getDelegateId()).isEqualTo(7002L);
    }

    @Test
    @DisplayName("findByScheduleIdAndDelegateIdAndStatusIn — 代理人視点で解決できる")
    void 代理人視点で解決できる() {
        persist(7001L, 7002L, ScheduleDelegationStatus.PENDING, ORG_A);

        List<ScheduleDelegationEntity> result =
                repository.findByScheduleIdAndDelegateIdAndStatusIn(
                        SCHEDULE_ID, 7002L, List.of(ScheduleDelegationStatus.PENDING));

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("existsByDelegateIdAndStatusIn — 連鎖代理禁止チェックが効く")
    void 連鎖代理禁止チェックが効く() {
        persist(7001L, 7002L, ScheduleDelegationStatus.ACCEPTED, ORG_A);

        boolean exists = repository.existsByDelegateIdAndStatusIn(
                7002L, List.of(ScheduleDelegationStatus.PENDING, ScheduleDelegationStatus.ACCEPTED));

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("findByIdAndOrganizationIdAndDeletedAtIsNull — 別組織からは見えない（IDOR対策）")
    void 別組織からは見えない() {
        ScheduleDelegationEntity saved = persist(7001L, 7002L, ScheduleDelegationStatus.PENDING, ORG_A);

        assertThat(repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_A)).isPresent();
        assertThat(repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_B)).isEmpty();
    }

    @Test
    @DisplayName("findByScheduleIdOrderByCreatedAtDesc — ADMIN 一覧をページング取得できる")
    void ADMIN一覧をページング取得できる() {
        persist(7001L, 7002L, ScheduleDelegationStatus.PENDING, ORG_A);
        persist(7003L, 7004L, ScheduleDelegationStatus.ACCEPTED, ORG_A);

        var page = repository.findByScheduleIdOrderByCreatedAtDesc(SCHEDULE_ID, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2);
        assertThat(repository.countByScheduleId(SCHEDULE_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("accept() で status と reviewedAt が更新される")
    void accept状態遷移() {
        ScheduleDelegationEntity saved = persist(7001L, 7002L, ScheduleDelegationStatus.PENDING, ORG_A);
        ScheduleDelegationEntity managed = em.find(ScheduleDelegationEntity.class, saved.getId());

        managed.accept();
        em.flush();
        em.clear();

        ScheduleDelegationEntity reloaded = em.find(ScheduleDelegationEntity.class, saved.getId());
        assertThat(reloaded.getStatus()).isEqualTo(ScheduleDelegationStatus.ACCEPTED);
        assertThat(reloaded.getReviewedAt()).isNotNull();
    }
}
