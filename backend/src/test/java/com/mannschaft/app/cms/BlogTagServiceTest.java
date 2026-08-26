package com.mannschaft.app.cms;

import com.mannschaft.app.cms.dto.BlogTagResponse;
import com.mannschaft.app.cms.dto.CreateTagRequest;
import com.mannschaft.app.cms.dto.UpdateTagRequest;
import com.mannschaft.app.cms.entity.BlogTagEntity;
import com.mannschaft.app.cms.repository.BlogTagRepository;
import com.mannschaft.app.cms.service.BlogTagService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BlogTagService 単体テスト")
class BlogTagServiceTest {

    @Mock
    private BlogTagRepository tagRepository;
    @Mock
    private CmsMapper cmsMapper;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private BlogTagService service;

    private static final Long TEAM_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long TAG_ID = 10L;

    @Nested
    @DisplayName("createTag")
    class CreateTag {

        @Test
        @DisplayName("正常系: タグが作成される（checkMembershipのみ・ADMIN不要）")
        void 作成_正常_タグ保存() {
            // Given
            CreateTagRequest request = new CreateTagRequest(TEAM_ID, null, "新タグ", null);
            given(tagRepository.findByTeamIdAndName(TEAM_ID, "新タグ")).willReturn(Optional.empty());
            BlogTagEntity saved = BlogTagEntity.builder().teamId(TEAM_ID).name("新タグ").color("#6B7280").build();
            given(tagRepository.save(any(BlogTagEntity.class))).willReturn(saved);
            given(cmsMapper.toBlogTagResponse(saved)).willReturn(new BlogTagResponse(null, null, null, null, null, null, null));

            // When
            BlogTagResponse result = service.createTag(USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(tagRepository).save(any(BlogTagEntity.class));
            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
        }

        @Test
        @DisplayName("異常系: タグ名重複でCMS_006例外")
        void 作成_重複_例外() {
            // Given
            CreateTagRequest request = new CreateTagRequest(TEAM_ID, null, "既存タグ", null);
            given(tagRepository.findByTeamIdAndName(TEAM_ID, "既存タグ"))
                    .willReturn(Optional.of(BlogTagEntity.builder().build()));

            // When / Then
            assertThatThrownBy(() -> service.createTag(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_006"));
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 非メンバーの作成は403(COMMON_002)")
        void 作成_非メンバー_例外() {
            CreateTagRequest request = new CreateTagRequest(TEAM_ID, null, "新タグ", null);
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.createTag(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(tagRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    @Nested
    @DisplayName("updateTag")
    class UpdateTag {

        @Test
        @DisplayName("異常系: タグ不在でCMS_002例外")
        void 更新_タグ不在_例外() {
            // Given
            given(tagRepository.findById(TAG_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.updateTag(TAG_ID, USER_ID, new UpdateTagRequest(null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_002"));
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 非ADMINの更新は403(COMMON_002)")
        void 更新_非ADMIN_例外() {
            BlogTagEntity entity = BlogTagEntity.builder().teamId(TEAM_ID).name("タグ").color("#000").build();
            given(tagRepository.findById(TAG_ID)).willReturn(Optional.of(entity));
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.updateTag(TAG_ID, USER_ID, new UpdateTagRequest("改名", null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(tagRepository, org.mockito.Mockito.never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteTag")
    class DeleteTag {

        @Test
        @DisplayName("正常系: タグが物理削除される")
        void 削除_正常_物理削除() {
            // Given
            BlogTagEntity entity = BlogTagEntity.builder().teamId(TEAM_ID).name("タグ").color("#000").build();
            given(tagRepository.findById(TAG_ID)).willReturn(Optional.of(entity));

            // When
            service.deleteTag(TAG_ID, USER_ID);

            // Then
            verify(tagRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: タグ不在でCMS_002例外")
        void 削除_タグ不在_例外() {
            // Given
            given(tagRepository.findById(TAG_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.deleteTag(TAG_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("CMS_002"));
        }

        @Test
        @DisplayName("異常系(認可根治Wave3-B7): 非ADMINの削除は403(COMMON_002)")
        void 削除_非ADMIN_例外() {
            BlogTagEntity entity = BlogTagEntity.builder().teamId(TEAM_ID).name("タグ").color("#000").build();
            given(tagRepository.findById(TAG_ID)).willReturn(Optional.of(entity));
            org.mockito.BDDMockito.willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.deleteTag(TAG_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verify(tagRepository, org.mockito.Mockito.never()).delete(any());
        }
    }
}
