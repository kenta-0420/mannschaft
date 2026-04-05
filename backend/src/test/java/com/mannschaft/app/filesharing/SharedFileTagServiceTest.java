package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.CreateTagRequest;
import com.mannschaft.app.filesharing.dto.TagResponse;
import com.mannschaft.app.filesharing.entity.SharedFileTagEntity;
import com.mannschaft.app.filesharing.repository.SharedFileTagRepository;
import com.mannschaft.app.filesharing.service.SharedFileTagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SharedFileTagService} の単体テスト。
 * タグの一覧取得・追加・削除・ユーザータグ一覧を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileTagService 単体テスト")
class SharedFileTagServiceTest {

    @Mock
    private SharedFileTagRepository tagRepository;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @InjectMocks
    private SharedFileTagService sharedFileTagService;

    private static final Long FILE_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final Long TAG_ID = 1L;
    private static final String TAG_NAME = "重要";

    private SharedFileTagEntity createTagEntity() {
        return SharedFileTagEntity.builder()
                .fileId(FILE_ID)
                .tagName(TAG_NAME)
                .userId(USER_ID)
                .build();
    }

    private TagResponse createTagResponse() {
        return new TagResponse(TAG_ID, FILE_ID, TAG_NAME, USER_ID, LocalDateTime.now());
    }

    // ========================================
    // listTags
    // ========================================

    @Nested
    @DisplayName("listTags")
    class ListTags {

        @Test
        @DisplayName("正常系: タグ一覧が返る")
        void タグ一覧取得_正常_リスト返却() {
            // Given
            SharedFileTagEntity entity = createTagEntity();
            TagResponse response = createTagResponse();
            given(tagRepository.findByFileIdOrderByTagNameAsc(FILE_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toTagResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<TagResponse> result = sharedFileTagService.listTags(FILE_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTagName()).isEqualTo(TAG_NAME);
        }

        @Test
        @DisplayName("正常系: タグが存在しない場合は空リスト")
        void タグ一覧取得_タグなし_空リスト() {
            // Given
            given(tagRepository.findByFileIdOrderByTagNameAsc(FILE_ID))
                    .willReturn(List.of());
            given(fileSharingMapper.toTagResponseList(List.of()))
                    .willReturn(List.of());

            // When
            List<TagResponse> result = sharedFileTagService.listTags(FILE_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // addTag
    // ========================================

    @Nested
    @DisplayName("addTag")
    class AddTag {

        @Test
        @DisplayName("正常系: タグが追加される")
        void タグ追加_正常_レスポンス返却() {
            // Given
            CreateTagRequest request = new CreateTagRequest(TAG_NAME);
            SharedFileTagEntity savedEntity = createTagEntity();
            TagResponse response = createTagResponse();

            given(tagRepository.existsByFileIdAndTagNameAndUserId(FILE_ID, TAG_NAME, USER_ID))
                    .willReturn(false);
            given(tagRepository.save(any(SharedFileTagEntity.class))).willReturn(savedEntity);
            given(fileSharingMapper.toTagResponse(savedEntity)).willReturn(response);

            // When
            TagResponse result = sharedFileTagService.addTag(FILE_ID, USER_ID, request);

            // Then
            assertThat(result.getTagName()).isEqualTo(TAG_NAME);
            assertThat(result.getFileId()).isEqualTo(FILE_ID);
            verify(tagRepository).save(any(SharedFileTagEntity.class));
        }

        @Test
        @DisplayName("異常系: 同名タグ重複でFILE_SHARING_010例外")
        void タグ追加_タグ重複_FILE_SHARING_010例外() {
            // Given
            CreateTagRequest request = new CreateTagRequest(TAG_NAME);
            given(tagRepository.existsByFileIdAndTagNameAndUserId(FILE_ID, TAG_NAME, USER_ID))
                    .willReturn(true);

            // When / Then
            assertThatThrownBy(() -> sharedFileTagService.addTag(FILE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FILE_SHARING_010"));
        }
    }

    // ========================================
    // removeTag
    // ========================================

    @Nested
    @DisplayName("removeTag")
    class RemoveTag {

        @Test
        @DisplayName("正常系: タグが削除される")
        void タグ削除_正常_削除実行() {
            // Given
            SharedFileTagEntity entity = createTagEntity();
            given(tagRepository.findById(TAG_ID)).willReturn(Optional.of(entity));

            // When
            sharedFileTagService.removeTag(TAG_ID);

            // Then
            verify(tagRepository).delete(entity);
        }

        @Test
        @DisplayName("異常系: タグが存在しないでFILE_SHARING_008例外")
        void タグ削除_タグ不在_FILE_SHARING_008例外() {
            // Given
            given(tagRepository.findById(TAG_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> sharedFileTagService.removeTag(TAG_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FILE_SHARING_008"));
        }
    }

    // ========================================
    // listUserTags
    // ========================================

    @Nested
    @DisplayName("listUserTags")
    class ListUserTags {

        @Test
        @DisplayName("正常系: ユーザーのタグ一覧が返る")
        void ユーザータグ一覧取得_正常_リスト返却() {
            // Given
            SharedFileTagEntity entity = createTagEntity();
            TagResponse response = createTagResponse();
            given(tagRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toTagResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<TagResponse> result = sharedFileTagService.listUserTags(USER_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("正常系: ユーザーのタグが存在しない場合は空リスト")
        void ユーザータグ一覧取得_タグなし_空リスト() {
            // Given
            given(tagRepository.findByUserIdOrderByCreatedAtDesc(USER_ID))
                    .willReturn(List.of());
            given(fileSharingMapper.toTagResponseList(List.of()))
                    .willReturn(List.of());

            // When
            List<TagResponse> result = sharedFileTagService.listUserTags(USER_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
