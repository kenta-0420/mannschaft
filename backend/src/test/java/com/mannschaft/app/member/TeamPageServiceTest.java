package com.mannschaft.app.member;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.PageVisibility;
import com.mannschaft.app.member.dto.CreateTeamPageRequest;
import com.mannschaft.app.member.dto.TeamPageResponse;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.member.repository.TeamPageRepository;
import com.mannschaft.app.member.repository.TeamPageSectionRepository;
import com.mannschaft.app.member.service.MemberSubtabVisibilityService;
import com.mannschaft.app.member.service.TeamPageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TeamPageService 単体テスト")
class TeamPageServiceTest {

    @Mock private TeamPageRepository pageRepository;
    @Mock private TeamPageSectionRepository sectionRepository;
    @Mock private MemberProfileRepository profileRepository;
    @Mock private MemberMapper memberMapper;
    @Mock private AccessControlService accessControlService;
    @Mock private MemberSubtabVisibilityService memberSubtabVisibilityService;
    @InjectMocks private TeamPageService service;

    private static final Long ORG_ID = 500L;
    private static final Long ACTOR_ID = 999L;

    @Nested
    @DisplayName("createPage")
    class CreatePage {

        @Test
        @DisplayName("正常系: ページが作成される")
        void 作成_正常_保存() {
            // Given
            CreateTeamPageRequest req = new CreateTeamPageRequest(
                    1L, null, "メンバー紹介", "members", "MAIN", null,
                    null, null, "MEMBERS_ONLY");
            given(pageRepository.findByTeamIdAndPageType(1L, PageType.MAIN)).willReturn(Optional.empty());
            given(pageRepository.existsByTeamIdAndSlug(1L, "members")).willReturn(false);
            given(pageRepository.save(any(TeamPageEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(memberMapper.toTeamPageResponse(any(TeamPageEntity.class)))
                    .willReturn(new TeamPageResponse(1L, 1L, null, "メンバー紹介", "members",
                            "MAIN", null, null, null, "MEMBERS_ONLY", "DRAFT", false, 0, null, null, null, null, null));

            // When
            TeamPageResponse result = service.createPage(100L, req);

            // Then
            assertThat(result.getTitle()).isEqualTo("メンバー紹介");
            verify(pageRepository).save(any(TeamPageEntity.class));
        }

        @Test
        @DisplayName("異常系: メインページ重複でMEMBER_007例外")
        void 作成_メイン重複_例外() {
            // Given
            CreateTeamPageRequest req = new CreateTeamPageRequest(
                    1L, null, "メンバー紹介", "members", "MAIN", null,
                    null, null, null);
            given(pageRepository.findByTeamIdAndPageType(1L, PageType.MAIN))
                    .willReturn(Optional.of(TeamPageEntity.builder().teamId(1L)
                            .title("既存").slug("existing").pageType(PageType.MAIN).build()));

            // When / Then
            assertThatThrownBy(() -> service.createPage(100L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_007"));
        }

        @Test
        @DisplayName("異常系: スラッグ重複でMEMBER_005例外")
        void 作成_スラッグ重複_例外() {
            // Given
            CreateTeamPageRequest req = new CreateTeamPageRequest(
                    1L, null, "年度ページ", "members", "YEARLY", (short) 2025,
                    null, null, null);
            given(pageRepository.existsByTeamIdAndSlug(1L, "members")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.createPage(100L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_005"));
        }
    }

    @Nested
    @DisplayName("getPage")
    class GetPage {

        @Test
        @DisplayName("異常系: ページ不在でMEMBER_001例外")
        void 取得_不在_例外() {
            // Given
            given(pageRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getPage(999L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_001"));
        }
    }

    @Nested
    @DisplayName("CMP-260919-1140 Phase 1: getPage 合成ルール（外側の門×内側の扉）")
    class GetPageCompositionRule {

        @Test
        @DisplayName("下書き(DRAFT)ページは ADMIN 以外に見せない → MEMBER_001（404 相当・存在秘匿）")
        void 下書き_非管理者_404秘匿() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .organizationId(ORG_ID).title("下書き").slug("draft-page").pageType(PageType.YEARLY)
                    .build(); // status デフォルト DRAFT
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);

            assertThatThrownBy(() -> service.getPage(ACTOR_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_001"));
            org.mockito.Mockito.verifyNoInteractions(memberSubtabVisibilityService);
        }

        @Test
        @DisplayName("ADMIN は下書きページも閲覧できる")
        void 下書き_管理者_閲覧可() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .organizationId(ORG_ID).title("下書き").slug("draft-page").pageType(PageType.YEARLY)
                    .build();
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(1L)).willReturn(List.of());
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(1L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(any())).willReturn(List.of());
            given(memberMapper.toMemberProfileResponseList(any())).willReturn(List.of());
            given(memberMapper.toTeamPageDetailResponse(any(), any(), any())).willReturn(
                    new TeamPageResponse(1L, null, ORG_ID, "下書き", "draft-page",
                            "YEARLY", null, null, null, "MEMBERS_ONLY", "DRAFT", false, 0, null, null, null, null, null));

            TeamPageResponse result = service.getPage(ACTOR_ID, 1L);
            assertThat(result.getSlug()).isEqualTo("draft-page");
        }

        @Test
        @DisplayName("公開済みページ: サブタブ min_role の外側の門を通過できれば閲覧可（既定値=MEMBER）")
        void 公開済み_門通過_閲覧可() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .organizationId(ORG_ID).title("紹介").slug("intro").pageType(PageType.MAIN)
                    .status(PageStatus.PUBLISHED)
                    .build(); // visibility デフォルト MEMBERS_ONLY
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            // assertViewable は正常時 void（何もしない）
            // ページ個別 visibility=MEMBERS_ONLY を満たすため、メンバーであることをスタブする
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(true);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(1L)).willReturn(List.of());
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(1L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(any())).willReturn(List.of());
            given(memberMapper.toMemberProfileResponseList(any())).willReturn(List.of());
            given(memberMapper.toTeamPageDetailResponse(any(), any(), any())).willReturn(
                    new TeamPageResponse(1L, null, ORG_ID, "紹介", "intro",
                            "MAIN", null, null, null, "MEMBERS_ONLY", "PUBLISHED", false, 0, null, null, null, null, null));

            TeamPageResponse result = service.getPage(ACTOR_ID, 1L);

            assertThat(result.getSlug()).isEqualTo("intro");
            verify(memberSubtabVisibilityService).assertViewable(
                    ACTOR_ID, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_PROFILES);
        }

        @Test
        @DisplayName("公開済みページ: 外側の門で拒否されたら MEMBER_001（403 ではなく 404 秘匿を維持）")
        void 公開済み_門拒否_404秘匿() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .organizationId(ORG_ID).title("紹介").slug("intro").pageType(PageType.MAIN)
                    .status(PageStatus.PUBLISHED)
                    .build();
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(memberSubtabVisibilityService)
                    .assertViewable(ACTOR_ID, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_PROFILES);

            assertThatThrownBy(() -> service.getPage(ACTOR_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_001"));
        }

        @Test
        @DisplayName("検分修正(P1): 非会員×サブタブPUBLIC×ページMEMBERS_ONLY → 拒否(MEMBER_001・404秘匿)")
        void サブタブPUBLIC_ページMEMBERS_ONLY_非会員_拒否() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .organizationId(ORG_ID).title("紹介").slug("intro").pageType(PageType.MAIN)
                    .status(PageStatus.PUBLISHED).visibility(PageVisibility.MEMBERS_ONLY)
                    .build();
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            // サブタブは PUBLIC 設定 → assertViewable は通過（何もしない）
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);

            assertThatThrownBy(() -> service.getPage(ACTOR_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_001"));
        }

        @Test
        @DisplayName("検分指摘A(2巡目・P1): SUPPORTER×サブタブSUPPORTER×ページMEMBERS_ONLY → 拒否"
                + "（isMember()ではなくロール閾値MEMBER以上で判定する回帰）")
        void サブタブSUPPORTER_ページMEMBERS_ONLY_SUPPORTER_拒否() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .organizationId(ORG_ID).title("紹介").slug("intro").pageType(PageType.MAIN)
                    .status(PageStatus.PUBLISHED).visibility(PageVisibility.MEMBERS_ONLY)
                    .build();
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            // サブタブは SUPPORTER 設定 → 外側の門(assertViewable)は SUPPORTER でも通過（何もしない）が、
            // ページ個別 visibility=MEMBERS_ONLY のロール閾値（MEMBER 以上）で拒否されるべき。
            // isMember() は SUPPORTER も所属者として true を返すため、ここを isMember() のまま判定すると
            // 誤って許可してしまう（検分指摘A）。hasRoleOrAbove(...,"MEMBER") で SUPPORTER を除外する。
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER"))
                    .willReturn(false);

            assertThatThrownBy(() -> service.getPage(ACTOR_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_001"));
            org.mockito.Mockito.verify(accessControlService, never())
                    .isMember(ACTOR_ID, ORG_ID, "ORGANIZATION");
        }

        @Test
        @DisplayName("検分修正(P1): メンバー×サブタブPUBLIC×ページMEMBERS_ONLY → 許可")
        void サブタブPUBLIC_ページMEMBERS_ONLY_メンバー_許可() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .organizationId(ORG_ID).title("紹介").slug("intro").pageType(PageType.MAIN)
                    .status(PageStatus.PUBLISHED).visibility(PageVisibility.MEMBERS_ONLY)
                    .build();
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(true);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(1L)).willReturn(List.of());
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(1L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(any())).willReturn(List.of());
            given(memberMapper.toMemberProfileResponseList(any())).willReturn(List.of());
            given(memberMapper.toTeamPageDetailResponse(any(), any(), any())).willReturn(
                    new TeamPageResponse(1L, null, ORG_ID, "紹介", "intro",
                            "MAIN", null, null, null, "MEMBERS_ONLY", "PUBLISHED", false, 0, null, null, null, null, null));

            TeamPageResponse result = service.getPage(ACTOR_ID, 1L);
            assertThat(result.getSlug()).isEqualTo("intro");
        }

        @Test
        @DisplayName("検分修正(P1): 非会員×サブタブPUBLIC×ページPUBLIC → 許可")
        void サブタブPUBLIC_ページPUBLIC_非会員_許可() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .organizationId(ORG_ID).title("紹介").slug("intro").pageType(PageType.MAIN)
                    .status(PageStatus.PUBLISHED).visibility(PageVisibility.PUBLIC)
                    .build();
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(1L)).willReturn(List.of());
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(1L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(any())).willReturn(List.of());
            given(memberMapper.toMemberProfileResponseList(any())).willReturn(List.of());
            given(memberMapper.toTeamPageDetailResponse(any(), any(), any())).willReturn(
                    new TeamPageResponse(1L, null, ORG_ID, "紹介", "intro",
                            "MAIN", null, null, null, "PUBLIC", "PUBLISHED", false, 0, null, null, null, null, null));

            TeamPageResponse result = service.getPage(ACTOR_ID, 1L);

            assertThat(result.getSlug()).isEqualTo("intro");
            // ページ visibility=PUBLIC のため isMember は問われない（呼ばれても呼ばれなくても結果に影響しない）
        }

        @Test
        @DisplayName("回帰: TEAM スコープは Phase1 対象外のため isMember のみで判定される（ページ visibility は問わない）")
        void TEAMスコープ_isMemberのみで判定_回帰() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .teamId(1L).title("紹介").slug("intro").pageType(PageType.MAIN)
                    .status(PageStatus.PUBLISHED).visibility(PageVisibility.MEMBERS_ONLY)
                    .build();
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, 1L, "TEAM")).willReturn(false);
            given(accessControlService.isMember(ACTOR_ID, 1L, "TEAM")).willReturn(true);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(1L)).willReturn(List.of());
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(1L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(any())).willReturn(List.of());
            given(memberMapper.toMemberProfileResponseList(any())).willReturn(List.of());
            given(memberMapper.toTeamPageDetailResponse(any(), any(), any())).willReturn(
                    new TeamPageResponse(1L, 1L, null, "紹介", "intro",
                            "MAIN", null, null, null, "MEMBERS_ONLY", "PUBLISHED", false, 0, null, null, null, null, null));

