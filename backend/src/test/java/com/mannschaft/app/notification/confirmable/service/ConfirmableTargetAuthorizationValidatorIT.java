package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.common.visibility.perf.VisibilityCheckerPerformanceTestBase;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練A補完（AC-12〜14・AC-33・AC-35 認可）。
 *
 * <p>軍議第8版確定稿 §3.3「認可」を対象とする。旧 {@code ConfirmableTargetAuthorizationValidatorTest}
 * は実データを持たない Mockito 相当のダミーID（100L・999L等）で検証しており、出陣後の実装が
 * 実在しない組織・チームに対して何を返すか（存在しないから403なのか、ツリー判定自体が
 * 動かず誤って通すのか）を区別できなかった。本クラスは実際の組織ツリー・
 * team_org_memberships を {@link AbstractMySqlIntegrationTest} 経由の実MySQLへ作り、
 * {@link ConfirmableTargetAuthorizationValidator} が実データに対して正しく判定することを検証する。</p>
 *
 * <p>{@link ConfirmableTargetAuthorizationValidator} は骨格段階では
 * {@code UnsupportedOperationException} を投げるスタブのため、本クラスの全テストは
 * 出陣（green化）まで red のまま失敗する。</p>
 *
 * <h2>AC ↔ テスト対応</h2>
 * <ul>
 *   <li>AC-12 自組織ツリー内は許可／他組織ツリー外は403 → {@link #ac12_organizationTreeScope()}</li>
 *   <li>AC-13 ツリー内組織にPENDINGでしか所属していないチームは403 → {@link #ac13_pendingOnlyTeamIsOutOfScope()}</li>
 *   <li>AC-14 チームスコープは自チームのみ許可 → {@link #ac14_teamScopeOnlySelf()}</li>
 *   <li>AC-33 グループ登録時は現在配下にないターゲットも403（登録時は厳格） → {@link #ac33_groupRegistrationIsStrict()}</li>
 *   <li>AC-35 認可検証はN（ターゲット件数）に比例したクエリ数にしない → {@link #ac35_queryCountNotProportionalToTargetCount()}</li>
 * </ul>
 */
@DisplayName("ConfirmableTargetAuthorizationValidator 試練（AC-12〜14・AC-33・AC-35 実DB）")
class ConfirmableTargetAuthorizationValidatorIT extends VisibilityCheckerPerformanceTestBase {

    private static final Logger log = LoggerFactory.getLogger(ConfirmableTargetAuthorizationValidatorIT.class);
    private static final AtomicInteger SLUG_SEQ = new AtomicInteger(0);

    @Autowired
    private ConfirmableTargetAuthorizationValidator validator;
    @Autowired
    private OrganizationRepository organizationRepository;
    @Autowired
    private TeamOrgMembershipRepository teamOrgMembershipRepository;

    // =====================================================================
    // AC-12 組織ツリーのスコープ判定
    // =====================================================================
    @Test
    @DisplayName("AC-12 自組織・子孫組織は許可、他組織ツリー外は403 TARGET_OUT_OF_SCOPE")
    void ac12_organizationTreeScope() {
        long root = createOrg(null);
        long child = createOrg(root);
        long otherRootTree = createOrg(null); // ツリー外

        assertThatCode(() -> validator.validateForSend(
                ScopeType.ORGANIZATION, root,
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, root),
                        new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, child))))
                .as("AC-12: 自組織・子孫組織は許可される")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateForSend(
                ScopeType.ORGANIZATION, root,
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, root),
                        new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, otherRootTree))))
                .as("AC-12: ツリー外の組織は403")
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);
    }

    // =====================================================================
    // AC-13 PENDINGでしか所属していないチームは配下扱いしない
    // =====================================================================
    @Test
    @DisplayName("AC-13 ツリー内組織にACTIVE所属のチームは許可、PENDINGでしか所属していないチームは403")
    void ac13_pendingOnlyTeamIsOutOfScope() {
        long org = createOrg(null);
        long activeTeam = 91_001L;
        long pendingTeam = 91_002L;
        seedTeamOrgMembership(activeTeam, org, TeamOrgMembershipEntity.Status.ACTIVE);
        seedTeamOrgMembership(pendingTeam, org, TeamOrgMembershipEntity.Status.PENDING);

        assertThatCode(() -> validator.validateForSend(
                ScopeType.ORGANIZATION, org,
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, activeTeam))))
                .as("AC-13: ACTIVE所属のチームは許可される")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateForSend(
                ScopeType.ORGANIZATION, org,
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, pendingTeam))))
                .as("AC-13: PENDINGでしか所属していないチームは403")
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);
    }

    // =====================================================================
    // AC-14 チームスコープは自チーム以外を許さない
    // =====================================================================
    @Test
    @DisplayName("AC-14 チームスコープは自チームのみ許可、自チーム以外のTEAM/ORGANIZATIONは403")
    void ac14_teamScopeOnlySelf() {
        long selfTeam = 91_003L;
        long otherTeam = 91_004L;
        long anyOrg = createOrg(null);

        assertThatCode(() -> validator.validateForSend(
                ScopeType.TEAM, selfTeam,
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, selfTeam))))
                .as("AC-14: チームスコープで自チームのみは許可される")
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateForSend(
                ScopeType.TEAM, selfTeam,
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, otherTeam))))
                .as("AC-14: チームスコープで自チーム以外のTEAMは403")
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);

        assertThatThrownBy(() -> validator.validateForSend(
                ScopeType.TEAM, selfTeam,
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, anyOrg))))
                .as("AC-14: チームスコープでORGANIZATIONを指定すると403")
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);
    }

    // =====================================================================
    // AC-33 登録時（グループ作成/更新）の検証は送信時より厳格
    // =====================================================================
    @Test
    @DisplayName("AC-33 グループ登録時は、現在配下から外れているターゲットも403（登録時の基準は厳格）")
    void ac33_groupRegistrationIsStrict() {
        long org = createOrg(null);
        long team = 91_005L;
        // 登録時点ではACTIVE所属（正当）。
        seedTeamOrgMembership(team, org, TeamOrgMembershipEntity.Status.ACTIVE);
        assertThatCode(() -> validator.validateForGroupRegistration(
                ScopeType.ORGANIZATION, org,
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, team))))
                .as("AC-33: 登録時点で配下にあるターゲットは許可される")
                .doesNotThrowAnyException();

        // チームが組織を離脱（所属行の削除。本エンティティに LEFT 状態は無く、離脱は行削除で表す）した後に
        // 再度登録操作を行うと403になる（送信時の validateForSend は §8.1 により0人展開へ倒すのでここでは呼ばない）。
        teamOrgMembershipRepository.findByTeamIdAndOrganizationId(team, org)
                .ifPresent(teamOrgMembershipRepository::delete);
        teamOrgMembershipRepository.flush();

        assertThatThrownBy(() -> validator.validateForGroupRegistration(
                ScopeType.ORGANIZATION, org,
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, team))))
                .as("AC-33: 配下から外れたターゲットの登録操作は403（登録時は厳格）")
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);
    }

    // =====================================================================
    // AC-35 検証クエリ数はターゲット件数Nに比例しない
    // =====================================================================
    @Test
    @DisplayName("AC-35 検証クエリ数はターゲット件数N（1件 vs 50件）に比例しない")
    void ac35_queryCountNotProportionalToTargetCount() {
        long org = createOrg(null);
        List<Long> childTeams = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            long team = 92_000L + i;
            seedTeamOrgMembership(team, org, TeamOrgMembershipEntity.Status.ACTIVE);
            childTeams.add(team);
        }

        SqlIntentCounter.reset();
        validator.validateForSend(ScopeType.ORGANIZATION, org,
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, childTeams.get(0))));
        long oneTargetQueries = SqlIntentCounter.totalCount();

        SqlIntentCounter.reset();
        List<ConfirmableTargetSpec> fiftyTargets = childTeams.stream()
                .map(id -> new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, id))
                .toList();
        validator.validateForSend(ScopeType.ORGANIZATION, org, fiftyTargets);
        long fiftyTargetQueries = SqlIntentCounter.totalCount();

        log.info("[AC-35] 1件={} 50件={}", oneTargetQueries, fiftyTargetQueries);
        assertThat(fiftyTargetQueries)
                .as("AC-35: 50件のクエリ数は1件の定数倍（ツリー取得1回＋IN句1回）に収まり、N倍にはならない")
                .isLessThanOrEqualTo(oneTargetQueries + 2);
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    private long createOrg(Long parentOrganizationId) {
        OrganizationEntity org = organizationRepository.save(OrganizationEntity.builder()
                .slug("confirmable-authz-it-" + SLUG_SEQ.incrementAndGet())
                .name("confirmable authz IT org")
                .orgType(OrganizationEntity.OrgType.COMMUNITY)
                .parentOrganizationId(parentOrganizationId)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.FULL)
                .supporterEnabled(Boolean.TRUE)
                .build());
        return org.getId();
    }

    private void seedTeamOrgMembership(long teamId, long orgId, TeamOrgMembershipEntity.Status status) {
        teamOrgMembershipRepository.save(TeamOrgMembershipEntity.builder()
                .teamId(teamId)
                .organizationId(orgId)
                .status(status)
                .invitedAt(LocalDateTime.now())
                .build());
    }
}
