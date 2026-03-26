package com.mannschaft.app.circulation;

import com.mannschaft.app.circulation.dto.CommentResponse;
import com.mannschaft.app.circulation.dto.CreateCommentRequest;
import com.mannschaft.app.circulation.dto.UpdateCommentRequest;
import com.mannschaft.app.circulation.entity.CirculationCommentEntity;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import com.mannschaft.app.circulation.repository.CirculationCommentRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.service.CirculationCommentService;
import com.mannschaft.app.common.BusinessException;
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

/**
 * {@link CirculationCommentService} の単体テスト。
 * 回覧コメントのCRUDを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CirculationCommentService 単体テスト")
class CirculationCommentServiceTest {

    @Mock
    private CirculationDocumentRepository documentRepository;

    @Mock
    private CirculationCommentRepository commentRepository;

    @Mock
    private CirculationMapper circulationMapper;

    @InjectMocks
    private CirculationCommentService circulationCommentService;

    private static final Long DOCUMENT_ID = 100L;
    private static final Long COMMENT_ID = 200L;
    private static final Long USER_ID = 10L;

    @Nested
    @DisplayName("createComment")
    class CreateComment {

        @Test
        @DisplayName("コメント作成_正常_レスポンス返却")
        void コメント作成_正常_レスポンス返却() {
            // Given
            CreateCommentRequest request = new CreateCommentRequest("コメント本文");

            CirculationDocumentEntity document = CirculationDocumentEntity.builder()
                    .scopeType("TEAM").scopeId(1L).createdBy(USER_ID)
                    .title("テスト").body("本文").build();
            CirculationCommentEntity savedComment = CirculationCommentEntity.builder()
                    .documentId(DOCUMENT_ID).userId(USER_ID).body("コメント本文").build();
            CommentResponse response = new CommentResponse(COMMENT_ID, DOCUMENT_ID, USER_ID, "コメント本文", null, null);

            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));
            given(commentRepository.save(any(CirculationCommentEntity.class))).willReturn(savedComment);
            given(circulationMapper.toCommentResponse(savedComment)).willReturn(response);

            // When
            CommentResponse result = circulationCommentService.createComment(DOCUMENT_ID, USER_ID, request);

            // Then
            assertThat(result.getBody()).isEqualTo("コメント本文");
            assertThat(document.getCommentCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("コメント作成_文書存在しない_BusinessException")
        void コメント作成_文書存在しない_BusinessException() {
            // Given
            CreateCommentRequest request = new CreateCommentRequest("コメント");
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> circulationCommentService.createComment(DOCUMENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.DOCUMENT_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("updateComment")
    class UpdateComment {

        @Test
        @DisplayName("コメント更新_自分のコメント_正常")
        void コメント更新_自分のコメント_正常() {
            // Given
            UpdateCommentRequest request = new UpdateCommentRequest("更新コメント");

            CirculationCommentEntity entity = CirculationCommentEntity.builder()
                    .documentId(DOCUMENT_ID).userId(USER_ID).body("元コメント").build();
            CommentResponse response = new CommentResponse(COMMENT_ID, DOCUMENT_ID, USER_ID, "更新コメント", null, null);

            given(commentRepository.findByIdAndDocumentId(COMMENT_ID, DOCUMENT_ID)).willReturn(Optional.of(entity));
            given(commentRepository.save(entity)).willReturn(entity);
            given(circulationMapper.toCommentResponse(entity)).willReturn(response);

            // When
            CommentResponse result = circulationCommentService.updateComment(DOCUMENT_ID, COMMENT_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("コメント更新_他人のコメント_BusinessException")
        void コメント更新_他人のコメント_BusinessException() {
            // Given
            UpdateCommentRequest request = new UpdateCommentRequest("更新コメント");

            CirculationCommentEntity entity = CirculationCommentEntity.builder()
                    .documentId(DOCUMENT_ID).userId(USER_ID).body("元コメント").build();

            given(commentRepository.findByIdAndDocumentId(COMMENT_ID, DOCUMENT_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> circulationCommentService.updateComment(DOCUMENT_ID, COMMENT_ID, 999L, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.COMMENT_NOT_OWNED));
        }
    }

    @Nested
    @DisplayName("deleteComment")
    class DeleteComment {

        @Test
        @DisplayName("コメント削除_自分のコメント_正常")
        void コメント削除_自分のコメント_正常() {
            // Given
            CirculationCommentEntity entity = CirculationCommentEntity.builder()
                    .documentId(DOCUMENT_ID).userId(USER_ID).body("コメント").build();
            CirculationDocumentEntity document = CirculationDocumentEntity.builder()
                    .scopeType("TEAM").scopeId(1L).createdBy(USER_ID)
                    .title("テスト").body("本文").build();
            document.incrementCommentCount();

            given(commentRepository.findByIdAndDocumentId(COMMENT_ID, DOCUMENT_ID)).willReturn(Optional.of(entity));
            given(documentRepository.findById(DOCUMENT_ID)).willReturn(Optional.of(document));

            // When
            circulationCommentService.deleteComment(DOCUMENT_ID, COMMENT_ID, USER_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            assertThat(document.getCommentCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("コメント削除_他人のコメント_BusinessException")
        void コメント削除_他人のコメント_BusinessException() {
            // Given
            CirculationCommentEntity entity = CirculationCommentEntity.builder()
                    .documentId(DOCUMENT_ID).userId(USER_ID).body("コメント").build();

            given(commentRepository.findByIdAndDocumentId(COMMENT_ID, DOCUMENT_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> circulationCommentService.deleteComment(DOCUMENT_ID, COMMENT_ID, 999L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CirculationErrorCode.COMMENT_NOT_OWNED));
        }
    }
}