            TeamPageResponse result = service.getPage(ACTOR_ID, 1L);

            assertThat(result.getSlug()).isEqualTo("intro");
            org.mockito.Mockito.verifyNoInteractions(memberSubtabVisibilityService);
        }

        @Test
        @DisplayName("検分修正(3巡目・P2): 所属のないSYSTEM_ADMINは下書きページも閲覧できる"
                + "（assertViewableのSYSTEM_ADMINバイパスと揃える。設計書F06.6 §9.1）")
        void 下書き_SYSTEM_ADMIN_閲覧可() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .organizationId(ORG_ID).title("下書き").slug("draft-page").pageType(PageType.YEARLY)
                    .build(); // status デフォルト DRAFT
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(true);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(1L)).willReturn(List.of());
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(1L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(any())).willReturn(List.of());
            given(memberMapper.toMemberProfileResponseList(any())).willReturn(List.of());
            given(memberMapper.toTeamPageDetailResponse(any(), any(), any())).willReturn(
                    new TeamPageResponse(1L, null, ORG_ID, "下書き", "draft-page",
                            "YEARLY", null, null, null, "MEMBERS_ONLY", "DRAFT", false, 0, null, null, null, null, null));

            TeamPageResponse result = service.getPage(ACTOR_ID, 1L);
            assertThat(result.getSlug()).isEqualTo("draft-page");
        }

        @Test
        @DisplayName("検分修正(3巡目・P2): 所属のないSYSTEM_ADMINはMEMBERS_ONLYの公開済みページも閲覧できる"
                + "（assertViewableのSYSTEM_ADMINバイパスと揃える。設計書F06.6 §9.1）")
        void 公開済みMEMBERS_ONLY_SYSTEM_ADMIN_閲覧可() {
            TeamPageEntity entity = TeamPageEntity.builder()
                    .organizationId(ORG_ID).title("紹介").slug("intro").pageType(PageType.MAIN)
                    .status(PageStatus.PUBLISHED).visibility(PageVisibility.MEMBERS_ONLY)
                    .build();
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(true);
            given(sectionRepository.findByTeamPageIdOrderBySortOrder(1L)).willReturn(List.of());
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(1L)).willReturn(List.of());
            given(memberMapper.toSectionResponseList(any())).willReturn(List.of());
            given(memberMapper.toMemberProfileResponseList(any())).willReturn(List.of());
            given(memberMapper.toTeamPageDetailResponse(any(), any(), any())).willReturn(
                    new TeamPageResponse(1L, null, ORG_ID, "紹介", "intro",
                            "MAIN", null, null, null, "MEMBERS_ONLY", "PUBLISHED", false, 0, null, null, null, null, null));

            TeamPageResponse result = service.getPage(ACTOR_ID, 1L);

            assertThat(result.getSlug()).isEqualTo("intro");
            org.mockito.Mockito.verifyNoInteractions(memberSubtabVisibilityService);
        }
    }

    @Nested
    @DisplayName("CMP-260919-1140 Phase 1: listPages 合成ルール")
    class ListPagesCompositionRule {

        @Test
        @DisplayName("組織スコープ: 外側の門で拒否されたら例外伝播（非会員の一覧列挙を遮断）")
        void 組織_門拒否_例外伝播() {
            Pageable pageable = PageRequest.of(0, 10);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(memberSubtabVisibilityService)
                    .assertViewable(ACTOR_ID, ScopeType.ORGANIZATION, ORG_ID, MemberSubtabKey.MEMBER_PROFILES);

            assertThatThrownBy(() -> service.listPages(ACTOR_ID, null, ORG_ID, pageable))
                    .isInstanceOf(BusinessException.class);
            org.mockito.Mockito.verifyNoInteractions(pageRepository);
        }

        @Test
        @DisplayName("組織スコープ: 非管理者（メンバー）は PUBLISHED のみ取得（下書きは一覧から除外）")
        void 組織_非管理者_公開済みのみ() {
            Pageable pageable = PageRequest.of(0, 10);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(true);
            Page<TeamPageEntity> emptyPage = new PageImpl<>(List.of());
            given(pageRepository.findByOrganizationIdAndStatusOrderBySortOrder(ORG_ID, PageStatus.PUBLISHED, pageable))
                    .willReturn(emptyPage);

            service.listPages(ACTOR_ID, null, ORG_ID, pageable);

            verify(pageRepository).findByOrganizationIdAndStatusOrderBySortOrder(ORG_ID, PageStatus.PUBLISHED, pageable);
            verify(pageRepository, never()).findByOrganizationIdOrderBySortOrder(anyLong(), any());
        }

        @Test
        @DisplayName("検分修正(P1): 組織スコープ・非会員（サブタブPUBLIC通過）はページ個別visibility=PUBLICのみ取得")
        void 組織_非会員_サブタブPUBLIC通過_PUBLICページのみ() {
            Pageable pageable = PageRequest.of(0, 10);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            given(accessControlService.hasRoleOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION", "MEMBER")).willReturn(false);
            Page<TeamPageEntity> emptyPage = new PageImpl<>(List.of());
            given(pageRepository.findByOrganizationIdAndStatusAndVisibilityOrderBySortOrder(
                    ORG_ID, PageStatus.PUBLISHED, PageVisibility.PUBLIC, pageable))
                    .willReturn(emptyPage);

            service.listPages(ACTOR_ID, null, ORG_ID, pageable);

            verify(pageRepository).findByOrganizationIdAndStatusAndVisibilityOrderBySortOrder(
                    ORG_ID, PageStatus.PUBLISHED, PageVisibility.PUBLIC, pageable);
            verify(pageRepository, never())
                    .findByOrganizationIdAndStatusOrderBySortOrder(anyLong(), any(), any());
            verify(pageRepository, never()).findByOrganizationIdOrderBySortOrder(anyLong(), any());
        }

        @Test
        @DisplayName("組織スコープ: ADMIN は下書き含む全ページを取得")
        void 組織_管理者_下書き含む全件() {
            Pageable pageable = PageRequest.of(0, 10);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            Page<TeamPageEntity> emptyPage = new PageImpl<>(List.of());
            given(pageRepository.findByOrganizationIdOrderBySortOrder(ORG_ID, pageable)).willReturn(emptyPage);

            service.listPages(ACTOR_ID, null, ORG_ID, pageable);

            verify(pageRepository).findByOrganizationIdOrderBySortOrder(ORG_ID, pageable);
            verify(pageRepository, never())
                    .findByOrganizationIdAndStatusOrderBySortOrder(anyLong(), any(), any());
        }

        @Test
        @DisplayName("検分修正(3巡目・P2): 組織スコープ・所属のないSYSTEM_ADMINも下書き含む全ページを取得"
                + "（assertViewableのSYSTEM_ADMINバイパスと揃える。設計書F06.6 §9.1）")
        void 組織_SYSTEM_ADMIN_下書き含む全件() {
            Pageable pageable = PageRequest.of(0, 10);
            given(accessControlService.isSystemAdmin(ACTOR_ID)).willReturn(true);
            given(accessControlService.isAdminOrAbove(ACTOR_ID, ORG_ID, "ORGANIZATION")).willReturn(false);
            Page<TeamPageEntity> emptyPage = new PageImpl<>(List.of());
            given(pageRepository.findByOrganizationIdOrderBySortOrder(ORG_ID, pageable)).willReturn(emptyPage);

            service.listPages(ACTOR_ID, null, ORG_ID, pageable);

            verify(pageRepository).findByOrganizationIdOrderBySortOrder(ORG_ID, pageable);
            verify(pageRepository, never())
                    .findByOrganizationIdAndStatusOrderBySortOrder(anyLong(), any(), any());
            verify(pageRepository, never())
                    .findByOrganizationIdAndStatusAndVisibilityOrderBySortOrder(anyLong(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("deletePage")
    class DeletePage {

        @Test
        @DisplayName("正常系: ページが論理削除される")
        void 削除_正常() {
            // Given
            TeamPageEntity entity = TeamPageEntity.builder()
                    .teamId(1L).title("テスト").slug("test").pageType(PageType.MAIN).build();
            given(pageRepository.findById(1L)).willReturn(Optional.of(entity));
            given(pageRepository.save(any(TeamPageEntity.class))).willReturn(entity);
            given(accessControlService.isAdminOrAbove(anyLong(), anyLong(), anyString())).willReturn(true);

            // When
            service.deletePage(999L, 1L);

            // Then
            verify(pageRepository).save(any(TeamPageEntity.class));
        }
    }
}
