package com.mannschaft.app.member;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.dto.BulkCreateMemberRequest;
import com.mannschaft.app.member.dto.CopyMembersRequest;
import com.mannschaft.app.member.dto.CreateMemberProfileRequest;
import com.mannschaft.app.member.dto.MemberProfileResponse;
import com.mannschaft.app.member.dto.ReorderRequest;
import com.mannschaft.app.member.entity.MemberProfileEntity;
import com.mannschaft.app.member.entity.TeamPageEntity;
import com.mannschaft.app.member.repository.MemberProfileRepository;
import com.mannschaft.app.member.service.MemberProfileService;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
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
}
