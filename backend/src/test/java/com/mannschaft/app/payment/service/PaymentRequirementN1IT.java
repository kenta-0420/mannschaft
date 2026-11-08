package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.MembershipScopeQueryService;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.PaymentMethod;
import com.mannschaft.app.payment.PaymentStatus;
import com.mannschaft.app.payment.dto.PaymentRequirementResponse;
import com.mannschaft.app.payment.entity.MemberPaymentEntity;
import com.mannschaft.app.payment.entity.OrganizationAccessRequirementEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.entity.TeamAccessRequirementEntity;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.OrganizationAccessRequirementRepository;
import com.mannschaft.app.payment.repository.PaymentItemRepository;
import com.mannschaft.app.payment.repository.TeamAccessRequirementRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

/**
 * 未払い要件一覧（PaymentRequirementService#getPaymentRequirements）の N+1 解消を固定する試練
 * （戦役 CMP-261103-1225 / #4270 P1-A、AC-1〜AC-7）。
 *
 * <p>SQL 数は所属数・要件数に依存しないこと、応答（件数・順序・内容）が従前と等価であることを
 * Hibernate {@link Statistics#getPrepareStatementCount()} と characterization で検証する。
 * 所属解決（user_roles ∪ memberships）は本テストの対象外なのでスパイで固定する。</p>
 */
