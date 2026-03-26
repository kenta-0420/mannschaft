package com.mannschaft.app.member;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.member.dto.BulkCreateMemberRequest;
import com.mannschaft.app.member.dto.CopyMembersRequest;
import com.mannschaft.app.member.dto.CreateMemberProfileRequest;
import com.mannschaft.app.member.dto.MemberProfileResponse;
import com.mannschaft.app.member.dto.ReorderRequest;
import com.mannschaft.app.member.entity.MemberProfileEntity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
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
            MemberProfileResponse result = service.createProfile(req);

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
            assertThatThrownBy(() -> service.createProfile(req))
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
            assertThatThrownBy(() -> service.getProfile(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_003"));
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
            assertThatThrownBy(() -> service.bulkCreate(req))
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
            assertThatThrownBy(() -> service.copyMembers(1L, req))
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
            assertThatThrownBy(() -> service.reorderMembers(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("MEMBER_013"));
        }
    }
}
