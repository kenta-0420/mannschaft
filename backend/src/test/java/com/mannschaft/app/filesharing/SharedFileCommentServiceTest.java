package com.mannschaft.app.filesharing;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.filesharing.dto.CommentResponse;
import com.mannschaft.app.filesharing.dto.CreateCommentRequest;
import com.mannschaft.app.filesharing.dto.UpdateCommentRequest;
import com.mannschaft.app.filesharing.entity.SharedFileCommentEntity;
import com.mannschaft.app.filesharing.repository.SharedFileCommentRepository;
import com.mannschaft.app.filesharing.service.SharedFileCommentService;
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
 * {@link SharedFileCommentService} の単体テスト。
 * コメントの一覧取得・作成・更新・論理削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SharedFileCommentService 単体テスト")
class SharedFileCommentServiceTest {

    @Mock
    private SharedFileCommentRepository commentRepository;

    @Mock
    private FileSharingMapper fileSharingMapper;

    @InjectMocks
    private SharedFileCommentService sharedFileCommentService;

    private static final Long FILE_ID = 100L;
    private static final Long USER_ID = 10L;
    private static final Long COMMENT_ID = 1L;
    private static final String COMMENT_BODY = "テストコメント";
    private static final String UPDATED_BODY = "更新されたコメント";

    private SharedFileCommentEntity createCommentEntity() {
        return SharedFileCommentEntity.builder()
                .fileId(FILE_ID)
                .userId(USER_ID)
                .body(COMMENT_BODY)
                .build();
    }

    private CommentResponse createCommentResponse() {
        return new CommentResponse(COMMENT_ID, FILE_ID, USER_ID, COMMENT_BODY,
                LocalDateTime.now(), LocalDateTime.now());
    }

    // ========================================
    // listComments
    // ========================================

    @Nested
    @DisplayName("listComments")
    class ListComments {

        @Test
        @DisplayName("正常系: コメント一覧が返る")
        void コメント一覧取得_正常_リスト返却() {
            // Given
            SharedFileCommentEntity entity = createCommentEntity();
            CommentResponse response = createCommentResponse();
            given(commentRepository.findByFileIdOrderByCreatedAtAsc(FILE_ID))
                    .willReturn(List.of(entity));
            given(fileSharingMapper.toCommentResponseList(List.of(entity)))
                    .willReturn(List.of(response));

            // When
            List<CommentResponse> result = sharedFileCommentService.listComments(FILE_ID);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getBody()).isEqualTo(COMMENT_BODY);
        }

        @Test
        @DisplayName("正常系: コメントが存在しない場合は空リスト")
        void コメント一覧取得_コメントなし_空リスト() {
            // Given
            given(commentRepository.findByFileIdOrderByCreatedAtAsc(FILE_ID))
                    .willReturn(List.of());
            given(fileSharingMapper.toCommentResponseList(List.of()))
                    .willReturn(List.of());

            // When
            List<CommentResponse> result = sharedFileCommentService.listComments(FILE_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // createComment
    // ========================================

    @Nested
    @DisplayName("createComment")
    class CreateComment {

        @Test
        @DisplayName("正常系: コメントが作成される")
        void コメント作成_正常_レスポンス返却() {
            // Given
            CreateCommentRequest request = new CreateCommentRequest(COMMENT_BODY);
            SharedFileCommentEntity savedEntity = createCommentEntity();
            CommentResponse response = createCommentResponse();

            given(commentRepository.save(any(SharedFileCommentEntity.class))).willReturn(savedEntity);
            given(fileSharingMapper.toCommentResponse(savedEntity)).willReturn(response);

            // When
            CommentResponse result = sharedFileCommentService.createComment(FILE_ID, USER_ID, request);

            // Then
            assertThat(result.getFileId()).isEqualTo(FILE_ID);
            assertThat(result.getUserId()).isEqualTo(USER_ID);
            assertThat(result.getBody()).isEqualTo(COMMENT_BODY);
            verify(commentRepository).save(any(SharedFileCommentEntity.class));
        }
    }

    // ========================================
    // updateComment
    // ========================================

    @Nested
    @DisplayName("updateComment")
    class UpdateComment {

        @Test
        @DisplayName("正常系: コメントが更新される")
        void コメント更新_正常_レスポンス返却() {
            // Given
            UpdateCommentRequest request = new UpdateCommentRequest(UPDATED_BODY);
            SharedFileCommentEntity entity = createCommentEntity();
            CommentResponse updatedResponse = new CommentResponse(
                    COMMENT_ID, FILE_ID, USER_ID, UPDATED_BODY,
                    LocalDateTime.now(), LocalDateTime.now());

            given(commentRepository.findById(COMMENT_ID)).willReturn(Optional.of(entity));
            given(commentRepository.save(entity)).willReturn(entity);
            given(fileSharingMapper.toCommentResponse(entity)).willReturn(updatedResponse);

            // When
            CommentResponse result = sharedFileCommentService.updateComment(COMMENT_ID, request);

            // Then
            assertThat(result.getBody()).isEqualTo(UPDATED_BODY);
            verify(commentRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: コメントが存在しないでFILE_SHARING_006例外")
        void コメント更新_コメント不在_FILE_SHARING_006例外() {
            // Given
            UpdateCommentRequest request = new UpdateCommentRequest(UPDATED_BODY);
            given(commentRepository.findById(COMMENT_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> sharedFileCommentService.updateComment(COMMENT_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FILE_SHARING_006"));
        }
    }

    // ========================================
    // deleteComment
    // ========================================

    @Nested
    @DisplayName("deleteComment")
    class DeleteComment {

        @Test
        @DisplayName("正常系: コメントが論理削除される")
        void コメント削除_正常_論理削除実行() {
            // Given
            SharedFileCommentEntity entity = createCommentEntity();
            given(commentRepository.findById(COMMENT_ID)).willReturn(Optional.of(entity));
            given(commentRepository.save(entity)).willReturn(entity);

            // When
            sharedFileCommentService.deleteComment(COMMENT_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(commentRepository).save(entity);
        }

        @Test
        @DisplayName("異常系: コメントが存在しないでFILE_SHARING_006例外")
        void コメント削除_コメント不在_FILE_SHARING_006例外() {
            // Given
            given(commentRepository.findById(COMMENT_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> sharedFileCommentService.deleteComment(COMMENT_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("FILE_SHARING_006"));
        }
    }
}
