package com.mannschaft.app.notification.credit.batch;

import com.mannschaft.app.notification.credit.entity.OrganizationNotificationBalanceEntity;
import com.mannschaft.app.notification.credit.service.NotificationCreditResetRunner;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;

/**
 * F09.13 {@link NotificationCreditMonthlyResetBatch#runBatch()} の統合テスト（CMP-035）。
 *
 * <p>1件のリセットが失敗しても他の件が独立トランザクション（{@code REQUIRES_NEW}）で
 * コミットされることを、実 DB（MySQL Testcontainers）で検証する。失敗は
 * {@link NotificationCreditResetRunner} を spy し、特定組織の {@code resetOne} でのみ
 * 例外を投げさせることで再現する（Spring Data JPA リポジトリはインターフェース実装のプロキシで
 * {@code callRealMethod()} が使えないため、spy 対象は具象クラスである Runner 側にする）。
 *
 * <p>クラスレベル {@code @Transactional} は付けない。1 次キャッシュにより、独立トランザクション側
 * でコミットされた更新がこのテストのコンテキストから見えなくなる事故を避けるため（既知の罠）。
 * 検証は毎回 {@link EntityManager#clear()} 後に DB から読み直した値で行う。
 *
 * <p>{@code runBatch()} は全組織を無絞り込みで走査するため、フィクスチャは
 * {@link #cleanUpFixtures()} で毎回撤去し、他テストへの巻き添えを防ぐ。</p>
 */
@DisplayName("NotificationCreditMonthlyResetBatch#runBatch 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationCreditMonthlyResetBatchIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private NotificationCreditMonthlyResetBatch batch;

    @MockitoSpyBean
    private NotificationCreditResetRunner resetRunner;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TransactionTemplate txTemplate;

    private static final Long ORG_ID_BASE = 9_960_000L;

    @AfterEach
    void cleanUpFixtures() {
        txTemplate.executeWithoutResult(status ->
                em.createNativeQuery(
                                "DELETE FROM organization_notification_balances WHERE organization_id >= :base")
                        .setParameter("base", ORG_ID_BASE)
                        .executeUpdate());
    }

    private OrganizationNotificationBalanceEntity buildBalance(Long organizationId, long freeUsed) {
        return OrganizationNotificationBalanceEntity.builder()
                .organizationId(organizationId)
                .freeUsedThisMonth(freeUsed)
                .freeQuotaMonth(LocalDate.now().minusMonths(1).withDayOfMonth(1))
                .alertSentThisMonth(false)
                .creditBalance(0L)
                .gracePeriodDebt(0L)
                .build();
    }

    private Long readFreeUsedThisMonth(Long id) {
        Number result = (Number) em.createNativeQuery(
                        "SELECT free_used_this_month FROM organization_notification_balances WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
        return result.longValue();
    }

    @Test
    @DisplayName("途中の1件が保存時に失敗しても、他の件のリセットはコミットされて残る")
    void runBatch_途中の1件が失敗しても他のリセットはコミットされる() {
        OrganizationNotificationBalanceEntity ok1 = buildBalance(ORG_ID_BASE + 1, 5L);
        OrganizationNotificationBalanceEntity broken = buildBalance(ORG_ID_BASE + 2, 5L);
        OrganizationNotificationBalanceEntity ok2 = buildBalance(ORG_ID_BASE + 3, 5L);

        txTemplate.executeWithoutResult(status -> {
            em.persist(ok1);
            em.persist(broken);
            em.persist(ok2);
            em.flush();
        });
        em.clear();

        // broken の id に対する resetOne だけ失敗させ、他は実処理（REQUIRES_NEW）を通す
        Long brokenId = broken.getId();
        willAnswer(invocation -> {
            Long targetId = invocation.getArgument(0);
            if (targetId.equals(brokenId)) {
                throw new RuntimeException("模擬保存失敗（CMP-035 検証用）");
            }
            return invocation.callRealMethod();
        }).given(resetRunner).resetOne(any(), any());

        // 本丸: バッチが例外を外に投げずに完走すること
        assertThatCode(() -> batch.runBatch()).doesNotThrowAnyException();

        em.clear();

        // 正常系2件はコミットされて free_used_this_month が 0 にリセットされている
        assertThat(readFreeUsedThisMonth(ok1.getId())).isZero();
        assertThat(readFreeUsedThisMonth(ok2.getId())).isZero();
        // 異常系1件は失敗してロールバックされ、元の値のまま残る（他へ巻き添えしない）
        assertThat(readFreeUsedThisMonth(broken.getId())).isEqualTo(5L);
    }

    @Test
    @DisplayName("複数件が処理対象のとき、全件が独立にコミットされる")
    void runBatch_全件が独立にコミットされる() {
        List<OrganizationNotificationBalanceEntity> balances = List.of(
                buildBalance(ORG_ID_BASE + 10, 3L),
                buildBalance(ORG_ID_BASE + 11, 3L),
                buildBalance(ORG_ID_BASE + 12, 3L));

        txTemplate.executeWithoutResult(status -> {
            balances.forEach(em::persist);
            em.flush();
        });
        em.clear();

        batch.runBatch();
        em.clear();

        for (OrganizationNotificationBalanceEntity b : balances) {
            assertThat(readFreeUsedThisMonth(b.getId())).isZero();
        }
    }
}
