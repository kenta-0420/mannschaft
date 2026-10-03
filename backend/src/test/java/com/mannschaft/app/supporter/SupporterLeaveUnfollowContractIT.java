package com.mannschaft.app.supporter;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261001-0835 試練: 応援者（SUPPORTER）の退出（DELETE /{slug}/me）と
 * フォロー解除（DELETE /{slug}/follow）の受け入れテスト（AC-11〜AC-15）。
 *
 * <p>対象 EP（組織・チームの双子）:</p>
 * <ul>
 *   <li>DELETE /api/v1/organizations/{slug}/me ・ DELETE /api/v1/teams/{slug}/me（{@code RoleService#leaveScope}）</li>
 *   <li>DELETE /api/v1/organizations/{slug}/follow ・ DELETE /api/v1/teams/{slug}/follow
 *       （{@code SupporterService#unfollow}）</li>
 * </ul>
 *
 * <p>仕様の正本: 確定受け入れ条件 AC-11〜15 と
 * {@code docs/features/F01.2_org_team_member_role/03_business_logic.md}「組織フォロー解除フロー」
 * （SUPPORTER のアクティブ所属が無ければ 404）。</p>
 *
 * <ul>
 *   <li>AC-11: 応援者の /me は 422 + 新ErrorCode（フォロー解除 API を案内）。所属も user_roles も不変。
 *       新ErrorCode は仮に {@code ROLE_015} とし、未定義でもコンパイルできるよう文字列で比較する。</li>
 *   <li>AC-12: 所属もロールも無い人の /me は 404 ROLE_001（退会済み SUPPORTER 履歴・PENDING 申請のみも同じ。記録は不変）</li>
 *   <li>AC-13: MEMBER の /me は 204 で所属終了（退行なし）</li>
 *   <li>AC-14: MEMBER / ADMIN の /follow は 404 で所属・user_roles 不変。解除対象は SUPPORTER 所属と PENDING 申請のみ</li>
 *   <li>AC-15: 他スコープ・同一数値 ID の組織/チームへ波及しない。未認証は 401 で不変</li>
 * </ul>
 *
 * <p>作法は既存の {@code SupporterScopeContractIT} / {@code OrganizationSupporterScopeContractIT} に倣う
 * （実 MySQL・{@code addFilters=false} + 手動 SecurityContext。Service・Repository・認可判定はモックしない）。
 * 各リクエスト後は {@code em.flush()/clear()} の上で native SQL により DB を再読込して検証する。</p>
 *
 * <p>範囲外: 最後の ADMIN の退出は設計書 422・実装 409 ROLE_004 の既存不一致があり、本戦役では
 * 現行の 409 ROLE_004 を回帰の期待値として固定する。</p>
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261001-0835 応援者の退出・フォロー解除 受け入れテスト（AC-11〜15）")
class SupporterLeaveUnfollowContractIT extends AbstractMySqlIntegrationTest {

    /** AC-11 で新設予定の ErrorCode（仮名）。実装側で名前を変える場合はここを合わせる。 */
    private static final String SUPPORTER_MUST_UNFOLLOW_CODE = "ROLE_015";

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    /** 組織・チームの切替（同一シナリオを双子の EP に流す）。 */
    enum Kind {
        ORGANIZATION("/api/v1/organizations"),
        TEAM("/api/v1/teams");

        final String basePath;

        Kind(String basePath) {
            this.basePath = basePath;
        }

        ScopeType scopeType() {
            return ScopeType.valueOf(name());
        }
    }

    /** スコープ 1 件（id と slug）。 */
    private record Scope(Kind kind, Long id, String slug) {
    }

    private Scope orgA;
    private Scope orgB;
    private Scope teamA;
    private Scope teamB;

    /** スコープ A の応援者（U）。 */
    private Long supporterUId;
    /** スコープ B の応援者（V）。 */
    private Long supporterVId;
    /** スコープ A の一般メンバー。 */
    private Long memberId;
    /** スコープ A の唯一の ADMIN（memberships MEMBER + user_roles ADMIN）。 */
    private Long adminId;
    /** スコープ A に PENDING 申請だけを持つ人。 */
    private Long pendingOnlyId;
    /** スコープ A の SUPPORTER を過去に退会した人（left_at 済みの履歴のみ）。 */
    private Long formerSupporterId;
    /** どこにも所属しない人。 */
    private Long outsiderId;

    @BeforeEach
    void setUp() {
        orgA = newScope(Kind.ORGANIZATION, "応援退出試練組織A", null);
        orgB = newScope(Kind.ORGANIZATION, "応援退出試練組織B", null);
        teamA = newScope(Kind.TEAM, "応援退出試練チームA", null);
        teamB = newScope(Kind.TEAM, "応援退出試練チームB", null);

        supporterUId = insertUser("cmp0835-supporter-u@example.com");
        supporterVId = insertUser("cmp0835-supporter-v@example.com");
        memberId = insertUser("cmp0835-member@example.com");
        adminId = insertUser("cmp0835-admin@example.com");
        pendingOnlyId = insertUser("cmp0835-pending@example.com");
        formerSupporterId = insertUser("cmp0835-former@example.com");
        outsiderId = insertUser("cmp0835-outsider@example.com");

        for (Scope a : List.of(orgA, teamA)) {
            MembershipTestHelper.insertMembership(em, supporterUId, a.kind().scopeType(), a.id(), RoleKind.SUPPORTER);
            MembershipTestHelper.insertMembership(em, memberId, a.kind().scopeType(), a.id(), RoleKind.MEMBER);
            MembershipTestHelper.insertMembership(em, adminId, a.kind().scopeType(), a.id(), RoleKind.MEMBER);
            insertAdminRole(adminId, a);
            insertPendingApplication(a, pendingOnlyId);
            insertLeftMembership(formerSupporterId, a, RoleKind.SUPPORTER);
        }
        for (Scope b : List.of(orgB, teamB)) {
            MembershipTestHelper.insertMembership(em, supporterVId, b.kind().scopeType(), b.id(), RoleKind.SUPPORTER);
        }

        em.flush();
        em.clear();
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-11: 応援者の /me は 422（フォロー解除 API を案内）・不変
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-11: 応援者が DELETE /me を叩くと 422")
    class Ac11SupporterLeave {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-11: 応援者の /me は 422 + フォロー解除案内の ErrorCode。SUPPORTER 所属は終了しない")
        void 応援者のmeは422で所属不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            setAuthentication(supporterUId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/me", a.slug()))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value(SUPPORTER_MUST_UNFOLLOW_CODE));

            reload();
            assertThat(activeCount(supporterUId, a, RoleKind.SUPPORTER)).isEqualTo(1);
            assertThat(leftCount(supporterUId, a)).isZero();
            assertThat(userRoleCount(supporterUId, a)).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-12: 所属もロールも無い人の /me は 404 ROLE_001・記録不変
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-12: 無所属の /me は従来どおり 404 ROLE_001")
    class Ac12NoMembershipLeave {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-12: 所属もロールも無いユーザーの /me は 404 ROLE_001")
        void 無所属のmeは404(Kind kind) throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/me", scopeA(kind).slug()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ROLE_001"));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-12: 退会済み SUPPORTER 履歴だけの人の /me は 404 ROLE_001 で履歴は不変")
        void 退会済み応援者のmeは404で履歴不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            String leftAtBefore = leftAtOf(formerSupporterId, a);
            setAuthentication(formerSupporterId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/me", a.slug()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ROLE_001"));

            reload();
            assertThat(leftCount(formerSupporterId, a)).isEqualTo(1);
            assertThat(activeCount(formerSupporterId, a, RoleKind.SUPPORTER)).isZero();
            assertThat(leftAtOf(formerSupporterId, a)).isEqualTo(leftAtBefore);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-12: PENDING 申請だけの人の /me は 404 ROLE_001 で申請は残る")
        void PENDINGのみのmeは404で申請不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            setAuthentication(pendingOnlyId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/me", a.slug()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("ROLE_001"));

            reload();
            assertThat(pendingCount(pendingOnlyId, a)).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-13: MEMBER の /me は 204 で所属終了（退行なし）＋最後の ADMIN 回帰
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-13: MEMBER の /me は従来どおり 204")
    class Ac13MemberLeave {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-13: MEMBER の /me は 204 で MEMBER 所属が終了する")
        void MEMBERのmeは204で所属終了(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            setAuthentication(memberId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/me", a.slug()))
                    .andExpect(status().isNoContent());

            reload();
            assertThat(activeCount(memberId, a, RoleKind.MEMBER)).isZero();
            assertThat(leftCount(memberId, a)).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-13 回帰（範囲外の既存挙動）: 最後の ADMIN の /me は現行どおり 409 ROLE_004 で所属・user_roles 不変")
        void 最後のADMINのmeは409で不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            setAuthentication(adminId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/me", a.slug()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("ROLE_004"));

            reload();
            assertThat(activeCount(adminId, a, RoleKind.MEMBER)).isEqualTo(1);
            assertThat(userRoleCount(adminId, a)).isEqualTo(1);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-14: /follow の解除対象は SUPPORTER 所属と PENDING 申請のみ
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-14: DELETE /follow は SUPPORTER 所属と PENDING 申請だけを解除する")
    class Ac14Unfollow {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-14: MEMBER の /follow は 404 で MEMBER 所属は不変")
        void MEMBERのfollow解除は404で所属不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            setAuthentication(memberId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", a.slug()))
                    .andExpect(status().isNotFound());

            reload();
            assertThat(activeCount(memberId, a, RoleKind.MEMBER)).isEqualTo(1);
            assertThat(leftCount(memberId, a)).isZero();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-14: ADMIN の /follow は 404 で所属・user_roles(ADMIN) は不変")
        void ADMINのfollow解除は404で所属とロール不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            setAuthentication(adminId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", a.slug()))
                    .andExpect(status().isNotFound());

            reload();
            assertThat(activeCount(adminId, a, RoleKind.MEMBER)).isEqualTo(1);
            assertThat(leftCount(adminId, a)).isZero();
            assertThat(userRoleCount(adminId, a)).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-14: 応援者の /follow は 204 で SUPPORTER 所属が終了する")
        void 応援者のfollow解除は204で所属終了(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            setAuthentication(supporterUId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", a.slug()))
                    .andExpect(status().isNoContent());

            reload();
            assertThat(activeCount(supporterUId, a, RoleKind.SUPPORTER)).isZero();
            assertThat(leftCount(supporterUId, a)).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-14: PENDING 申請だけの人の /follow は 204 で申請が削除される")
        void PENDINGのみのfollow解除は204で申請削除(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            setAuthentication(pendingOnlyId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", a.slug()))
                    .andExpect(status().isNoContent());

            reload();
            assertThat(pendingCount(pendingOnlyId, a)).isZero();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-14: 所属もフォローも無い人の /follow は 404（設計書「組織フォロー解除フロー」手順2）")
        void 無所属のfollow解除は404(Kind kind) throws Exception {
            setAuthentication(outsiderId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", scopeA(kind).slug()))
                    .andExpect(status().isNotFound());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("検分修繕: MEMBER の正規所属に PENDING 申請が併存しても /follow は 404 で所属・申請とも不変")
        void MEMBERにPENDING申請が併存しても404で両方不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            // 正規所属＋申請併存の人はこのテスト内だけで作る（共通 setUp の「スコープ A の ADMIN は
            // adminId 1人」という前提を、他テスト（最後の ADMIN の /me は 409）のために崩さない）。
            Long memberWithPendingId = insertUser("cmp0835-member-pending@example.com");
            MembershipTestHelper.insertMembership(em, memberWithPendingId, a.kind().scopeType(), a.id(), RoleKind.MEMBER);
            insertPendingApplication(a, memberWithPendingId);
            em.flush();
            em.clear();
            setAuthentication(memberWithPendingId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", a.slug()))
                    .andExpect(status().isNotFound());

            reload();
            assertThat(activeCount(memberWithPendingId, a, RoleKind.MEMBER)).isEqualTo(1);
            assertThat(leftCount(memberWithPendingId, a)).isZero();
            assertThat(pendingCount(memberWithPendingId, a)).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("検分修繕: ADMIN の正規所属＋権限割当＋PENDING 申請が併存しても /follow は 404 で全て不変")
        void ADMINにPENDING申請と権限割当が併存しても404で全て不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            // 2人目の ADMIN はこのテスト内だけで作る（共通 setUp に置くと adminId が「最後の ADMIN」で
            // なくなり、「最後の ADMIN の /me は 409」の回帰テストが正常実装でも 409 にならない）。
            Long adminWithPendingId = insertUser("cmp0835-admin-pending@example.com");
            MembershipTestHelper.insertMembership(em, adminWithPendingId, a.kind().scopeType(), a.id(), RoleKind.MEMBER);
            insertAdminRole(adminWithPendingId, a);
            insertPendingApplication(a, adminWithPendingId);
            Long groupId = insertPermissionGroup(a);
            insertUserPermissionGroup(adminWithPendingId, groupId);
            em.flush();
            em.clear();
            setAuthentication(adminWithPendingId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", a.slug()))
                    .andExpect(status().isNotFound());

            reload();
            assertThat(activeCount(adminWithPendingId, a, RoleKind.MEMBER)).isEqualTo(1);
            assertThat(leftCount(adminWithPendingId, a)).isZero();
            assertThat(pendingCount(adminWithPendingId, a)).isEqualTo(1);
            assertThat(userRoleCount(adminWithPendingId, a)).isEqualTo(1);
            assertThat(userPermissionGroupCount(adminWithPendingId, groupId)).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-14: 退会済み SUPPORTER 履歴だけの人の /follow は 404 で履歴は不変")
        void 退会済み応援者のfollow解除は404で履歴不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            String leftAtBefore = leftAtOf(formerSupporterId, a);
            setAuthentication(formerSupporterId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", a.slug()))
                    .andExpect(status().isNotFound());

            reload();
            assertThat(leftAtOf(formerSupporterId, a)).isEqualTo(leftAtBefore);
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AC-15: 越境・同一数値 ID・未認証
    // ═════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AC-15: 他スコープへ波及しない・未認証は 401")
    class Ac15CrossScope {

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-15: A の応援者 U が B の /me を叩くと 404 で B の応援者 V も U 自身も不変")
        void 他スコープのmeは404でVもUも不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            Scope b = scopeB(kind);
            setAuthentication(supporterUId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/me", b.slug()))
                    .andExpect(status().isNotFound());

            reload();
            assertThat(activeCount(supporterVId, b, RoleKind.SUPPORTER)).isEqualTo(1);
            assertThat(activeCount(supporterUId, a, RoleKind.SUPPORTER)).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-15: A の応援者 U が B の /follow を叩くと 404 で B の応援者 V も U 自身も不変")
        void 他スコープのfollow解除は404でVもUも不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            Scope b = scopeB(kind);
            setAuthentication(supporterUId);

            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", b.slug()))
                    .andExpect(status().isNotFound());

            reload();
            assertThat(activeCount(supporterVId, b, RoleKind.SUPPORTER)).isEqualTo(1);
            assertThat(activeCount(supporterUId, a, RoleKind.SUPPORTER)).isEqualTo(1);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("AC-15: 未認証の /me・/follow は 401 で所属・申請は不変")
        void 未認証は401で不変(Kind kind) throws Exception {
            Scope a = scopeA(kind);
            SecurityContextHolder.clearContext();

            mockMvc.perform(delete(kind.basePath + "/{slug}/me", a.slug()))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(delete(kind.basePath + "/{slug}/follow", a.slug()))
                    .andExpect(status().isUnauthorized());

            reload();
            assertThat(activeCount(supporterUId, a, RoleKind.SUPPORTER)).isEqualTo(1);
            assertThat(activeCount(memberId, a, RoleKind.MEMBER)).isEqualTo(1);
            assertThat(pendingCount(pendingOnlyId, a)).isEqualTo(1);
        }

        @Test
        @DisplayName("AC-15: 同じ数値IDの組織とチーム — チーム応援者が同ID組織の /follow・/me を叩いても 404 でチーム所属は不変")
        void 同一数値IDの組織操作はチーム所属に波及しない() throws Exception {
            SameIdPair pair = newSameIdPair();
            Long teamSupporter = insertUser("cmp0835-sameid-team-supporter@example.com");
            MembershipTestHelper.insertMembership(em, teamSupporter, ScopeType.TEAM, pair.team().id(), RoleKind.SUPPORTER);
            em.flush();
            em.clear();
            setAuthentication(teamSupporter);

            mockMvc.perform(delete(Kind.ORGANIZATION.basePath + "/{slug}/follow", pair.org().slug()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete(Kind.ORGANIZATION.basePath + "/{slug}/me", pair.org().slug()))
                    .andExpect(status().isNotFound());

            reload();
            assertThat(activeCount(teamSupporter, pair.team(), RoleKind.SUPPORTER)).isEqualTo(1);
            assertThat(leftCount(teamSupporter, pair.team())).isZero();
        }

        @Test
        @DisplayName("AC-15: 同じ数値IDの組織とチーム — 組織応援者が同IDチームの /follow・/me を叩いても 404 で組織所属は不変")
        void 同一数値IDのチーム操作は組織所属に波及しない() throws Exception {
            SameIdPair pair = newSameIdPair();
            Long orgSupporter = insertUser("cmp0835-sameid-org-supporter@example.com");
            MembershipTestHelper.insertMembership(
                    em, orgSupporter, ScopeType.ORGANIZATION, pair.org().id(), RoleKind.SUPPORTER);
            em.flush();
            em.clear();
            setAuthentication(orgSupporter);

            mockMvc.perform(delete(Kind.TEAM.basePath + "/{slug}/follow", pair.team().slug()))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete(Kind.TEAM.basePath + "/{slug}/me", pair.team().slug()))
                    .andExpect(status().isNotFound());

            reload();
            assertThat(activeCount(orgSupporter, pair.org(), RoleKind.SUPPORTER)).isEqualTo(1);
            assertThat(leftCount(orgSupporter, pair.org())).isZero();
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private record SameIdPair(Scope org, Scope team) {
    }

    private Scope scopeA(Kind kind) {
        return kind == Kind.ORGANIZATION ? orgA : teamA;
    }

    private Scope scopeB(Kind kind) {
        return kind == Kind.ORGANIZATION ? orgB : teamB;
    }

    private void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    /** サービス側の書き込みを確定させ、一次キャッシュを捨てて DB から読み直す。 */
    private void reload() {
        em.flush();
        em.clear();
    }

    /** 組織とチームを同じ数値 ID で作る（どちらの表でも未使用の ID を選ぶ）。 */
    private SameIdPair newSameIdPair() {
        long maxOrg = ((Number) em.createNativeQuery("SELECT COALESCE(MAX(id), 0) FROM organizations")
                .getSingleResult()).longValue();
        long maxTeam = ((Number) em.createNativeQuery("SELECT COALESCE(MAX(id), 0) FROM teams")
                .getSingleResult()).longValue();
        long sameId = Math.max(maxOrg, maxTeam) + 1000L;
        Scope org = newScope(Kind.ORGANIZATION, "応援退出試練同ID組織", sameId);
        Scope team = newScope(Kind.TEAM, "応援退出試練同IDチーム", sameId);
        assertThat(org.id()).isEqualTo(team.id());
        return new SameIdPair(org, team);
    }

    /**
     * 組織またはチームを PUBLIC・受け入れ有効で 1 件作る。
     *
     * @param explicitId null なら自動採番、非 null ならその ID で INSERT する
     */
    private Scope newScope(Kind kind, String name, Long explicitId) {
        String idCol = explicitId == null ? "" : "id, ";
        String idVal = explicitId == null ? "" : ":id, ";
        jakarta.persistence.Query q;
        if (kind == Kind.ORGANIZATION) {
            q = em.createNativeQuery(
                    "INSERT INTO organizations (" + idCol + "name, org_type, visibility, hierarchy_visibility, "
                            + "supporter_enabled, version, slug, created_at, updated_at) "
                            + "VALUES (" + idVal + ":name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                            + "CONCAT('cmp0835o-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())");
        } else {
            q = em.createNativeQuery(
                    "INSERT INTO teams (" + idCol + "name, visibility, supporter_enabled, version, member_count, slug, "
                            + "created_at, updated_at) "
                            + "VALUES (" + idVal + ":name, 'PUBLIC', 1, 0, 0, "
                            + "CONCAT('cmp0835t-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())");
        }
        q.setParameter("name", name);
        if (explicitId != null) {
            q.setParameter("id", explicitId);
        }
        q.executeUpdate();
        String table = kind == Kind.ORGANIZATION ? "organizations" : "teams";
        Object[] row = (Object[]) em.createNativeQuery("SELECT id, slug FROM " + table + " WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult();
        return new Scope(kind, ((Number) row[0]).longValue(), (String) row[1]);
    }

    private Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users ("
                                + "email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, "
                                + "created_at, updated_at) "
                                + "VALUES (:email, '応援退出', 'テスト', '応援退出テスト', 'ACTIVE', "
                                + "1, 1, 1, "
                                + "'NOBODY', 'ANYONE', 1, "
                                + "'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, "
                                + "NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email)
                .getSingleResult()).longValue();
    }

    /** ADMIN の権限ロール（user_roles）を張る。組織なら organization_id、チームなら team_id。 */
    private void insertAdminRole(Long userId, Scope scope) {
        if (scope.kind() == Kind.ORGANIZATION) {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", null, scope.id());
        } else {
            MembershipTestHelper.insertUserRole(em, userId, "ADMIN", scope.id(), null);
        }
    }

    /** 1 日前に自主退会済みの所属履歴（left_at 非 NULL）を 1 行作る。 */
    private void insertLeftMembership(Long userId, Scope scope, RoleKind roleKind) {
        em.createNativeQuery(
                        "INSERT INTO memberships ("
                                + "user_id, scope_type, scope_id, role_kind, "
                                + "joined_at, left_at, leave_reason, invited_by, "
                                + "created_at, updated_at) "
                                + "VALUES (:uid, :st, :sid, :rk, "
                                + "NOW() - INTERVAL 30 DAY, NOW() - INTERVAL 1 DAY, 'SELF', NULL, "
                                + "NOW(), NOW())")
                .setParameter("uid", userId)
                .setParameter("st", scope.kind().scopeType().name())
                .setParameter("sid", scope.id())
                .setParameter("rk", roleKind.name())
                .executeUpdate();
    }

    private void insertPendingApplication(Scope scope, Long applicantUserId) {
        em.createNativeQuery(
                        "INSERT INTO supporter_applications (scope_type, scope_id, user_id, message, status, "
                                + "created_at, updated_at) "
                                + "VALUES (:st, :sid, :uid, '応援退出試練', :status, NOW(), NOW())")
                .setParameter("st", scope.kind().name())
                .setParameter("sid", scope.id())
                .setParameter("uid", applicantUserId)
                .setParameter("status", SupporterApplicationStatus.PENDING.name())
                .executeUpdate();
    }

    private long activeCount(Long userId, Scope scope, RoleKind roleKind) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM memberships WHERE user_id = :uid AND scope_type = :st "
                                + "AND scope_id = :sid AND role_kind = :rk AND left_at IS NULL")
                .setParameter("uid", userId)
                .setParameter("st", scope.kind().scopeType().name())
                .setParameter("sid", scope.id())
                .setParameter("rk", roleKind.name())
                .getSingleResult()).longValue();
    }

    private long leftCount(Long userId, Scope scope) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM memberships WHERE user_id = :uid AND scope_type = :st "
                                + "AND scope_id = :sid AND left_at IS NOT NULL")
                .setParameter("uid", userId)
                .setParameter("st", scope.kind().scopeType().name())
                .setParameter("sid", scope.id())
                .getSingleResult()).longValue();
    }

    /** 退会済み履歴の left_at を文字列で取る（不変であることの比較用）。 */
    private String leftAtOf(Long userId, Scope scope) {
        Object v = em.createNativeQuery(
                        "SELECT CAST(MAX(left_at) AS CHAR) FROM memberships WHERE user_id = :uid AND scope_type = :st "
                                + "AND scope_id = :sid AND left_at IS NOT NULL")
                .setParameter("uid", userId)
                .setParameter("st", scope.kind().scopeType().name())
                .setParameter("sid", scope.id())
                .getSingleResult();
        return Objects.toString(v, null);
    }

    private long userRoleCount(Long userId, Scope scope) {
        String col = scope.kind() == Kind.ORGANIZATION ? "organization_id" : "team_id";
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM user_roles WHERE user_id = :uid AND " + col + " = :sid")
                .setParameter("uid", userId)
                .setParameter("sid", scope.id())
                .getSingleResult()).longValue();
    }

    /** 検分修繕: 権限グループを 1 件作る（組織/チーム双方対応）。 */
    private Long insertPermissionGroup(Scope scope) {
        String scopeCol = scope.kind() == Kind.ORGANIZATION ? "organization_id" : "team_id";
        em.createNativeQuery(
                        "INSERT INTO permission_groups (" + scopeCol + ", target_role, name, created_at, updated_at) "
                                + "VALUES (:sid, 'MEMBER', '応援退出試練権限グループ', NOW(), NOW())")
                .setParameter("sid", scope.id())
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM permission_groups WHERE " + scopeCol + " = :sid ORDER BY id DESC LIMIT 1")
                .setParameter("sid", scope.id())
                .getSingleResult()).longValue();
    }

    /** 検分修繕: ユーザーへ権限グループを割り当てる。 */
    private void insertUserPermissionGroup(Long userId, Long groupId) {
        em.createNativeQuery(
                        "INSERT INTO user_permission_groups (user_id, group_id, created_at) "
                                + "VALUES (:uid, :gid, NOW())")
                .setParameter("uid", userId)
                .setParameter("gid", groupId)
                .executeUpdate();
    }

    private long userPermissionGroupCount(Long userId, Long groupId) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM user_permission_groups WHERE user_id = :uid AND group_id = :gid")
                .setParameter("uid", userId)
                .setParameter("gid", groupId)
                .getSingleResult()).longValue();
    }

    private long pendingCount(Long userId, Scope scope) {
        return ((Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM supporter_applications WHERE user_id = :uid AND scope_type = :st "
                                + "AND scope_id = :sid AND status = 'PENDING'")
                .setParameter("uid", userId)
                .setParameter("st", scope.kind().name())
                .setParameter("sid", scope.id())
                .getSingleResult()).longValue();
    }
}
