package com.mannschaft.app.membership.batch;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * F00.5 Phase 3: {@link MembershipConsistencyChecker} 単体テスト。
 *
 * <p>欠落データを仕込んだ状態で {@code onlyInUserRoles} のカウントが正しく増え、
 * 整合状態では 0 になることを検証する。</p>
 */
@DisplayName("MembershipConsistencyChecker 単体テスト")
@ExtendWith(MockitoExtension.class)
class MembershipConsistencyCheckerTest {

    @Mock
    private MembershipRepository membershipRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    private MeterRegistry meterRegistry;
    private MembershipConsistencyChecker checker;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        checker = new MembershipConsistencyChecker(membershipRepository, userRoleRepository, meterRegistry);

        // デフォルト: 空ページ（各テストで必要に応じてオーバーライド）
        when(membershipRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
        when(userRoleRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(Collections.emptyList()));
    }

    // ---------------------------------------------------------------------------
    // computeDiffResult
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("computeDiffResult()")
    class ComputeDiffResultTests {

        @Test
        @DisplayName("memberships と user_roles が完全一致するとき、両差分集合ともに空を返す")
        void computeDiffResult_perfectMatch_bothEmpty() {
            // given: userId=1, TEAM:10 が両テーブルに存在
            MembershipEntity m = buildActiveMembership(1L, "TEAM", 10L);
            UserRoleEntity ur = buildUserRoleTeam(1L, 10L);

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(m)));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(ur)));

            // when
            MembershipConsistencyChecker.DiffResult result = checker.computeDiffResult();

            // then
            assertThat(result.onlyInMemberships()).isEmpty();
            assertThat(result.onlyInUserRoles()).isEmpty();
        }

        @Test
        @DisplayName("user_roles にあるが memberships にアクティブ行が無いとき、onlyInUserRoles に含まれる")
        void computeDiffResult_userRoleWithoutMembership_detectedInOnlyInUserRoles() {
            // given: user_roles に userId=2, TEAM:20 が存在するが memberships には無い
            UserRoleEntity ur = buildUserRoleTeam(2L, 20L);

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(ur)));

            // when
            MembershipConsistencyChecker.DiffResult result = checker.computeDiffResult();

            // then
            assertThat(result.onlyInUserRoles()).hasSize(1);
            assertThat(result.onlyInUserRoles()).contains("2:TEAM:20");
            assertThat(result.onlyInMemberships()).isEmpty();
        }

        @Test
        @DisplayName("memberships にあるが user_roles に無いとき、onlyInMemberships に含まれる")
        void computeDiffResult_membershipWithoutUserRole_detectedInOnlyInMemberships() {
            // given: memberships に userId=3, ORGANIZATION:30 が存在するが user_roles には無い
            MembershipEntity m = buildActiveMembership(3L, "ORGANIZATION", 30L);

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(m)));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            // when
            MembershipConsistencyChecker.DiffResult result = checker.computeDiffResult();

            // then
            assertThat(result.onlyInMemberships()).hasSize(1);
            assertThat(result.onlyInMemberships()).contains("3:ORGANIZATION:30");
            assertThat(result.onlyInUserRoles()).isEmpty();
        }

        @Test
        @DisplayName("left_at が設定済み（退会済み）のメンバーシップ行は比較対象から除外される")
        void computeDiffResult_inactiveMembership_isExcluded() {
            // given: memberships に退会済み行、user_roles に同一キーが存在
            MembershipEntity inactive = buildInactiveMembership(4L, "TEAM", 40L);
            UserRoleEntity ur = buildUserRoleTeam(4L, 40L);

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(inactive)));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(ur)));

            // when: 退会済みは memberships 側から除外されるため、user_roles のみに存在する扱い
            MembershipConsistencyChecker.DiffResult result = checker.computeDiffResult();

            // then
            assertThat(result.onlyInUserRoles()).hasSize(1).contains("4:TEAM:40");
            assertThat(result.onlyInMemberships()).isEmpty();
        }

        @Test
        @DisplayName("複数の欠落がある場合、全件が onlyInUserRoles に含まれる")
        void computeDiffResult_multipleUserRolesMissingMemberships_allDetected() {
            // given: user_roles に 3 件あるが memberships には 0 件
            List<UserRoleEntity> userRoles = List.of(
                    buildUserRoleTeam(5L, 50L),
                    buildUserRoleTeam(6L, 60L),
                    buildUserRoleOrg(7L, 70L)
            );

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(userRoles));

            // when
            MembershipConsistencyChecker.DiffResult result = checker.computeDiffResult();

            // then
            assertThat(result.onlyInUserRoles()).hasSize(3)
                    .contains("5:TEAM:50", "6:TEAM:60", "7:ORGANIZATION:70");
        }
    }

    // ---------------------------------------------------------------------------
    // computeDiff（後方互換）
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("computeDiff() — 後方互換")
    class ComputeDiffTests {

        @Test
        @DisplayName("整合状態では 0 を返す")
        void computeDiff_consistent_returnsZero() {
            MembershipEntity m = buildActiveMembership(1L, "TEAM", 10L);
            UserRoleEntity ur = buildUserRoleTeam(1L, 10L);

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(m)));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(ur)));

            assertThat(checker.computeDiff()).isEqualTo(0L);
        }

        @Test
        @DisplayName("onlyInUserRoles=2 のとき 2 を返す")
        void computeDiff_twoOnlyInUserRoles_returnsTwo() {
            List<UserRoleEntity> userRoles = List.of(
                    buildUserRoleTeam(8L, 80L),
                    buildUserRoleOrg(9L, 90L)
            );

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(userRoles));

            assertThat(checker.computeDiff()).isEqualTo(2L);
        }
    }

    // ---------------------------------------------------------------------------
    // checkConsistency（メトリクス登録確認）
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("checkConsistency() — メトリクス登録")
    class CheckConsistencyTests {

        @Test
        @DisplayName("整合状態では両メトリクスともに 0 が記録される")
        void checkConsistency_consistent_bothMetricsZero() {
            MembershipEntity m = buildActiveMembership(1L, "TEAM", 10L);
            UserRoleEntity ur = buildUserRoleTeam(1L, 10L);

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(m)));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(ur)));

            assertThatCode(() -> checker.checkConsistency()).doesNotThrowAnyException();

            assertThat(meterRegistry.find("f005.consistency.diff.count").gauge()).isNotNull();
            assertThat(meterRegistry.find("f005.consistency.diff.count").gauge().value())
                    .isEqualTo(0.0);
            assertThat(meterRegistry.find("f005.consistency.only_in_user_roles.count").gauge()).isNotNull();
            assertThat(meterRegistry.find("f005.consistency.only_in_user_roles.count").gauge().value())
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("user_roles のみに 2 件ある場合、only_in_user_roles メトリクスが 2 になる")
        void checkConsistency_twoOnlyInUserRoles_metricIsTwo() {
            List<UserRoleEntity> userRoles = List.of(
                    buildUserRoleTeam(10L, 100L),
                    buildUserRoleOrg(11L, 110L)
            );

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(userRoles));

            assertThatCode(() -> checker.checkConsistency()).doesNotThrowAnyException();

            assertThat(meterRegistry.find("f005.consistency.only_in_user_roles.count").gauge().value())
                    .isEqualTo(2.0);
            assertThat(meterRegistry.find("f005.consistency.diff.count").gauge().value())
                    .isEqualTo(2.0);
        }

        @Test
        @DisplayName("memberships のみに 1 件ある場合、diff=1 だが only_in_user_roles=0 になる")
        void checkConsistency_onlyInMemberships_onlyInUserRolesStaysZero() {
            MembershipEntity m = buildActiveMembership(12L, "TEAM", 120L);

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(m)));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));

            assertThatCode(() -> checker.checkConsistency()).doesNotThrowAnyException();

            assertThat(meterRegistry.find("f005.consistency.diff.count").gauge().value())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.find("f005.consistency.only_in_user_roles.count").gauge().value())
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("SAMPLE_LOG_LIMIT を超える件数があってもログ出力で例外が発生しない")
        void checkConsistency_exceedSampleLimit_noException() {
            // SAMPLE_LOG_LIMIT(10) を超える 15 件を仕込む
            List<UserRoleEntity> userRoles = new ArrayList<>();
            for (long i = 1; i <= 15; i++) {
                userRoles.add(buildUserRoleTeam(i, i * 100));
            }

            when(membershipRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(Collections.emptyList()));
            when(userRoleRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(userRoles));

            assertThatCode(() -> checker.checkConsistency()).doesNotThrowAnyException();

            assertThat(meterRegistry.find("f005.consistency.only_in_user_roles.count").gauge().value())
                    .isEqualTo(15.0);
        }
    }

    // ---------------------------------------------------------------------------
    // ヘルパー
    // ---------------------------------------------------------------------------

    private MembershipEntity buildActiveMembership(Long userId, String scopeTypeName, Long scopeId) {
        ScopeType scopeType = ScopeType.valueOf(scopeTypeName);
        return MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .build();
    }

    private MembershipEntity buildInactiveMembership(Long userId, String scopeTypeName, Long scopeId) {
        ScopeType scopeType = ScopeType.valueOf(scopeTypeName);
        return MembershipEntity.builder()
                .userId(userId)
                .scopeType(scopeType)
                .scopeId(scopeId)
                .leftAt(LocalDateTime.now().minusDays(1))
                .build();
    }

    private UserRoleEntity buildUserRoleTeam(Long userId, Long teamId) {
        return UserRoleEntity.builder()
                .userId(userId)
                .roleId(1L)
                .teamId(teamId)
                .build();
    }

    private UserRoleEntity buildUserRoleOrg(Long userId, Long orgId) {
        return UserRoleEntity.builder()
                .userId(userId)
                .roleId(1L)
                .organizationId(orgId)
                .build();
    }
}
