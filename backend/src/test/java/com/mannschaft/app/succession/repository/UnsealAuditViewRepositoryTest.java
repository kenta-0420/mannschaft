package com.mannschaft.app.succession.repository;

import com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity;
import com.mannschaft.app.succession.entity.UnsealAuditViewEntity;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.15 S1-A {@link UnsealAuditViewRepository} 結合テスト。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §5.6</p>
 */
@Transactional
@DisplayName("UnsealAuditViewRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class UnsealAuditViewRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private UnsealAuditViewRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_A = 9301L;
    private static final Long DWELLING = 11_301L;
    private static final Long RESIDENT = 12_301L;
    private static final Long REQUESTER = 13_301L;
    private static final Long FIRST_APPROVER = 13_302L;
    private static final Long SECOND_APPROVER = 13_303L;
    private static final Long VIEWER = 13_304L;

    private UUID persistRequest() {
        SuccessionPreRegistrationEntity pre = SuccessionPreRegistrationEntity.builder()
                .organizationId(ORG_A)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(RESIDENT + System.nanoTime() % 100000L)
                .ownerUserId(REQUESTER)
                .sealStatus("UNSEALED")
                .build();
        em.persist(pre);

        UnsealRequestEntity req = UnsealRequestEntity.builder()
                .organizationId(ORG_A)
                .dwellingUnitId(DWELLING)
                .residentRegistryId(RESIDENT)
                .preRegistrationId(pre.getId())
                .requestedBy(REQUESTER)
                .firstApproverUserId(FIRST_APPROVER)
                .secondApproverUserId(SECOND_APPROVER)
                .requestReason("死亡確認のため")
                .build();
        em.persist(req);
        em.flush();
        return req.getId();
    }

    @Test
    @DisplayName("保存_主要フィールドが永続化される")
    void 保存_主要フィールドが永続化される() {
        UUID requestId = persistRequest();

        UnsealAuditViewEntity view = UnsealAuditViewEntity.builder()
                .organizationId(ORG_A)
                .unsealRequestId(requestId)
                .viewerUserId(VIEWER)
                .viewedAt(LocalDateTime.now())
                .ipAddress("192.0.2.1")
                .userAgent("Mozilla/5.0 Test")
                .requestId("req-test-001")
                .build();
        em.persist(view);
        em.flush();
        em.clear();

        UnsealAuditViewEntity found = em.find(UnsealAuditViewEntity.class, view.getId());
        assertThat(found).isNotNull();
        assertThat(found.getUnsealRequestId()).isEqualTo(requestId);
        assertThat(found.getViewerUserId()).isEqualTo(VIEWER);
        assertThat(found.getIpAddress()).isEqualTo("192.0.2.1");
        assertThat(found.getUserAgent()).contains("Test");
        assertThat(found.getRequestId()).isEqualTo("req-test-001");
        assertThat(found.getViewedAt()).isNotNull();
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findByUnsealRequestId_閲覧履歴を時刻降順で取得")
    void findByUnsealRequestId_閲覧履歴を時刻降順で取得() {
        UUID requestId = persistRequest();

        for (int i = 0; i < 3; i++) {
            UnsealAuditViewEntity v = UnsealAuditViewEntity.builder()
                    .organizationId(ORG_A)
                    .unsealRequestId(requestId)
                    .viewerUserId(VIEWER)
                    .viewedAt(LocalDateTime.now().minusMinutes(3 - i))
                    .build();
            em.persist(v);
        }
        em.flush();
        em.clear();

        List<UnsealAuditViewEntity> views =
                repository.findByUnsealRequestIdAndDeletedAtIsNullOrderByViewedAtDesc(requestId);

        assertThat(views).hasSize(3);
        // 降順
        assertThat(views.get(0).getViewedAt()).isAfterOrEqualTo(views.get(1).getViewedAt());
        assertThat(views.get(1).getViewedAt()).isAfterOrEqualTo(views.get(2).getViewedAt());
    }
}
