package com.mannschaft.app.membership.batch;

import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.service.RoleService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F00.5 Phase 3: {@link MembershipConsistencyChecker} 単体テスト。
 *
 * <p>差分件数の集計は {@link MembershipRepository#countOnlyInMemberships()} /
 * {@link RoleService#countUserRolesOnlyDiff()}（SQL 側の NOT EXISTS 集計）に委譲したため、
 * 本テストは「リポジトリが返した件数をメトリクスへ正しく反映するか」「サンプル取得の要否判定が
 * 正しいか」のみを検証する。SQL の正しさ（JOIN 条件・DISTINCT）自体は
 * {@code MembershipUserRoleConsistencyRepositoryIntegrationTest}（実 DB 結合テスト）で検証する。</p>
 */
@DisplayName("MembershipConsistencyChecker 単体テスト")
@ExtendWith(MockitoExtension.class)
class MembershipConsistencyCheckerTest {

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private RoleService roleService;

    private MeterRegistry meterRegistry;
    private MembershipConsistencyChecker checker;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        checker = new MembershipConsistencyChecker(membershipRepository, roleService, meterRegistry);
    }

    @Nested
    @DisplayName("checkConsistency() — メトリクス登録")
    class CheckConsistencyTests {

        @Test
        @DisplayName("整合状態（両カウント0）では両メトリクスともに0が記録され、サンプル取得は行われない")
        void checkConsistency_consistent_bothMetricsZero() {
            when(membershipRepository.countOnlyInMemberships()).thenReturn(0L);
            when(roleService.countUserRolesOnlyDiff()).thenReturn(0L);

            assertThatCode(() -> checker.checkConsistency()).doesNotThrowAnyException();

            assertThat(meterRegistry.find("f005.consistency.diff.count").gauge()).isNotNull();
            assertThat(meterRegistry.find("f005.consistency.diff.count").gauge().value()).isEqualTo(0.0);
            assertThat(meterRegistry.find("f005.consistency.only_in_user_roles.count").gauge()).isNotNull();
            assertThat(meterRegistry.find("f005.consistency.only_in_user_roles.count").gauge().value())
                    .isEqualTo(0.0);
            verify(roleService, never()).sampleUserRolesOnlyDiff(any(Pageable.class));
        }

        @Test
        @DisplayName("user_roles のみに2件ある場合、only_in_user_roles メトリクスが2になりサンプルを取得する")
        void checkConsistency_twoOnlyInUserRoles_metricIsTwo() {
            when(membershipRepository.countOnlyInMemberships()).thenReturn(0L);
            when(roleService.countUserRolesOnlyDiff()).thenReturn(2L);
            lenient().when(roleService.sampleUserRolesOnlyDiff(any(Pageable.class)))
                    .thenReturn(List.of());

            assertThatCode(() -> checker.checkConsistency()).doesNotThrowAnyException();

            assertThat(meterRegistry.find("f005.consistency.only_in_user_roles.count").gauge().value())
                    .isEqualTo(2.0);
            assertThat(meterRegistry.find("f005.consistency.diff.count").gauge().value()).isEqualTo(2.0);
            verify(roleService).sampleUserRolesOnlyDiff(any(Pageable.class));
        }

        @Test
        @DisplayName("memberships のみに1件ある場合、diff=1だが only_in_user_roles=0 でサンプル取得は行われない")
        void checkConsistency_onlyInMemberships_onlyInUserRolesStaysZero() {
            when(membershipRepository.countOnlyInMemberships()).thenReturn(1L);
            when(roleService.countUserRolesOnlyDiff()).thenReturn(0L);

            assertThatCode(() -> checker.checkConsistency()).doesNotThrowAnyException();

            assertThat(meterRegistry.find("f005.consistency.diff.count").gauge().value()).isEqualTo(1.0);
            assertThat(meterRegistry.find("f005.consistency.only_in_user_roles.count").gauge().value())
                    .isEqualTo(0.0);
            verify(roleService, never()).sampleUserRolesOnlyDiff(any(Pageable.class));
        }

        @Test
        @DisplayName("SAMPLE_LOG_LIMIT を超える件数があってもログ出力で例外が発生しない")
        void checkConsistency_exceedSampleLimit_noException() {
            when(membershipRepository.countOnlyInMemberships()).thenReturn(0L);
            when(roleService.countUserRolesOnlyDiff()).thenReturn(15L);
            lenient().when(roleService.sampleUserRolesOnlyDiff(any(Pageable.class)))
                    .thenReturn(List.of()); // サンプル取得件数自体は結合テストで検証するためここでは空でよい

            assertThatCode(() -> checker.checkConsistency()).doesNotThrowAnyException();

            assertThat(meterRegistry.find("f005.consistency.only_in_user_roles.count").gauge().value())
                    .isEqualTo(15.0);
        }
    }
}
