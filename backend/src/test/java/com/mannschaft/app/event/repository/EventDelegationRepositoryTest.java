package com.mannschaft.app.event.repository;

import com.mannschaft.app.event.EventDelegationStatus;
import com.mannschaft.app.event.entity.EventDelegationEntity;
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
 * F03.10 第一陣 — {@link EventDelegationRepository} 結合テスト。
 *
 * <p>Entity マッピング・テナント派生クエリ・カスタム finder・F08.3 連携カラムの永続化・状態遷移
 * メソッドを検証する。（生成カラム + UNIQUE の DB 挙動は {@code EventDelegationMigrationIntegrationTest}
 * で別途検証。）</p>
 */
@Transactional
@DisplayName("EventDelegationRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class EventDelegationRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private EventDelegationRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long EVENT_ID = 9501L;
    private static final Long ORG_A = 1101L;
    private static final Long ORG_B = 1102L;

    private EventDelegationEntity persist(Long delegatorId, Long delegateId,
                                          EventDelegationStatus status, Long orgId, Long pvSessionId) {
        EventDelegationEntity e = EventDelegationEntity.builder()
                .eventId(EVENT_ID)
                .delegatorId(delegatorId)
                .delegateId(delegateId)
                .organizationId(orgId)
                .status(status)
                .reason("急病のため")
                .proxyVoteSessionId(pvSessionId)
                .build();
        em.persist(e);
        em.flush();
        em.clear();
        return e;
    }

    @Test
    @DisplayName("保存_全フィールド+F08.3連携カラムが永続化される")
    void 保存_全フィールドが永続化される() {
        EventDelegationEntity saved = persist(8101L, 8102L, EventDelegationStatus.PENDING, ORG_A, 99L);

        EventDelegationEntity found = em.find(EventDelegationEntity.class, saved.getId());
        assertThat(found).isNotNull();
        assertThat(found.getEventId()).isEqualTo(EVENT_ID);
        assertThat(found.getDelegatorId()).isEqualTo(8101L);
        assertThat(found.getDelegateId()).isEqualTo(8102L);
        assertThat(found.getOrganizationId()).isEqualTo(ORG_A);
        assertThat(found.getStatus()).isEqualTo(EventDelegationStatus.PENDING);
        assertThat(found.getProxyVoteSessionId()).isEqualTo(99L);
        assertThat(found.getProxyDelegationId()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("findFirstByEventIdAndDelegatorIdAndStatusIn — アクティブ委任を解決できる")
    void アクティブ委任を委任者視点で解決できる() {
        persist(8101L, 8102L, EventDelegationStatus.ACCEPTED, ORG_A, null);

        Optional<EventDelegationEntity> result =
                repository.findFirstByEventIdAndDelegatorIdAndStatusIn(
                        EVENT_ID, 8101L,
                        List.of(EventDelegationStatus.PENDING, EventDelegationStatus.ACCEPTED));

        assertThat(result).isPresent();
        assertThat(result.get().getDelegateId()).isEqualTo(8102L);
    }

    @Test
    @DisplayName("existsByDelegateIdAndStatusIn — 連鎖代理禁止チェックが効く")
    void 連鎖代理禁止チェックが効く() {
        persist(8101L, 8102L, EventDelegationStatus.PENDING, ORG_A, null);

        boolean exists = repository.existsByDelegateIdAndStatusIn(
                8102L, List.of(EventDelegationStatus.PENDING, EventDelegationStatus.ACCEPTED));

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("findByIdAndOrganizationIdAndDeletedAtIsNull — 別組織からは見えない（IDOR対策）")
    void 別組織からは見えない() {
        EventDelegationEntity saved = persist(8101L, 8102L, EventDelegationStatus.PENDING, ORG_A, null);

        assertThat(repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_A)).isPresent();
        assertThat(repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_B)).isEmpty();
    }

    @Test
    @DisplayName("findByEventIdOrderByCreatedAtDesc — ADMIN 一覧をページング取得できる")
    void ADMIN一覧をページング取得できる() {
        persist(8101L, 8102L, EventDelegationStatus.PENDING, ORG_A, null);
        persist(8103L, 8104L, EventDelegationStatus.ACCEPTED, ORG_A, null);

        var page = repository.findByEventIdOrderByCreatedAtDesc(EVENT_ID, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2);
        assertThat(repository.countByEventId(EVENT_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("linkProxyDelegation() で proxy_delegation_id を設定できる")
    void 投票委任連携を設定できる() {
        EventDelegationEntity saved = persist(8101L, 8102L, EventDelegationStatus.ACCEPTED, ORG_A, 99L);
        EventDelegationEntity managed = em.find(EventDelegationEntity.class, saved.getId());

        managed.linkProxyDelegation(555L);
        em.flush();
        em.clear();

        EventDelegationEntity reloaded = em.find(EventDelegationEntity.class, saved.getId());
        assertThat(reloaded.getProxyDelegationId()).isEqualTo(555L);
    }
}
