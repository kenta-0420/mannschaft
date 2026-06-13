package com.mannschaft.app.visibility.service;

import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.entity.TeamFriendEntity;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.visibility.VisibilityTemplateRuleType;
import com.mannschaft.app.visibility.entity.VisibilityTemplateEntity;
import com.mannschaft.app.visibility.entity.VisibilityTemplateRuleEntity;
import com.mannschaft.app.visibility.repository.VisibilityTemplateRepository;
import com.mannschaft.app.visibility.repository.VisibilityTemplateRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VisibilityTemplateEvaluator} のユニットテスト（Phase3b E-2）。
 *
 * <p>各 {@link VisibilityTemplateRuleType} の評価・{@code @USER_PRIMARY_TEAM} プレースホルダ解決・
 * OR 結合の short-circuit を網羅する。<b>現状の実装挙動の固定</b>が目的（仕様変更しない）。
 *
 * <p>注意: 純 Mockito UT のため {@code @Cacheable getTemplateRules} はキャッシュプロキシを介さず
 * 実メソッドが呼ばれる（= ruleRepository を直接叩く）。これは UT として意図どおり。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("VisibilityTemplateEvaluator")
class VisibilityTemplateEvaluatorTest {

    @Mock
    private VisibilityTemplateRepository visibilityTemplateRepository;
    @Mock
    private VisibilityTemplateRuleRepository visibilityTemplateRuleRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private TeamFriendRepository teamFriendRepository;

    @InjectMocks
    private VisibilityTemplateEvaluator evaluator;

    private static final Long VIEWER_ID = 10L;
    private static final Long OWNER_ID = 20L;
    private static final Long TEMPLATE_ID = 1000L;
    private static final Long TARGET_TEAM_ID = 700L;
    private static final Long TARGET_ORG_ID = 800L;

    // ─── canView: テンプレート存在・ルール無し ─────────────────

    @Test
    @DisplayName("テンプレートが存在しなければ false")
    void canView_templateNotExists_false() {
        when(visibilityTemplateRepository.existsById(TEMPLATE_ID)).thenReturn(false);
        assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isFalse();
    }

    @Test
    @DisplayName("ルールが空なら false")
    void canView_noRules_false() {
        when(visibilityTemplateRepository.existsById(TEMPLATE_ID)).thenReturn(true);
        when(visibilityTemplateRuleRepository.findByTemplateIdOrderBySortOrderAsc(TEMPLATE_ID))
                .thenReturn(List.of());
        assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isFalse();
    }

    // ─── 各 RuleType の評価 ─────────────────────────────────────

    @Nested
    @DisplayName("RuleType 評価")
    class RuleTypeEvaluation {

        @Test
        @DisplayName("EXPLICIT_USER: viewer.id == rule_target_id なら true")
        void explicitUser_match_true() {
            mockRules(rule(VisibilityTemplateRuleType.EXPLICIT_USER, VIEWER_ID, null));
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isTrue();
        }

        @Test
        @DisplayName("EXPLICIT_USER: 不一致なら false")
        void explicitUser_noMatch_false() {
            mockRules(rule(VisibilityTemplateRuleType.EXPLICIT_USER, 9999L, null));
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isFalse();
        }

        @Test
        @DisplayName("EXPLICIT_USER: rule_target_id が null なら false")
        void explicitUser_nullTarget_false() {
            mockRules(rule(VisibilityTemplateRuleType.EXPLICIT_USER, null, null));
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isFalse();
        }

        @Test
        @DisplayName("EXPLICIT_SOCIAL_PROFILE: viewer.id == rule_target_id（暫定）なら true")
        void explicitSocialProfile_match_true() {
            mockRules(rule(VisibilityTemplateRuleType.EXPLICIT_SOCIAL_PROFILE, VIEWER_ID, null));
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isTrue();
        }

        @Test
        @DisplayName("EXPLICIT_TEAM: viewer が当該チーム所属なら true")
        void explicitTeam_member_true() {
            mockRules(rule(VisibilityTemplateRuleType.EXPLICIT_TEAM, TARGET_TEAM_ID, null));
            when(userRoleRepository.existsByUserIdAndTeamId(VIEWER_ID, TARGET_TEAM_ID))
                    .thenReturn(true);
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isTrue();
        }

