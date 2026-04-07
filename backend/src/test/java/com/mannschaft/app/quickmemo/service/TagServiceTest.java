package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.quickmemo.QuickMemoErrorCode;
import com.mannschaft.app.quickmemo.dto.CreateTagRequest;
import com.mannschaft.app.quickmemo.dto.TagResponse;
import com.mannschaft.app.quickmemo.dto.UpdateTagRequest;
import com.mannschaft.app.quickmemo.entity.TagEntity;
import com.mannschaft.app.quickmemo.repository.TagRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link TagService} の単体テスト。
 * F02.5 ポイっとメモ機能のタグサービス層を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TagService 単体テスト")
class TagServiceTest {

    @Mock
    private TagRepository tagRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private TagService tagService;

    // ========================================
    // テスト用定数
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long TAG_ID = 100L;
    private static final String SCOPE_TYPE = "PERSONAL";
    private static final Long SCOPE_ID = 1L;

    // ========================================
    // セットアップ / ティアダウン
    // ========================================

    /**
     * テスト前に SecurityContextHolder にユーザーIDをセットする。
     * SecurityUtils.getCurrentUserId() は SecurityContextHolder を参照するため。
     */
    @BeforeEach
    void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ========================================
    // ヘルパーメソッド
    // ========================================

    /**
     * 基本的な TagEntity を生成する。
     */
    private TagEntity buildTag(Long id, String name, String color, int usageCount) {
        return TagEntity.builder()
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .name(name)
                .color(color)
                .usageCount(usageCount)
                .createdBy(USER_ID)
                .build();
    }

    // ========================================
    // listTags
    // ========================================

    @Nested
    @DisplayName("listTags")
    class ListTags {

        @Test
        @DisplayName("listTags_PERSONALスコープ_使用頻度降順一覧が返る")
        void listTags_PERSONALスコープ_使用頻度降順一覧が返る() {
            // Given
            TagEntity tag1 = buildTag(1L, "よく使うタグ", "#FF0000", 10);
            TagEntity tag2 = buildTag(2L, "あまり使わないタグ", "#00FF00", 2);
            Page<TagEntity> page = new PageImpl<>(List.of(tag1, tag2),
                    PageRequest.of(0, 20), 2L);
            given(tagRepository.findByScopeTypeAndScopeIdOrderByUsageCountDesc(
                    eq(SCOPE_TYPE), eq(SCOPE_ID), any(PageRequest.class)))
                    .willReturn(page);

            // When
            PagedResponse<TagResponse> result = tagService.listTags(SCOPE_TYPE, SCOPE_ID, 1, 20);

            // Then
            assertThat(result.getData()).hasSize(2);
            assertThat(result.getData().get(0).name()).isEqualTo("よく使うタグ");
            assertThat(result.getData().get(1).name()).isEqualTo("あまり使わないタグ");
            assertThat(result.getMeta().getTotal()).isEqualTo(2L);
            assertThat(result.getMeta().getPage()).isEqualTo(1);
        }
    }

    // ========================================
    // createTag
    // ========================================

    @Nested
    @DisplayName("createTag")
    class CreateTag {

