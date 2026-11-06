package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.matching.service.MatchingAdminQueryService;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.service.PaymentAdminQueryService;
import com.mannschaft.app.reservation.service.ReservationAdminQueryService;
import com.mannschaft.app.shift.service.ShiftRequestAdminQueryService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理者向け集約で、承認待ちのない実在スコープのプレビューSQLを省略する契約。
 *
 * <p>共通MySQL Testcontainersと共通Spring構成をそのまま使い、追加のモックや設定は作らない。
 * fixtureはRepository経由で計測前にcommitする。テスト自体にトランザクションを張らず、
 * 各Query Serviceの実proxyが開始する読み取りトランザクションを1呼び出しずつ計測する。
 * SessionFactory全体のStatisticsを使うため排他とし、有効状態は必ず復元する。</p>
 *
 * <p>本試験は内部Query ServiceのSQL本数を検査する。HTTPやロール別認可の試験は代替しない。</p>
 */
@Isolated
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("管理者向け承認待ち集約: 空スコープはCOUNTだけを発行する（実DB）")
class AdminActionRequiredEmptyQueryCountIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private ReservationAdminQueryService reservationService;
    @Autowired
    private ShiftRequestAdminQueryService shiftService;
    @Autowired
    private MatchingAdminQueryService matchingService;
    @Autowired
    private PaymentAdminQueryService paymentService;

    private TeamEntity team;
    private OrganizationEntity organization;

    @BeforeEach
    void 空の実在スコープを作成する() {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        team = teamRepository.saveAndFlush(TeamEntity.builder()
                .slug("admin-empty-team-" + suffix)
                .name("承認待ちゼロ件のSQL計測チーム")
                .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE)
                .supporterEnabled(false)
                .build());
        organization = organizationRepository.saveAndFlush(OrganizationEntity.builder()
                .slug("admin-empty-org-" + suffix)
                .name("未収請求ゼロ件のSQL計測組織")
                .orgType(OrganizationEntity.OrgType.OTHER)
                .visibility(OrganizationEntity.Visibility.PRIVATE)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE)
                .supporterEnabled(false)
                .build());
    }

    @AfterEach
    void 自分で作成したスコープだけを削除する() {
        if (organization != null) {
            organizationRepository.deleteById(organization.getId());
        }
        if (team != null) {
            teamRepository.deleteById(team.getId());
        }
    }

    @Test
    void 予約ゼロ件は代表行COUNTの1本だけを発行する() {
        assertEmptyQueryCount(() -> reservationService.pendingForTeam(team.getId(), team.getSlug(), 3), 1L);
    }

    @Test
    void シフト両種別ゼロ件はCOUNTの2本だけを発行する() {
        assertEmptyQueryCount(() -> shiftService.pendingForTeam(team.getId(), team.getSlug(), 3), 2L);
    }

    @Test
    void マッチング応募ゼロ件はCOUNTの1本だけを発行する() {
        assertEmptyQueryCount(() -> matchingService.pendingReceivedForTeam(team.getId(), team.getSlug(), 3), 1L);
    }

    @Test
    void 未収請求ゼロ件はCOUNTの1本だけを発行する() {
        assertEmptyQueryCount(() -> paymentService.unsettledForOrg(
                organization.getId(), organization.getSlug(), 3), 1L);
    }

    private void assertEmptyQueryCount(Supplier<PendingAggregate> query, long expectedStatements) {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean originallyEnabled = stats.isStatisticsEnabled();
        PendingAggregate result;
        long preparedStatements;
        try {
            stats.setStatisticsEnabled(true);
            stats.clear();
            result = query.get();
            preparedStatements = stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(originallyEnabled);
        }

        assertThat(result.pendingCount()).isZero();
        assertThat(result.items()).isEmpty();
        assertThat(preparedStatements).as("空スコープの集約はCOUNTのみ発行する")
                .isEqualTo(expectedStatements);
    }
}