        @Test
        @DisplayName("TEAM_MEMBER_OF: viewer が当該チーム非所属なら false")
        void teamMemberOf_notMember_false() {
            mockRules(rule(VisibilityTemplateRuleType.TEAM_MEMBER_OF, TARGET_TEAM_ID, null));
            when(userRoleRepository.existsByUserIdAndTeamId(VIEWER_ID, TARGET_TEAM_ID))
                    .thenReturn(false);
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isFalse();
        }

        @Test
        @DisplayName("ORGANIZATION_MEMBER_OF: viewer が当該組織所属なら true")
        void orgMemberOf_member_true() {
            mockRules(rule(VisibilityTemplateRuleType.ORGANIZATION_MEMBER_OF, TARGET_ORG_ID, null));
            when(userRoleRepository.existsByUserIdAndOrganizationId(VIEWER_ID, TARGET_ORG_ID))
                    .thenReturn(true);
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isTrue();
        }

        @Test
        @DisplayName("REGION_MATCH: F01.2 未実装のため常に false（フォールバック）")
        void regionMatch_false() {
            mockRules(rule(VisibilityTemplateRuleType.REGION_MATCH, null, "REGION_X"));
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isFalse();
        }
    }

    // ─── TEAM_FRIEND_OF + プレースホルダ ────────────────────────

    @Nested
    @DisplayName("TEAM_FRIEND_OF / @USER_PRIMARY_TEAM")
    class TeamFriendOf {

        @Test
        @DisplayName("明示 team_id 指定: viewer のチームが target のフレンドなら true")
        void explicitTargetTeam_friend_true() {
            mockRules(rule(VisibilityTemplateRuleType.TEAM_FRIEND_OF, TARGET_TEAM_ID, null));
            // viewer は team 500 に所属
            when(userRoleRepository.findByUserIdAndTeamIdIsNotNull(VIEWER_ID))
                    .thenReturn(List.of(roleWithTeam(500L)));
            // 500 と 700 のフレンド関係を正規化（min=500, max=700）
            when(teamFriendRepository.findByTeamAIdAndTeamBId(500L, TARGET_TEAM_ID))
                    .thenReturn(Optional.of(mock(TeamFriendEntity.class)));

            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isTrue();
        }

        @Test
        @DisplayName("viewer がどのチームにも属さなければ false")
        void viewerNoTeam_false() {
            mockRules(rule(VisibilityTemplateRuleType.TEAM_FRIEND_OF, TARGET_TEAM_ID, null));
            when(userRoleRepository.findByUserIdAndTeamIdIsNotNull(VIEWER_ID))
                    .thenReturn(List.of());
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isFalse();
        }

        @Test
        @DisplayName("フレンド関係が無ければ false")
        void notFriend_false() {
            mockRules(rule(VisibilityTemplateRuleType.TEAM_FRIEND_OF, TARGET_TEAM_ID, null));
            when(userRoleRepository.findByUserIdAndTeamIdIsNotNull(VIEWER_ID))
                    .thenReturn(List.of(roleWithTeam(500L)));
            when(teamFriendRepository.findByTeamAIdAndTeamBId(500L, TARGET_TEAM_ID))
                    .thenReturn(Optional.empty());
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isFalse();
        }

        @Test
        @DisplayName("@USER_PRIMARY_TEAM: owner の最小 team_id を primary として解決する")
        void primaryTeamPlaceholder_resolved() {
            mockRules(rule(VisibilityTemplateRuleType.TEAM_FRIEND_OF, null, "@USER_PRIMARY_TEAM"));
            // owner は team 900, 700 に所属 → 最小 700 が primary
            when(userRoleRepository.findByUserIdAndTeamIdIsNotNull(OWNER_ID))
                    .thenReturn(List.of(roleWithTeam(900L), roleWithTeam(TARGET_TEAM_ID)));
            when(userRoleRepository.findByUserIdAndTeamIdIsNotNull(VIEWER_ID))
                    .thenReturn(List.of(roleWithTeam(500L)));
            when(teamFriendRepository.findByTeamAIdAndTeamBId(500L, TARGET_TEAM_ID))
                    .thenReturn(Optional.of(mock(TeamFriendEntity.class)));

            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isTrue();
        }

