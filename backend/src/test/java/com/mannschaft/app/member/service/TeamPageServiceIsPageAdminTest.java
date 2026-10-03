package com.mannschaft.app.member.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.MemberSubtabKey;
import com.mannschaft.app.member.PageStatus;
import com.mannschaft.app.member.PageType;
import com.mannschaft.app.member.PageVisibility;
import com.mannschaft.app.member.dto.TeamPageResponse;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.member.repository.TeamPageRepository;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * PR #3387 検分（4巡目・P2）: {@link TeamPageService#isPageAdmin} は package-private のため、
 * 同一パッケージ（{@code com.mannschaft.app.member.service}）からでないと直接テストできない
 * （既存の {@code TeamPageServiceTest} は {@code com.mannschaft.app.member} パッケージのため
 * このメソッドへアクセスできず、これまで直接のテストが存在しなかった）。
 *
 * <p>検分指摘: {@code isPageAdmin} は {@code isAdminOrAbove} のみを見ており SYSTEM_ADMIN を含まない。
 * 一方 {@link TeamPageService#checkPageViewableOrNotFound} は組織スコープで SYSTEM_ADMIN を
 * 無条件バイパスするため、所属のない SYSTEM_ADMIN が「閲覧はできるが非表示行が欠ける／
 * 非表示プロフィールの詳細が 404 になる」という矛盾を起こす。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TeamPageService#isPageAdmin 単体テスト")
class TeamPageServiceIsPageAdminTest {

    @Mock private TeamPageRepository pageRepository;
    @Mock private TeamPageSectionRepository sectionRepository;
    @Mock private MemberProfileRepository profileRepository;
    @Mock private MemberMapper memberMapper;
    @Mock private AccessControlService accessControlService;
    @Mock private MemberSubtabVisibilityService memberSubtabVisibilityService;
    @InjectMocks private TeamPageService service;

    private static final Long ORG_ID = 700L;
    private static final Long ACTOR_ID = 999L;

    @Test
    @DisplayName("所属のない SYSTEM_ADMIN は isPageAdmin=true になる"
            + "（checkPageViewableOrNotFound の SYSTEM_ADMIN バイパスと矛盾させない）")
    void 所属のないSYSTEM_ADMINはページ管理者扱いになる() {
        TeamPageEntity page = TeamPageEntity.builder()
                .organizationId(ORG_ID).title("テスト").slug("test").pageType(PageType.MAIN).build();
        given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(true);
        given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

        boolean result = service.isPageAdmin(ACTOR_ID, page);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("ADMIN/DEPUTY_ADMIN は従来通り isPageAdmin=true になる")
    void ADMIN以上はページ管理者扱いになる() {
        TeamPageEntity page = TeamPageEntity.builder()
                .organizationId(ORG_ID).title("テスト").slug("test").pageType(PageType.MAIN).build();
        given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

        boolean result = service.isPageAdmin(ACTOR_ID, page);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("SYSTEM_ADMIN でも ADMIN でもない一般会員は isPageAdmin=false のまま")
    void 一般会員はページ管理者扱いにならない() {
        TeamPageEntity page = TeamPageEntity.builder()
                .organizationId(ORG_ID).title("テスト").slug("test").pageType(PageType.MAIN).build();
        given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(false);
        given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

        boolean result = service.isPageAdmin(ACTOR_ID, page);

        assertThat(result).isFalse();
    }

    // ═════════════════════════════════════════════════════════════════════
    // PR #3387 試練（判定分離）: 以下は軍議書 gungi-3387.md の受け入れ条件に対応する。
    // 判定メソッドは package-private のため、このクラス（同一パッケージ）で直接検証する。
    // ═════════════════════════════════════════════════════════════════════

    private static final Long TEAM_ID = 70L;

    private static TeamPageEntity orgPage(PageStatus status, PageVisibility visibility) {
        return TeamPageEntity.builder()
                .organizationId(ORG_ID).title("紹介").slug("intro").pageType(PageType.MAIN)
                .status(status).visibility(visibility).build();
    }

    private static TeamPageEntity teamPage(PageStatus status, PageVisibility visibility) {
        return TeamPageEntity.builder()
                .teamId(TEAM_ID).title("紹介").slug("intro").pageType(PageType.MAIN)
                .status(status).visibility(visibility).build();
    }

    private static void assertMember001(Throwable ex) {
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo("MEMBER_001");
    }

    @Nested
    @DisplayName("A. checkPageMembershipOrNotFound は base（isMember || isAdminOrAbove）に戻す")
    class MembershipRestored {

        @Test
        @DisplayName("AC-01: ORG・非会員(OUT)・サブタブPUBLIC・PUB → 404(MEMBER_001)。サブタブ判定・isSystemAdmin・hasRoleOrAbove を呼ばない")
        void AC01_ORG_非会員_サブタブPUBLIC_公開ページ_404で_緩和を見ない() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);
            given(accessControlService.isMember(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            // サブタブ PUBLIC 相当: assertViewable は例外を投げない（既定の void スタブ）

            assertThatThrownBy(() -> service.checkPageMembershipOrNotFound(ACTOR_ID, page))
                    .satisfies(TeamPageServiceIsPageAdminTest::assertMember001);
            verifyNoInteractions(memberSubtabVisibilityService);
            verify(accessControlService, never()).isSystemAdmin(anyLong());
            verify(accessControlService, never()).hasRoleOrAbove(anyLong(), anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("AC-01b: ORG の SUPPORTER は（MEMBERS_ONLY ページでも）通る — 元どおり isMember で判定")
        void AC01b_ORG_SUPPORTER_会員限定ページでも通る() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.MEMBERS_ONLY);
            given(accessControlService.isMember(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);

            assertThatCode(() -> service.checkPageMembershipOrNotFound(ACTOR_ID, page)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-01b: ORG の MEMBER は DRAFT でも通る — 元どおり状態を見ない")
        void AC01b_ORG_MEMBER_下書きでも通る() {
            TeamPageEntity page = orgPage(PageStatus.DRAFT, PageVisibility.MEMBERS_ONLY);
            given(accessControlService.isMember(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatCode(() -> service.checkPageMembershipOrNotFound(ACTOR_ID, page)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-01b: 所属のない SYSTEM_ADMIN は 404(MEMBER_001) — 元どおりバイパスしない")
        void AC01b_所属のないSYSTEM_ADMINは404() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(true);
            given(accessControlService.isMember(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.checkPageMembershipOrNotFound(ACTOR_ID, page))
                    .satisfies(TeamPageServiceIsPageAdminTest::assertMember001);
        }
    }

    @Nested
    @DisplayName("B. checkPageViewableOrNotFound（閲覧経路の判定。HEAD の checkPageMembershipOrNotFound 本体を移す）")
    class Viewable {

        @Test
        @DisplayName("AC-02: ORG・OUT・サブタブPUBLIC・PUB → 通る")
        void AC02_ORG_非会員_サブタブPUBLIC_公開ページ_通る() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);

            assertThatCode(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .doesNotThrowAnyException();
            verify(memberSubtabVisibilityService).assertViewable(
                    ACTOR_ID, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_PROFILES);
        }

        @Test
        @DisplayName("AC-04: ORG・OUT・サブタブPUBLIC・MEMBERS_ONLY → 404(MEMBER_001)")
        void AC04_ORG_非会員_サブタブPUBLIC_会員限定_404() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.MEMBERS_ONLY);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);

            assertThatThrownBy(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .satisfies(TeamPageServiceIsPageAdminTest::assertMember001);
        }

        @Test
        @DisplayName("AC-05/07: ORG・サブタブ門で拒否（COMMON_002）→ 404(MEMBER_001) に畳む")
        void AC05_ORG_サブタブ門拒否_404に畳む() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(memberSubtabVisibilityService)
                    .assertViewable(ACTOR_ID, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_PROFILES);

            assertThatThrownBy(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .satisfies(TeamPageServiceIsPageAdminTest::assertMember001);
        }

        @Test
        @DisplayName("AC-06: ORG・SUPPORTER・サブタブSUPPORTER・MEMBERS_ONLY → 404(MEMBER_001)（MEMBER 以上が要る）")
        void AC06_ORG_SUPPORTER_会員限定_404() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.MEMBERS_ONLY);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isMember(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);

            assertThatThrownBy(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .satisfies(TeamPageServiceIsPageAdminTest::assertMember001);
        }

        @Test
        @DisplayName("AC-08: ORG・MEMBER・既定・MEMBERS_ONLY → 通る")
        void AC08_ORG_MEMBER_会員限定_通る() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.MEMBERS_ONLY);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(true);

            assertThatCode(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-09: ORG・MEMBER・DRAFT → 404(MEMBER_001)")
        void AC09_ORG_MEMBER_下書き_404() {
            TeamPageEntity page = orgPage(PageStatus.DRAFT, PageVisibility.PUBLIC);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isMember(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(true);

            assertThatThrownBy(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .satisfies(TeamPageServiceIsPageAdminTest::assertMember001);
        }

        @Test
        @DisplayName("AC-10: ORG・ADMIN/DEPUTY_ADMIN（isAdminOrAbove）は DRAFT でもサブタブ判定なしで通る")
        void AC10_ORG_管理者_下書きでも通る() {
            TeamPageEntity page = orgPage(PageStatus.DRAFT, PageVisibility.MEMBERS_ONLY);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(true);

            assertThatCode(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .doesNotThrowAnyException();
            verifyNoInteractions(memberSubtabVisibilityService);
        }

        @Test
        @DisplayName("AC-11: ORG・所属のない SYSTEM_ADMIN は DRAFT でも通る")
        void AC11_ORG_SYSTEM_ADMIN_下書きでも通る() {
            TeamPageEntity page = orgPage(PageStatus.DRAFT, PageVisibility.MEMBERS_ONLY);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(true);

            assertThatCode(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC-19: TEAM・MEMBER は DRAFT・MEMBERS_ONLY でも通り、サブタブ判定を呼ばない")
        void AC19_TEAM_MEMBER_下書き会員限定でも通る() {
            TeamPageEntity page = teamPage(PageStatus.DRAFT, PageVisibility.MEMBERS_ONLY);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isMember(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(true);

            assertThatCode(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .doesNotThrowAnyException();
            verifyNoInteractions(memberSubtabVisibilityService);
        }

        @Test
        @DisplayName("AC-19: TEAM・未所属者は 404(MEMBER_001)")
        void AC19_TEAM_未所属者_404() {
            TeamPageEntity page = teamPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isMember(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .satisfies(TeamPageServiceIsPageAdminTest::assertMember001);
            verifyNoInteractions(memberSubtabVisibilityService);
        }

        @Test
        @DisplayName("AC-19: TEAM・所属のない SYSTEM_ADMIN は 404(MEMBER_001)（TEAM のバイパスは Phase 3）")
        void AC19_TEAM_所属のないSYSTEM_ADMIN_404() {
            TeamPageEntity page = teamPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(true);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isMember(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(false);

            assertThatThrownBy(() -> service.checkPageViewableOrNotFound(ACTOR_ID, page))
                    .satisfies(TeamPageServiceIsPageAdminTest::assertMember001);
        }
    }

    @Nested
    @DisplayName("C. checkPageMemberRoleOrNotFound（操作経路の判定。MEMBER 以上でなければ 404）")
    class MemberRole {

        @Test
        @DisplayName("AC-13: MEMBER 以上（hasRoleOrAbove=true）は通り、サブタブ判定を呼ばない")
        void AC13_MEMBER以上は通る() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(true);

            assertThatCode(() -> service.checkPageMemberRoleOrNotFound(ACTOR_ID, page))
                    .doesNotThrowAnyException();
            verifyNoInteractions(memberSubtabVisibilityService);
        }

        @Test
        @DisplayName("AC-12/13: SUPPORTER・非会員（hasRoleOrAbove=false）はサブタブ PUBLIC・公開ページでも 404(MEMBER_001)")
        void AC12_MEMBER未満は404() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);
            given(accessControlService.isMember(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);

            assertThatThrownBy(() -> service.checkPageMemberRoleOrNotFound(ACTOR_ID, page))
                    .satisfies(TeamPageServiceIsPageAdminTest::assertMember001);
            verifyNoInteractions(memberSubtabVisibilityService);
        }

        @Test
        @DisplayName("AC-13: TEAM でも同じ判定（TEAM の SUPPORTER は 404）")
        void AC13_TEAM_SUPPORTERは404() {
            TeamPageEntity page = teamPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);
            given(accessControlService.isMember(ACTOR_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, TEAM_ID, "TEAM", "MEMBER")).willReturn(false);

            assertThatThrownBy(() -> service.checkPageMemberRoleOrNotFound(ACTOR_ID, page))
                    .satisfies(TeamPageServiceIsPageAdminTest::assertMember001);
        }
    }

    @Nested
    @DisplayName("D. 配線と可視性")
    class WiringAndModifiers {

        @Test
        @DisplayName("AC-22: 3つの判定メソッドはすべて package-private（D-1 番人の対象外に置く）")
        void AC22_判定メソッドはpackage_private() throws NoSuchMethodException {
            for (String name : List.of("checkPageMembershipOrNotFound", "checkPageViewableOrNotFound",
                    "checkPageMemberRoleOrNotFound")) {
                Method m = TeamPageService.class.getDeclaredMethod(name, Long.class, TeamPageEntity.class);
                int mod = m.getModifiers();
                assertThat(Modifier.isPublic(mod) || Modifier.isProtected(mod) || Modifier.isPrivate(mod))
                        .as(name + " は package-private であること")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("AC-14b(V1): getPage は checkPageViewableOrNotFound を1回呼び、checkPageMembershipOrNotFound を呼ばない")
        void AC14b_getPageは閲覧判定を呼ぶ() {
            TeamPageEntity page = orgPage(PageStatus.PUBLISHED, PageVisibility.PUBLIC);
            given(pageRepository.findById(1L)).willReturn(Optional.of(page));
            given(accessControlService.isAdminOrAbove(anyLong(), anyLong(), anyString())).willReturn(true);
            given(accessControlService.isMember(anyLong(), anyLong(), anyString())).willReturn(true);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(1L)).willReturn(List.of());
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(1L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(any())).willReturn(List.of());
            given(memberMapper.toMemberProfileResponseList(any())).willReturn(List.of());
            given(memberMapper.toTeamPageDetailResponse(any(), any(), any())).willReturn(
                    new TeamPageResponse(1L, null, ORG_ID, "紹介", "intro",
                            "MAIN", null, null, null, "PUBLIC", "PUBLISHED", false, 0, null, null, null, null, null));
            TeamPageService spied = spy(service);

            spied.getPage(ACTOR_ID, 1L);

            verify(spied, times(1)).checkPageViewableOrNotFound(ACTOR_ID, page);
            verify(spied, never()).checkPageMembershipOrNotFound(ACTOR_ID, page);
        }
    }
}