@Transactional
@DisplayName("未払い要件一覧 N+1 解消（CMP-261103-1225 P1-A）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class PaymentRequirementN1IT extends AbstractMySqlIntegrationTest {

    private static final long USER = 930001L;
    private static final long OTHER_USER = 930002L;

    @Autowired private PaymentRequirementService service;
    @Autowired private PaymentItemRepository itemRepository;
    @Autowired private TeamAccessRequirementRepository teamReqRepository;
    @Autowired private OrganizationAccessRequirementRepository orgReqRepository;
    @Autowired private MemberPaymentRepository paymentRepository;
    @MockitoSpyBean private MembershipScopeQueryService scopeQueryService;
    @PersistenceContext private EntityManager em;

    private PaymentItemEntity item(String name, long scopeBase) {
        return itemRepository.save(PaymentItemEntity.builder()
                .teamId(scopeBase).name(name).type(PaymentItemType.ANNUAL_FEE)
                .amount(new BigDecimal("1000")).currency("JPY").build());
    }

    private void pay(long userId, PaymentItemEntity item, PaymentStatus status, LocalDate validUntil) {
        paymentRepository.save(MemberPaymentEntity.builder()
                .userId(userId).paymentItemId(item.getId()).amountPaid(new BigDecimal("1000"))
                .paymentMethod(PaymentMethod.MANUAL).status(status).validUntil(validUntil)
                .paidAt(LocalDateTime.now()).build());
    }

    private void scopes(List<Long> teams, List<Long> orgs) {
        doReturn(teams).when(scopeQueryService).findActiveTeamIds(USER);
        doReturn(orgs).when(scopeQueryService).findActiveOrganizationIds(USER);
    }

    /** teams チーム × 各 reqs 件の要件（全部未払い）。 */
    private void seedTeams(long baseTeam, int teams, int reqsEach, List<Long> teamIds) {
        for (int t = 0; t < teams; t++) {
            long teamId = baseTeam + t;
            teamIds.add(teamId);
            for (int r = 0; r < reqsEach; r++) {
                PaymentItemEntity it = item("T" + teamId + "-" + r, teamId);
                teamReqRepository.save(TeamAccessRequirementEntity.builder()
                        .teamId(teamId).paymentItemId(it.getId()).build());
            }
        }
    }

    private Statistics stats() {
        em.flush();
        em.clear();
        Statistics s = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        s.setStatisticsEnabled(true);
        s.clear();
        return s;
    }

    private long countSql(Runnable r) {
        Statistics s = stats();
        r.run();
        return s.getPrepareStatementCount();
    }

    @Test
    @DisplayName("AC-1: 所属数と要件数を独立に増やしても SQL 数が一定")
    void sqlCountIndependentOfScopesAndRequirements() {
        List<Long> smallTeams = new ArrayList<>();
        seedTeams(931000L, 1, 1, smallTeams);
        scopes(smallTeams, List.of());
        long small = countSql(() -> assertThat(service.getPaymentRequirements(USER)).hasSize(1));

        // 所属数のみ増やす（1 → 12 チーム・各 1 要件）
        List<Long> manyTeams = new ArrayList<>();
        seedTeams(932000L, 12, 1, manyTeams);
        scopes(manyTeams, List.of());
        long manyScopes = countSql(() -> assertThat(service.getPaymentRequirements(USER)).hasSize(12));

        // 要件数のみ増やす（1 チーム・1 → 12 要件）
        List<Long> oneTeam = new ArrayList<>();
        seedTeams(933000L, 1, 12, oneTeam);
        scopes(oneTeam, List.of());
        long manyReqs = countSql(() -> assertThat(service.getPaymentRequirements(USER)).hasSize(12));

        System.out.println("PRS SQL small=" + small + " manyScopes=" + manyScopes + " manyReqs=" + manyReqs);
        assertThat(manyScopes).as("所属数増でも一定").isEqualTo(small);
        assertThat(manyReqs).as("要件数増でも一定").isEqualTo(small);
    }

    @Test
    @DisplayName("AC-2/AC-4: 順序（チーム→組織・所属順・要件ID昇順）と内容が等価。同一 item を複数要件が参照しても例外なし")
    void characterizationOrderAndSharedItem() {
        PaymentItemEntity a = item("A", 934001L);
        PaymentItemEntity b = item("B", 934002L);
        // team 934002 を先、934001 を後の所属順にする。b は両チームから共有参照（重複キー）
        teamReqRepository.save(TeamAccessRequirementEntity.builder().teamId(934002L).paymentItemId(b.getId()).build());
        teamReqRepository.save(TeamAccessRequirementEntity.builder().teamId(934002L).paymentItemId(a.getId()).build());
        teamReqRepository.save(TeamAccessRequirementEntity.builder().teamId(934001L).paymentItemId(b.getId()).build());
        orgReqRepository.save(OrganizationAccessRequirementEntity.builder()
                .organizationId(935001L).paymentItemId(a.getId()).build());
        scopes(List.of(934002L, 934001L), List.of(935001L));
        em.flush();
        em.clear();

        List<PaymentRequirementResponse> result = service.getPaymentRequirements(USER);

        assertThat(result).extracting(r -> r.getRequirementType() + ":" + r.getScope().getId()
                        + ":" + r.getPaymentItem().getName())
                .containsExactly("TEAM_ACCESS:934002:B", "TEAM_ACCESS:934002:A",
                        "TEAM_ACCESS:934001:B", "ORGANIZATION_ACCESS:935001:A");
    }

    @Test
    @DisplayName("AC-2/AC-7: 有効支払済みは除外。期限切れ・PENDING・他人の支払は未払い扱い（他人の支払が混入しない）")
    void paidFilterAndIdor() {
        PaymentItemEntity paid = item("PAID", 936001L);
        PaymentItemEntity expired = item("EXPIRED", 936001L);
        PaymentItemEntity pending = item("PENDING", 936001L);
        PaymentItemEntity othersPaid = item("OTHERS", 936001L);
        for (PaymentItemEntity it : List.of(paid, expired, pending, othersPaid)) {
            teamReqRepository.save(TeamAccessRequirementEntity.builder()
                    .teamId(936001L).paymentItemId(it.getId()).build());
        }
        pay(USER, paid, PaymentStatus.PAID, null);
        pay(USER, expired, PaymentStatus.PAID, LocalDate.now().minusDays(30));
        pay(USER, pending, PaymentStatus.PENDING, null);
        pay(OTHER_USER, othersPaid, PaymentStatus.PAID, null);
        scopes(List.of(936001L), List.of());
        em.flush();
        em.clear();

        List<PaymentRequirementResponse> result = service.getPaymentRequirements(USER);

        assertThat(result).extracting(r -> r.getPaymentItem().getName())
                .containsExactly("EXPIRED", "PENDING", "OTHERS");
    }

    @Test
    @DisplayName("AC-3: 所属なし・要件なしでも例外なく空を返す")
    void emptyInputs() {
        scopes(List.of(), List.of());
        assertThat(service.getPaymentRequirements(USER)).isEmpty();

        scopes(List.of(937001L), List.of(937002L));
        assertThat(service.getPaymentRequirements(USER)).isEmpty();
    }

    @Test
    @DisplayName("AC-5/AC-6: 参照先の項目が論理削除済み・不在なら従前どおり PAYMENT_ITEM_NOT_FOUND（NPE にしない）")
    void missingOrDeletedItemKeepsBusinessException() {
        PaymentItemEntity gone = item("GONE", 938001L);
        teamReqRepository.save(TeamAccessRequirementEntity.builder()
                .teamId(938001L).paymentItemId(gone.getId()).build());
        em.flush();
        em.createNativeQuery("UPDATE payment_items SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", gone.getId()).executeUpdate();
        em.clear();
        scopes(List.of(938001L), List.of());

        assertThatThrownBy(() -> service.getPaymentRequirements(USER))
                .isInstanceOf(BusinessException.class);
    }
}