        @Test
        @DisplayName("@USER_PRIMARY_TEAM: owner がどのチームにも属さなければ解決失敗 false")
        void primaryTeamPlaceholder_ownerNoTeam_false() {
            mockRules(rule(VisibilityTemplateRuleType.TEAM_FRIEND_OF, null, "@USER_PRIMARY_TEAM"));
            when(userRoleRepository.findByUserIdAndTeamIdIsNotNull(OWNER_ID))
                    .thenReturn(List.of());
            assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isFalse();
        }
    }

    // ─── OR 結合・short-circuit ─────────────────────────────────

    @Test
    @DisplayName("OR 結合: 後続ルールが true なら全体 true（1つでも満たせば可視）")
    void or_anyTrue_true() {
        mockRules(
                rule(VisibilityTemplateRuleType.EXPLICIT_USER, 9999L, null), // false
                rule(VisibilityTemplateRuleType.EXPLICIT_USER, VIEWER_ID, null)); // true
        assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isTrue();
    }

    @Test
    @DisplayName("short-circuit: 先頭ルールが true なら後続ルールは評価しない")
    void or_shortCircuit_skipsRest() {
        VisibilityTemplateRuleEntity first = rule(VisibilityTemplateRuleType.EXPLICIT_USER, VIEWER_ID, null);
        VisibilityTemplateRuleEntity second = rule(VisibilityTemplateRuleType.TEAM_MEMBER_OF, TARGET_TEAM_ID, null);
        mockRules(first, second);

        assertThat(evaluator.canView(VIEWER_ID, TEMPLATE_ID, OWNER_ID)).isTrue();
        // 2 番目（TEAM_MEMBER_OF）は短絡で評価されない
        verify(userRoleRepository, never()).existsByUserIdAndTeamId(VIEWER_ID, TARGET_TEAM_ID);
    }

    // ─── resolveMemberUserIds（プレビュー） ─────────────────────

    @Test
    @DisplayName("resolveMemberUserIds: 各ルールのユーザー集合を和集合で返す")
    void resolveMemberUserIds_union() {
        VisibilityTemplateRuleEntity r1 = rule(VisibilityTemplateRuleType.EXPLICIT_USER, 11L, null);
        VisibilityTemplateRuleEntity r2 = rule(VisibilityTemplateRuleType.TEAM_MEMBER_OF, TARGET_TEAM_ID, null);
        when(visibilityTemplateRuleRepository.findByTemplateIdOrderBySortOrderAsc(TEMPLATE_ID))
                .thenReturn(List.of(r1, r2));
        when(userRoleRepository.findUserIdsByScope("TEAM", TARGET_TEAM_ID))
                .thenReturn(List.of(22L, 33L));

        Set<Long> result = evaluator.resolveMemberUserIds(TEMPLATE_ID, OWNER_ID);

        assertThat(result).containsExactlyInAnyOrder(11L, 22L, 33L);
    }

    // ─── ヘルパー ───────────────────────────────────────────────

    private void mockRules(VisibilityTemplateRuleEntity... rules) {
        when(visibilityTemplateRepository.existsById(TEMPLATE_ID)).thenReturn(true);
        when(visibilityTemplateRuleRepository.findByTemplateIdOrderBySortOrderAsc(TEMPLATE_ID))
                .thenReturn(List.of(rules));
    }

    private VisibilityTemplateRuleEntity rule(VisibilityTemplateRuleType type,
                                              Long targetId, String targetText) {
        // REGION_MATCH のログ出力で rule.getTemplate().getId() を参照するため template を設定する。
        VisibilityTemplateEntity template = VisibilityTemplateEntity.builder()
                .id(TEMPLATE_ID)
                .name("t")
                .build();
        return VisibilityTemplateRuleEntity.builder()
                .id(1L)
                .template(template)
                .ruleType(type)
                .ruleTargetId(targetId)
                .ruleTargetText(targetText)
                .sortOrder(0)
                .build();
    }

    private UserRoleEntity roleWithTeam(Long teamId) {
        return UserRoleEntity.builder()
                .userId(VIEWER_ID)
                .roleId(1L)
                .teamId(teamId)
                .build();
    }
}
