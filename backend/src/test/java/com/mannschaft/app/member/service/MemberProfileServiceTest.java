package com.mannschaft.app.member.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.MemberMapper;
import com.mannschaft.app.member.dto.BulkCreateMemberRequest;
import com.mannschaft.app.member.dto.CopyMembersRequest;
import com.mannschaft.app.member.dto.CopyMembersResponse;
import com.mannschaft.app.member.dto.CreateMemberProfileRequest;
import com.mannschaft.app.member.dto.MemberLookupResponse;
import com.mannschaft.app.member.dto.MemberProfileResponse;
import com.mannschaft.app.member.dto.ReorderRequest;
import com.mannschaft.app.member.entity.MemberProfileEntity;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MemberProfileService 単体テスト")
class MemberProfileServiceTest {

    @Mock private MemberProfileRepository profileRepository;
    @Mock private TeamPageService pageService;
    @Mock private MemberMapper memberMapper;
    @InjectMocks private MemberProfileService service;

    @Nested
    @DisplayName("createProfile")
    class CreateProfile {

        @Test
        @DisplayName("正常系: プロフィールが作成される")
        void 作成_正常_保存() {
            // Given
            given(profileRepository.existsByTeamPageIdAndUserId(1L, 100L)).willReturn(false);
            given(profileRepository.save(any(MemberProfileEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(memberMapper.toMemberProfileResponse(any(MemberProfileEntity.class)))
                    .willReturn(new MemberProfileResponse(1L, 1L, 100L, "テスト太郎",
                            "001", null, null, null, null, 0, true, null, null));

            CreateMemberProfileRequest req = new CreateMemberProfileRequest(
                    1L, 100L, "テスト太郎", "001", null, null, null, null);

            // When
            MemberProfileResponse result = service.createProfile(999L, req);

            // Then
            assertThat(result.getDisplayName()).isEqualTo("テスト太郎");
            verify(profileRepository).save(any(MemberProfileEntity.class));
        }

        @Test
        @DisplayName("異常系: ユーザー重複でMEMBER_008例外")
        void 作成_重複_例外() {
            // Given
            given(profileRepository.existsByTeamPageIdAndUserId(1L, 100L)).willReturn(true);

            CreateMemberProfileRequest req = new CreateMemberProfileRequest(
                    1L, 100L, "テスト太郎", null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.createProfile(999L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_008"));
        }
    }

    @Nested
    @DisplayName("getProfile")
    class GetProfile {

        @Test
        @DisplayName("異常系: プロフィール不在でMEMBER_003例外")
        void 取得_不在_例外() {
            // Given
            given(profileRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.getProfile(999L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_003"));
        }

        @Test
        @DisplayName("検分修正(3巡目・P1): 非会員×サブタブPUBLIC通過×非表示プロフィール → MEMBER_003(404秘匿)")
        void 非会員_非表示プロフィール_404秘匿() {
            // Given: 非会員だが「紹介」サブタブの外側の門(PUBLIC設定)を通過してページ閲覧はできる
            // （checkPageMembershipOrNotFound は例外を投げない）。ただし ADMIN ではない。
            TeamPageEntity page = TeamPageEntity.builder().organizationId(500L).build();
            MemberProfileEntity entity = MemberProfileEntity.builder()
                    .teamPageId(10L).displayName("非表示太郎").isVisible(false).build();
            given(profileRepository.findById(1L)).willReturn(Optional.of(entity));
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(pageService.isPageAdmin(999L, page)).willReturn(false);

            // When / Then: 非表示プロフィールは、存在は漏らさず MEMBER_003（404秘匿）で拒否する
            assertThatThrownBy(() -> service.getProfile(999L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_003"));
        }

        @Test
        @DisplayName("検分修正(3巡目・P1): 非会員×表示プロフィール → 取得できる")
        void 非会員_表示プロフィール_取得可() {
            TeamPageEntity page = TeamPageEntity.builder().organizationId(500L).build();
            MemberProfileEntity entity = MemberProfileEntity.builder()
                    .teamPageId(10L).displayName("表示太郎").isVisible(true).build();
            given(profileRepository.findById(1L)).willReturn(Optional.of(entity));
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(pageService.isPageAdmin(999L, page)).willReturn(false);
            given(memberMapper.toMemberProfileResponse(entity)).willReturn(
                    new MemberProfileResponse(1L, 10L, null, "表示太郎",
                            null, null, null, null, null, 0, true, null, null));

            MemberProfileResponse result = service.getProfile(999L, 1L);

            assertThat(result.getDisplayName()).isEqualTo("表示太郎");
        }

        @Test
        @DisplayName("検分修正(3巡目・P1): 管理者は非表示プロフィールも取得できる（編集用途）")
        void 管理者_非表示プロフィール_取得可() {
            TeamPageEntity page = TeamPageEntity.builder().organizationId(500L).build();
            MemberProfileEntity entity = MemberProfileEntity.builder()
                    .teamPageId(10L).displayName("非表示太郎").isVisible(false).build();
            given(profileRepository.findById(1L)).willReturn(Optional.of(entity));
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(pageService.isPageAdmin(999L, page)).willReturn(true);
            given(memberMapper.toMemberProfileResponse(entity)).willReturn(
                    new MemberProfileResponse(1L, 10L, null, "非表示太郎",
                            null, null, null, null, null, 0, false, null, null));

            MemberProfileResponse result = service.getProfile(999L, 1L);

            assertThat(result.getDisplayName()).isEqualTo("非表示太郎");
        }
    }

    @Nested
    @DisplayName("listProfiles")
    class ListProfiles {

        @Test
        @DisplayName("検分修正(3巡目・P1): 非管理者は is_visible=true のみをページング取得する（非表示行は除外）")
        void 非管理者_表示のみページング取得() {
            Pageable pageable = PageRequest.of(0, 10);
            TeamPageEntity page = TeamPageEntity.builder().organizationId(500L).build();
            Page<MemberProfileEntity> visibleOnly = new PageImpl<>(List.of());
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(pageService.isPageAdmin(999L, page)).willReturn(false);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(10L, pageable))
                    .willReturn(visibleOnly);

            service.listProfiles(999L, 10L, pageable);

            verify(profileRepository).findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(10L, pageable);
            verify(profileRepository, never()).findByTeamPageIdOrderBySortOrder(10L, pageable);
        }

        @Test
        @DisplayName("検分修正(3巡目・P1): 管理者は非表示行も含めて全件ページング取得する")
        void 管理者_全件ページング取得() {
            Pageable pageable = PageRequest.of(0, 10);
            TeamPageEntity page = TeamPageEntity.builder().organizationId(500L).build();
            Page<MemberProfileEntity> all = new PageImpl<>(List.of());
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(pageService.isPageAdmin(999L, page)).willReturn(true);
            given(profileRepository.findByTeamPageIdOrderBySortOrder(10L, pageable)).willReturn(all);

            service.listProfiles(999L, 10L, pageable);

            verify(profileRepository).findByTeamPageIdOrderBySortOrder(10L, pageable);
            verify(profileRepository, never())
                    .findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(10L, pageable);
        }
    }

    @Nested
    @DisplayName("bulkCreate")
    class BulkCreate {

        @Test
        @DisplayName("異常系: 100件超過でMEMBER_014例外")
        void 一括_上限超過_例外() {
            // Given
            List<BulkCreateMemberRequest.BulkMemberItem> items = new ArrayList<>();
            for (int i = 0; i < 101; i++) {
                items.add(new BulkCreateMemberRequest.BulkMemberItem(
                        (long) i, "メンバー" + i, null, null, null, null, null));
            }
            BulkCreateMemberRequest req = new BulkCreateMemberRequest(1L, items);

            // When / Then
            assertThatThrownBy(() -> service.bulkCreate(999L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_014"));
        }
    }

    @Nested
    @DisplayName("copyMembers")
    class CopyMembers {

        @Test
        @DisplayName("異常系: 同一ページをコピー元にするとMEMBER_011例外")
        void コピー_同一ページ_例外() {
            // Given
            CopyMembersRequest req = new CopyMembersRequest(1L);

            // When / Then
            assertThatThrownBy(() -> service.copyMembers(999L, 1L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_011"));
        }
    }

    @Nested
    @DisplayName("reorderMembers")
    class ReorderMembers {

        @Test
        @DisplayName("異常系: 100件超過でMEMBER_013例外")
        void 並替_上限超過_例外() {
            // Given
            List<ReorderRequest.OrderItem> orders = new ArrayList<>();
            for (int i = 0; i < 101; i++) {
                orders.add(new ReorderRequest.OrderItem((long) i, i));
            }
            ReorderRequest req = new ReorderRequest(1L, orders);

            // When / Then
            assertThatThrownBy(() -> service.reorderMembers(999L, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_013"));
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    // PR #3387 試練（判定分離）: 軍議書 gungi-3387.md の受け入れ条件に対応する。
    // pageService は LENIENT モックのため判定の中身では落ちない。どの判定を呼ぶか（配線）を
    // verify で固定し、判定の中身は TeamPageServiceIsPageAdminTest と結合テストで担保する。
    // 未実装の判定メソッドは PageAuthzProbe（リフレクション）経由で扱う。
    // ═════════════════════════════════════════════════════════════════════

    private static final Long ACTOR = 999L;

    private static TeamPageEntity orgPage(Long orgId) {
        return TeamPageEntity.builder().organizationId(orgId).title("紹介").slug("intro").build();
    }

    private static TeamPageEntity teamPage(Long teamId) {
        return TeamPageEntity.builder().teamId(teamId).title("紹介").slug("intro").build();
    }

    private static void assertCode(Throwable ex, String code) {
        assertThat(ex).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) ex).getErrorCode().getCode()).isEqualTo(code);
    }

    @Nested
    @DisplayName("PR #3387 試練: getProfile（V4）")
    class GetProfileSplit {

        @Test
        @DisplayName("AC-14b(V4): getProfile は checkPageViewableOrNotFound を1回呼ぶ")
        void AC14b_V4は閲覧判定を呼ぶ() {
            TeamPageEntity page = orgPage(500L);
            MemberProfileEntity entity = MemberProfileEntity.builder()
                    .teamPageId(10L).displayName("表示太郎").isVisible(true).build();
            given(profileRepository.findById(1L)).willReturn(Optional.of(entity));
            given(pageService.findPageOrThrow(10L)).willReturn(page);

            service.getProfile(ACTOR, 1L);

            PageAuthzProbe.verifyCalled(pageService, times(1), PageAuthzProbe.VIEWABLE, ACTOR, page);
        }

        @Test
        @DisplayName("AC-14b(V4): getProfile は checkPageMembershipOrNotFound を呼ばない")
        void AC14b_V4は会員確認を呼ばない() {
            TeamPageEntity page = orgPage(500L);
            MemberProfileEntity entity = MemberProfileEntity.builder()
                    .teamPageId(10L).displayName("表示太郎").isVisible(true).build();
            given(profileRepository.findById(1L)).willReturn(Optional.of(entity));
            given(pageService.findPageOrThrow(10L)).willReturn(page);

            service.getProfile(ACTOR, 1L);

            verify(pageService, never()).checkPageMembershipOrNotFound(ACTOR, page);
        }

        @Test
        @DisplayName("AC-09(V4): ページ判定（閲覧）が 404(MEMBER_001) を投げたらそのまま伝わる")
        void AC09_V4_ページ判定の404が伝わる() {
            TeamPageEntity page = orgPage(500L);
            MemberProfileEntity entity = MemberProfileEntity.builder()
                    .teamPageId(10L).displayName("表示太郎").isVisible(true).build();
            given(profileRepository.findById(1L)).willReturn(Optional.of(entity));
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            PageAuthzProbe.stubThrow(pageService, new BusinessException(com.mannschaft.app.member.MemberErrorCode.PAGE_NOT_FOUND),
                    PageAuthzProbe.VIEWABLE, ACTOR, page);

            assertThatThrownBy(() -> service.getProfile(ACTOR, 1L))
                    .satisfies(ex -> assertCode(ex, "MEMBER_001"));
            verify(memberMapper, never()).toMemberProfileResponse(any());
        }

        @Test
        @DisplayName("AC-20(裁可3): TEAM の会員（非管理者）は非表示プロフィールも V4 で 200")
        void AC20_TEAM_会員_非表示プロフィールも取得できる() {
            TeamPageEntity page = teamPage(7L);
            MemberProfileEntity entity = MemberProfileEntity.builder()
                    .teamPageId(10L).displayName("非表示太郎").isVisible(false).build();
            given(profileRepository.findById(1L)).willReturn(Optional.of(entity));
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(pageService.isPageAdmin(ACTOR, page)).willReturn(false);
            given(memberMapper.toMemberProfileResponse(entity)).willReturn(
                    new MemberProfileResponse(1L, 10L, null, "非表示太郎",
                            null, null, null, null, null, 0, false, null, null));

            MemberProfileResponse result = service.getProfile(ACTOR, 1L);

            assertThat(result.getDisplayName()).isEqualTo("非表示太郎");
        }

        @Test
        @DisplayName("AC-03/08: ORG の非管理者は非表示プロフィールが V4 で 404(MEMBER_003)（ORG だけに効く）")
        void AC03_ORG_非管理者_非表示は404() {
            TeamPageEntity page = orgPage(500L);
            MemberProfileEntity entity = MemberProfileEntity.builder()
                    .teamPageId(10L).displayName("非表示太郎").isVisible(false).build();
            given(profileRepository.findById(1L)).willReturn(Optional.of(entity));
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(pageService.isPageAdmin(ACTOR, page)).willReturn(false);

            assertThatThrownBy(() -> service.getProfile(ACTOR, 1L))
                    .satisfies(ex -> assertCode(ex, "MEMBER_003"));
        }
    }

    @Nested
    @DisplayName("PR #3387 試練: listProfiles（V3）")
    class ListProfilesSplit {

        @Test
        @DisplayName("AC-14b(V3): listProfiles は checkPageViewableOrNotFound を1回呼ぶ")
        void AC14b_V3は閲覧判定を呼ぶ() {
            Pageable pageable = PageRequest.of(0, 10);
            TeamPageEntity page = orgPage(500L);
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(10L, pageable))
                    .willReturn(new PageImpl<>(List.of()));

            service.listProfiles(ACTOR, 10L, pageable);

            PageAuthzProbe.verifyCalled(pageService, times(1), PageAuthzProbe.VIEWABLE, ACTOR, page);
        }

        @Test
        @DisplayName("AC-14b(V3): listProfiles は checkPageMembershipOrNotFound を呼ばない")
        void AC14b_V3は会員確認を呼ばない() {
            Pageable pageable = PageRequest.of(0, 10);
            TeamPageEntity page = orgPage(500L);
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(10L, pageable))
                    .willReturn(new PageImpl<>(List.of()));

            service.listProfiles(ACTOR, 10L, pageable);

            verify(pageService, never()).checkPageMembershipOrNotFound(ACTOR, page);
        }

        @Test
        @DisplayName("AC-20(裁可3): TEAM の会員（非管理者）は V3 で非表示行も含めて全件取得する")
        void AC20_TEAM_会員_非表示行も返る() {
            Pageable pageable = PageRequest.of(0, 10);
            TeamPageEntity page = teamPage(7L);
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(pageService.isPageAdmin(ACTOR, page)).willReturn(false);
            given(profileRepository.findByTeamPageIdOrderBySortOrder(10L, pageable))
                    .willReturn(new PageImpl<>(List.of()));

            service.listProfiles(ACTOR, 10L, pageable);

            verify(profileRepository).findByTeamPageIdOrderBySortOrder(10L, pageable);
            verify(profileRepository, never()).findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(10L, pageable);
        }

        @Test
        @DisplayName("AC-23(V3): ORG の非管理者で表示中0件なら空ページで返る")
        void AC23_V3_表示中0件_空ページ() {
            Pageable pageable = PageRequest.of(0, 10);
            TeamPageEntity page = orgPage(500L);
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(pageService.isPageAdmin(ACTOR, page)).willReturn(false);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(10L, pageable))
                    .willReturn(new PageImpl<>(List.of(), pageable, 0));

            Page<MemberProfileResponse> result = service.listProfiles(ACTOR, 10L, pageable);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }

        @Test
        @DisplayName("AC-26(V3): 認可まわり（pageService）の呼び出し回数は行数に依存しない（0件と30件で同数）")
        void AC26_V3_認可呼び出し回数は行数に依存しない() {
            int zero = countPageServiceCallsForListing(0);
            int thirty = countPageServiceCallsForListing(30);

            assertThat(thirty).isEqualTo(zero);
        }

        private int countPageServiceCallsForListing(int rows) {
            org.mockito.Mockito.clearInvocations(pageService);
            Pageable pageable = PageRequest.of(0, 50);
            TeamPageEntity page = orgPage(500L);
            List<MemberProfileEntity> entities = new ArrayList<>();
            for (int i = 0; i < rows; i++) {
                entities.add(MemberProfileEntity.builder().teamPageId(10L).displayName("会員" + i).build());
            }
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(pageService.isPageAdmin(ACTOR, page)).willReturn(false);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(10L, pageable))
                    .willReturn(new PageImpl<>(entities, pageable, rows));

            service.listProfiles(ACTOR, 10L, pageable);

            return mockingDetails(pageService).getInvocations().size();
        }
    }

    @Nested
    @DisplayName("PR #3387 試練: lookupMembers（O1）")
    class LookupSplit {

        @Test
        @DisplayName("AC-12/13(O1・裁可2): lookup は checkPageMemberRoleOrNotFound（MEMBER 以上）を1回呼ぶ")
        void AC13_O1はMEMBER以上判定を呼ぶ() {
            TeamPageEntity page = orgPage(500L);
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(profileRepository.lookupMembers(eq(10L), anyString(), anyString(), anyString(), any()))
                    .willReturn(List.of());

            service.lookupMembers(ACTOR, 10L, "選手", 10);

            PageAuthzProbe.verifyCalled(pageService, times(1), PageAuthzProbe.MEMBER_ROLE, ACTOR, page);
        }

        @Test
        @DisplayName("AC-14b(O1): lookup は閲覧用の判定（checkPageViewableOrNotFound）を呼ばない")
        void AC14b_O1は閲覧判定を呼ばない() {
            TeamPageEntity page = orgPage(500L);
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(profileRepository.lookupMembers(eq(10L), anyString(), anyString(), anyString(), any()))
                    .willReturn(List.of());

            service.lookupMembers(ACTOR, 10L, "選手", 10);

            PageAuthzProbe.verifyCalled(pageService, never(), PageAuthzProbe.VIEWABLE, ACTOR, page);
        }

        @Test
        @DisplayName("AC-12(O1): lookup はサブタブ緩和が効く HEAD の会員確認（checkPageMembershipOrNotFound）を呼ばない")
        void AC12_O1は会員確認を呼ばない() {
            TeamPageEntity page = orgPage(500L);
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(profileRepository.lookupMembers(eq(10L), anyString(), anyString(), anyString(), any()))
                    .willReturn(List.of());

            service.lookupMembers(ACTOR, 10L, "選手", 10);

            verify(pageService, never()).checkPageMembershipOrNotFound(ACTOR, page);
        }

        @Test
        @DisplayName("AC-12(O1): MEMBER 未満の判定が 404(MEMBER_001) を投げたら検索を実行しない")
        void AC12_O1_判定失敗なら検索しない() {
            TeamPageEntity page = orgPage(500L);
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            PageAuthzProbe.stubThrow(pageService, new BusinessException(com.mannschaft.app.member.MemberErrorCode.PAGE_NOT_FOUND),
                    PageAuthzProbe.MEMBER_ROLE, ACTOR, page);

            assertThatThrownBy(() -> service.lookupMembers(ACTOR, 10L, "選手", 10))
                    .satisfies(ex -> assertCode(ex, "MEMBER_001"));
            verify(profileRepository, never()).lookupMembers(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("AC-28(O1): 一致0件なら 200 で空配列")
        void AC28_O1_一致0件_空配列() {
            TeamPageEntity page = orgPage(500L);
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(profileRepository.lookupMembers(eq(10L), anyString(), anyString(), anyString(), any()))
                    .willReturn(List.of());
            given(memberMapper.toMemberLookupResponseList(List.of())).willReturn(List.of());

            List<MemberLookupResponse> result = service.lookupMembers(ACTOR, 10L, "該当なし", 10);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("AC-30(O1): limit=20 は20件まで")
        void AC30_O1_limit20は20件() {
            assertThat(capturedLookupPageSize(20)).isEqualTo(20);
        }

        @Test
        @DisplayName("AC-30(O1): limit=21 は20件に丸められる（Math.min の固定）")
        void AC30_O1_limit21は20件に丸める() {
            assertThat(capturedLookupPageSize(21)).isEqualTo(20);
        }

        private int capturedLookupPageSize(int limit) {
            TeamPageEntity page = orgPage(500L);
            given(pageService.findPageOrThrow(10L)).willReturn(page);
            given(profileRepository.lookupMembers(eq(10L), anyString(), anyString(), anyString(), any()))
                    .willReturn(List.of());
            service.lookupMembers(ACTOR, 10L, "選手", limit);
            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(profileRepository).lookupMembers(eq(10L), anyString(), anyString(), anyString(), captor.capture());
            return captor.getValue().getPageSize();
        }
    }

    @Nested
    @DisplayName("PR #3387 試練: copyMembers（O2）")
    class CopySplit {

        private void stubPages(TeamPageEntity target, TeamPageEntity source) {
            given(pageService.findPageOrThrow(1L)).willReturn(target);
            given(pageService.findPageOrThrow(2L)).willReturn(source);
        }

        @Test
        @DisplayName("AC-14b(O2): 同一スコープのコピー元には checkPageMemberRoleOrNotFound を1回呼ぶ")
        void AC14b_O2はコピー元にMEMBER以上判定を呼ぶ() {
            TeamPageEntity target = orgPage(500L);
            TeamPageEntity source = orgPage(500L);
            stubPages(target, source);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(2L)).willReturn(List.of());

            service.copyMembers(ACTOR, 1L, new CopyMembersRequest(2L));

            PageAuthzProbe.verifyCalled(pageService, times(1), PageAuthzProbe.MEMBER_ROLE, ACTOR, source);
            verify(pageService).checkPageAdminOrNotFound(ACTOR, target);
        }

        @Test
        @DisplayName("AC-14b(O2): コピー元に閲覧用の判定（checkPageViewableOrNotFound）を呼ばない")
        void AC14b_O2は閲覧判定を呼ばない() {
            TeamPageEntity target = orgPage(500L);
            TeamPageEntity source = orgPage(500L);
            stubPages(target, source);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(2L)).willReturn(List.of());

            service.copyMembers(ACTOR, 1L, new CopyMembersRequest(2L));

            PageAuthzProbe.verifyCalled(pageService, never(), PageAuthzProbe.VIEWABLE, ACTOR, source);
            verify(pageService, never()).checkPageMembershipOrNotFound(ACTOR, source);
        }

        @Test
        @DisplayName("AC-14/16(O2・裁可1): コピー元が別組織なら（判定がすべて通っても）404(MEMBER_001)・読み出しも save も0回")
        void AC16_O2_別組織のコピー元は404() {
            TeamPageEntity target = orgPage(500L);
            TeamPageEntity source = orgPage(600L);
            stubPages(target, source);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(2L)).willReturn(List.of(
                    MemberProfileEntity.builder().teamPageId(2L).displayName("流出太郎").build()));

            assertThatThrownBy(() -> service.copyMembers(ACTOR, 1L, new CopyMembersRequest(2L)))
                    .satisfies(ex -> assertCode(ex, "MEMBER_001"));
            verify(profileRepository, never()).findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(anyLong());
            verify(profileRepository, never()).save(any(MemberProfileEntity.class));
        }

        @Test
        @DisplayName("AC-14/16(O2・裁可1): scopeId が同じでも scopeType が違えば（TEAM→ORG）404(MEMBER_001)・save 0回")
        void AC16_O2_スコープ種別違いは404() {
            TeamPageEntity target = teamPage(500L);
            TeamPageEntity source = orgPage(500L);
            stubPages(target, source);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(2L)).willReturn(List.of(
                    MemberProfileEntity.builder().teamPageId(2L).displayName("流出太郎").build()));

            assertThatThrownBy(() -> service.copyMembers(ACTOR, 1L, new CopyMembersRequest(2L)))
                    .satisfies(ex -> assertCode(ex, "MEMBER_001"));
            verify(profileRepository, never()).save(any(MemberProfileEntity.class));
        }

        @Test
        @DisplayName("AC-25(O2): コピー先の判定は通りコピー元の判定で失敗したら、save 0回で例外が伝わる")
        void AC25_O2_コピー元判定失敗は書き込み前に止まる() {
            TeamPageEntity target = orgPage(500L);
            TeamPageEntity source = orgPage(500L);
            stubPages(target, source);
            PageAuthzProbe.stubThrow(pageService, new BusinessException(com.mannschaft.app.member.MemberErrorCode.PAGE_NOT_FOUND),
                    PageAuthzProbe.MEMBER_ROLE, ACTOR, source);

            assertThatThrownBy(() -> service.copyMembers(ACTOR, 1L, new CopyMembersRequest(2L)))
                    .satisfies(ex -> assertCode(ex, "MEMBER_001"));
            verify(pageService).checkPageAdminOrNotFound(ACTOR, target);
            verify(profileRepository, never()).save(any(MemberProfileEntity.class));
        }

        @Test
        @DisplayName("AC-16(O2): 同一組織内のコピーは表示中の行だけを複製する")
        void AC16_O2_同一組織は表示中のみ複製() {
            TeamPageEntity target = orgPage(500L);
            TeamPageEntity source = orgPage(500L);
            stubPages(target, source);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(2L)).willReturn(List.of(
                    MemberProfileEntity.builder().teamPageId(2L).displayName("表示A").build(),
                    MemberProfileEntity.builder().teamPageId(2L).displayName("表示B").build()));

            CopyMembersResponse result = service.copyMembers(ACTOR, 1L, new CopyMembersRequest(2L));

            assertThat(result.getCopiedCount()).isEqualTo(2);
            verify(profileRepository, times(2)).save(any(MemberProfileEntity.class));
            verify(profileRepository, never()).findByTeamPageIdOrderBySortOrder(anyLong(), any());
        }

        @Test
        @DisplayName("AC-23(O2): コピー元の表示中0件なら copied_count=0 で返り、save 0回")
        void AC23_O2_表示中0件ならcopied0() {
            TeamPageEntity target = orgPage(500L);
            TeamPageEntity source = orgPage(500L);
            stubPages(target, source);
            given(profileRepository.findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(2L)).willReturn(List.of());

            CopyMembersResponse result = service.copyMembers(ACTOR, 1L, new CopyMembersRequest(2L));

            assertThat(result.getCopiedCount()).isZero();
            assertThat(result.getSkippedCount()).isZero();
            verify(profileRepository, never()).save(any(MemberProfileEntity.class));
        }

        @Test
        @DisplayName("AC-29(O2): source=target なら 400(MEMBER_011)、コピー元の読み出し・save とも0回")
        void AC29_O2_同一ページは400で読み出しもsaveもしない() {
            TeamPageEntity target = orgPage(500L);
            given(pageService.findPageOrThrow(1L)).willReturn(target);

            assertThatThrownBy(() -> service.copyMembers(ACTOR, 1L, new CopyMembersRequest(1L)))
                    .satisfies(ex -> assertCode(ex, "MEMBER_011"));
            verify(profileRepository, never()).findByTeamPageIdAndIsVisibleTrueOrderBySortOrder(anyLong());
            verify(profileRepository, never()).save(any(MemberProfileEntity.class));
        }
    }
}