        @Test
        @DisplayName("createTag_正常_タグが作成される")
        void createTag_正常_タグが作成される() {
            // Given
            given(tagRepository.countByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(5L);
            given(tagRepository.existsByScopeTypeAndScopeIdAndName(SCOPE_TYPE, SCOPE_ID, "新しいタグ"))
                    .willReturn(false);
            TagEntity saved = buildTag(TAG_ID, "新しいタグ", "#FF0000", 0);
            given(tagRepository.save(any(TagEntity.class))).willReturn(saved);

            CreateTagRequest req = new CreateTagRequest("新しいタグ", "#FF0000");

            // When
            TagResponse result = tagService.createTag(SCOPE_TYPE, SCOPE_ID, req);

            // Then
            assertThat(result.name()).isEqualTo("新しいタグ");
            assertThat(result.color()).isEqualTo("#FF0000");
            assertThat(result.scopeType()).isEqualTo(SCOPE_TYPE);
            verify(tagRepository).save(any(TagEntity.class));
        }

        @Test
        @DisplayName("createTag_スコープ内50件上限超過_BusinessException(QM_012)が投げられる")
        void createTag_スコープ内50件上限超過_BusinessException_QM_012_が投げられる() {
            // Given
            given(tagRepository.countByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(50L);

            CreateTagRequest req = new CreateTagRequest("新しいタグ", "#FF0000");

            // When / Then
            assertThatThrownBy(() -> tagService.createTag(SCOPE_TYPE, SCOPE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_012");
                    });
            verify(tagRepository, never()).save(any());
        }

        @Test
        @DisplayName("createTag_同名タグが既存_BusinessException(QM_011)が投げられる")
        void createTag_同名タグが既存_BusinessException_QM_011_が投げられる() {
            // Given
            given(tagRepository.countByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(10L);
            given(tagRepository.existsByScopeTypeAndScopeIdAndName(SCOPE_TYPE, SCOPE_ID, "重複タグ"))
                    .willReturn(true);

            CreateTagRequest req = new CreateTagRequest("重複タグ", "#FF0000");

            // When / Then
            assertThatThrownBy(() -> tagService.createTag(SCOPE_TYPE, SCOPE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_011");
                    });
            verify(tagRepository, never()).save(any());
        }

        @Test
        @DisplayName("createTag_タグ名が前後スペースあり_trim後に保存される")
        void createTag_タグ名が前後スペースあり_trim後に保存される() {
            // Given
            given(tagRepository.countByScopeTypeAndScopeId(SCOPE_TYPE, SCOPE_ID)).willReturn(5L);
            given(tagRepository.existsByScopeTypeAndScopeIdAndName(SCOPE_TYPE, SCOPE_ID, "スペースタグ"))
                    .willReturn(false);
            TagEntity saved = buildTag(TAG_ID, "スペースタグ", "#FF0000", 0);
            given(tagRepository.save(any(TagEntity.class))).willReturn(saved);

            // 前後にスペースを含む名前
            CreateTagRequest req = new CreateTagRequest("  スペースタグ  ", "#FF0000");

            // When
            TagResponse result = tagService.createTag(SCOPE_TYPE, SCOPE_ID, req);

            // Then
            // trim後の名前で重複チェックが呼ばれることを確認
            verify(tagRepository).existsByScopeTypeAndScopeIdAndName(SCOPE_TYPE, SCOPE_ID, "スペースタグ");
            assertThat(result.name()).isEqualTo("スペースタグ");
        }
    }

    // ========================================
    // updateTag
    // ========================================

    @Nested
    @DisplayName("updateTag")
    class UpdateTag {

        @Test
        @DisplayName("updateTag_名前と色を変更_更新後タグが返る")
        void updateTag_名前と色を変更_更新後タグが返る() {
            // Given
            TagEntity existing = buildTag(TAG_ID, "旧タグ名", "#FF0000", 0);
            given(tagRepository.findByIdAndScopeTypeAndScopeId(TAG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(existing));
            given(tagRepository.existsByScopeTypeAndScopeIdAndNameAndIdNot(
                    SCOPE_TYPE, SCOPE_ID, "新タグ名", TAG_ID)).willReturn(false);
            given(tagRepository.save(any(TagEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

            UpdateTagRequest req = new UpdateTagRequest("新タグ名", "#0000FF");

            // When
            TagResponse result = tagService.updateTag(SCOPE_TYPE, SCOPE_ID, TAG_ID, req);

            // Then
            assertThat(result.name()).isEqualTo("新タグ名");
            assertThat(result.color()).isEqualTo("#0000FF");
            verify(tagRepository).save(existing);
        }

        @Test
        @DisplayName("updateTag_存在しないtagId_BusinessException(QM_010)が投げられる")
        void updateTag_存在しないtagId_BusinessException_QM_010_が投げられる() {
            // Given
            given(tagRepository.findByIdAndScopeTypeAndScopeId(TAG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            UpdateTagRequest req = new UpdateTagRequest("新タグ名", "#0000FF");

            // When / Then
            assertThatThrownBy(() -> tagService.updateTag(SCOPE_TYPE, SCOPE_ID, TAG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_010");
                    });
            verify(tagRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateTag_変更後の名前が既存と重複_BusinessException(QM_011)が投げられる")
        void updateTag_変更後の名前が既存と重複_BusinessException_QM_011_が投げられる() {
            // Given
            TagEntity existing = buildTag(TAG_ID, "旧タグ名", "#FF0000", 0);
            given(tagRepository.findByIdAndScopeTypeAndScopeId(TAG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(existing));
            given(tagRepository.existsByScopeTypeAndScopeIdAndNameAndIdNot(
                    SCOPE_TYPE, SCOPE_ID, "重複タグ名", TAG_ID)).willReturn(true);

            UpdateTagRequest req = new UpdateTagRequest("重複タグ名", null);

            // When / Then
            assertThatThrownBy(() -> tagService.updateTag(SCOPE_TYPE, SCOPE_ID, TAG_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_011");
                    });
            verify(tagRepository, never()).save(any());
        }

        @Test
        @DisplayName("updateTag_名前のみ変更（色はnull）_名前だけ更新される")
        void updateTag_名前のみ変更_色はnull_名前だけ更新される() {
            // Given
            TagEntity existing = buildTag(TAG_ID, "旧タグ名", "#FF0000", 0);
            given(tagRepository.findByIdAndScopeTypeAndScopeId(TAG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(existing));
            given(tagRepository.existsByScopeTypeAndScopeIdAndNameAndIdNot(
                    SCOPE_TYPE, SCOPE_ID, "新タグ名", TAG_ID)).willReturn(false);
            given(tagRepository.save(any(TagEntity.class))).willAnswer(invocation -> invocation.getArgument(0));

            UpdateTagRequest req = new UpdateTagRequest("新タグ名", null);

            // When
            TagResponse result = tagService.updateTag(SCOPE_TYPE, SCOPE_ID, TAG_ID, req);

            // Then
            assertThat(result.name()).isEqualTo("新タグ名");
            // 色は変更前のままであることを確認
            assertThat(result.color()).isEqualTo("#FF0000");
        }
    }

    // ========================================
    // deleteTag
    // ========================================

    @Nested
    @DisplayName("deleteTag")
    class DeleteTag {

        @Test
        @DisplayName("deleteTag_使用中でないタグ_削除成功")
        void deleteTag_使用中でないタグ_削除成功() {
            // Given
            TagEntity tag = buildTag(TAG_ID, "削除タグ", "#FF0000", 0);
            given(tagRepository.findByIdAndScopeTypeAndScopeId(TAG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(tag));

            // When
            tagService.deleteTag(SCOPE_TYPE, SCOPE_ID, TAG_ID);

            // Then
            verify(tagRepository).delete(tag);
        }

        @Test
        @DisplayName("deleteTag_存在しないtagId_BusinessException(QM_010)が投げられる")
        void deleteTag_存在しないtagId_BusinessException_QM_010_が投げられる() {
            // Given
            given(tagRepository.findByIdAndScopeTypeAndScopeId(TAG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> tagService.deleteTag(SCOPE_TYPE, SCOPE_ID, TAG_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_010");
                    });
            verify(tagRepository, never()).delete(any());
        }

        @Test
        @DisplayName("deleteTag_usageCount > 0_BusinessException(QM_013)が投げられる")
        void deleteTag_usageCount_gt_0_BusinessException_QM_013_が投げられる() {
            // Given
            // usageCount=3 のタグ（使用中）
            TagEntity inUseTag = buildTag(TAG_ID, "使用中タグ", "#FF0000", 3);
            given(tagRepository.findByIdAndScopeTypeAndScopeId(TAG_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(inUseTag));

            // When / Then
            assertThatThrownBy(() -> tagService.deleteTag(SCOPE_TYPE, SCOPE_ID, TAG_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_013");
                    });
            verify(tagRepository, never()).delete(any());
        }
    }
}
