package com.mannschaft.app.member.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.PageType;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.member.repository.TeamPageRepository;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * PR #3387 検分（4巡目・P2）: {@link TeamPageService#isPageAdmin} は package-private のため、
 * 同一パッケージ（{@code com.mannschaft.app.member.service}）からでないと直接テストできない
 * （既存の {@code TeamPageServiceTest} は {@code com.mannschaft.app.member} パッケージのため
 * このメソッドへアクセスできず、これまで直接のテストが存在しなかった）。
 *
 * <p>検分指摘: {@code isPageAdmin} は {@code isAdminOrAbove} のみを見ており SYSTEM_ADMIN を含まない。
 * 一方 {@link TeamPageService#checkPageMembershipOrNotFound} は組織スコープで SYSTEM_ADMIN を
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
            + "（checkPageMembershipOrNotFound の SYSTEM_ADMIN バイパスと矛盾させない）")
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
}
