package com.mannschaft.app.workflow;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.workflow.dto.WorkflowCommentRequest;
import com.mannschaft.app.workflow.dto.WorkflowCommentResponse;
import com.mannschaft.app.workflow.entity.WorkflowRequestCommentEntity;
import com.mannschaft.app.workflow.entity.WorkflowRequestEntity;
import com.mannschaft.app.workflow.repository.WorkflowRequestCommentRepository;
import com.mannschaft.app.workflow.repository.WorkflowRequestRepository;
import com.mannschaft.app.workflow.service.WorkflowCommentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link WorkflowCommentService} の単体テスト。
 * コメントのCRUDを検証する。
 *
 * <p>認可（Wave 2 トランシェ2C）: {@code findVisibleRequestOrThrow} が親申請を fetch して
 * 可視性を検証するため、各テストで {@code requestRepository.findById} を申請者本人一致の
 * エンティティでスタブし、既存の振る舞い（アサーション内容）は変更しない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCommentService 単体テスト")
class WorkflowCommentServiceTest {

    @Mock
    private WorkflowRequestCommentRepository commentRepository;

    @Mock
    private WorkflowRequestRepository requestRepository;

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private WorkflowCommentService workflowCommentService;

    private static final Long REQUEST_ID = 200L;
    private static final Long COMMENT_ID = 300L;
    private static final Long USER_ID = 10L;

    /**
     * 申請者本人 = USER_ID の可視申請エンティティ。
     * {@code findVisibleRequestOrThrow} は requestedBy 一致で即許可されるため、
     * accessControlService のメンバーシップ判定は経由しない。
     */
    private WorkflowRequestEntity visibleRequest() {
        return WorkflowRequestEntity.builder()
                .scopeType("TEAM").scopeId(1L).requestedBy(USER_ID).build();
    }

    @Nested
    @DisplayName("createComment")
    class CreateComment {

        @Test
        @DisplayName("コメント作成_正常_レスポンス返却")
        void コメント作成_正常_レスポンス返却() {
            // Given
            WorkflowCommentRequest request = new WorkflowCommentRequest("コメント本文");

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(visibleRequest()));

            WorkflowRequestCommentEntity savedEntity = WorkflowRequestCommentEntity.builder()
                    .requestId(REQUEST_ID).userId(USER_ID).body("コメント本文").build();
            WorkflowCommentResponse response = new WorkflowCommentResponse(
                    COMMENT_ID, REQUEST_ID, USER_ID, "コメント本文", null, null);

            given(commentRepository.save(any(WorkflowRequestCommentEntity.class))).willReturn(savedEntity);
            given(workflowMapper.toCommentResponse(savedEntity)).willReturn(response);

            // When
            WorkflowCommentResponse result = workflowCommentService.createComment(REQUEST_ID, USER_ID, request);

            // Then
            assertThat(result.getBody()).isEqualTo("コメント本文");
        }
    }

    @Nested
    @DisplayName("updateComment")
    class UpdateComment {

        @Test
        @DisplayName("コメント更新_正常_レスポンス返却")
        void コメント更新_正常_レスポンス返却() {
            // Given
            WorkflowCommentRequest request = new WorkflowCommentRequest("更新コメント");

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(visibleRequest()));

            WorkflowRequestCommentEntity entity = WorkflowRequestCommentEntity.builder()
                    .requestId(REQUEST_ID).userId(USER_ID).body("元コメント").build();
            WorkflowCommentResponse response = new WorkflowCommentResponse(
                    COMMENT_ID, REQUEST_ID, USER_ID, "更新コメント", null, null);

            given(commentRepository.findByIdAndRequestId(COMMENT_ID, REQUEST_ID)).willReturn(Optional.of(entity));
            given(commentRepository.save(entity)).willReturn(entity);
            given(workflowMapper.toCommentResponse(entity)).willReturn(response);

            // When
            WorkflowCommentResponse result = workflowCommentService.updateComment(
                    REQUEST_ID, COMMENT_ID, USER_ID, request);

            // Then
            assertThat(result.getBody()).isEqualTo("更新コメント");
        }

        @Test
        @DisplayName("コメント更新_存在しない_BusinessException")
        void コメント更新_存在しない_BusinessException() {
            // Given
            WorkflowCommentRequest request = new WorkflowCommentRequest("更新");

            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(visibleRequest()));
            given(commentRepository.findByIdAndRequestId(COMMENT_ID, REQUEST_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> workflowCommentService.updateComment(REQUEST_ID, COMMENT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.COMMENT_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("deleteComment")
    class DeleteComment {

        @Test
        @DisplayName("コメント削除_正常_論理削除実行")
        void コメント削除_正常_論理削除実行() {
            // Given
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(visibleRequest()));

            WorkflowRequestCommentEntity entity = WorkflowRequestCommentEntity.builder()
                    .requestId(REQUEST_ID).userId(USER_ID).body("コメント").build();

            given(commentRepository.findByIdAndRequestId(COMMENT_ID, REQUEST_ID)).willReturn(Optional.of(entity));

            // When
            workflowCommentService.deleteComment(REQUEST_ID, COMMENT_ID, USER_ID);

            // Then
            verify(commentRepository).save(entity);
        }

        @Test
        @DisplayName("コメント削除_存在しない_BusinessException")
        void コメント削除_存在しない_BusinessException() {
            // Given
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(visibleRequest()));
            given(commentRepository.findByIdAndRequestId(COMMENT_ID, REQUEST_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> workflowCommentService.deleteComment(REQUEST_ID, COMMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(WorkflowErrorCode.COMMENT_NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("listComments")
    class ListComments {

        @Test
        @DisplayName("コメント一覧取得_正常_リスト返却")
        void コメント一覧取得_正常_リスト返却() {
            // Given
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(visibleRequest()));
            given(commentRepository.findByRequestIdOrderByCreatedAtAsc(REQUEST_ID)).willReturn(List.of());
            given(workflowMapper.toCommentResponseList(List.of())).willReturn(List.of());

            // When
            List<WorkflowCommentResponse> result = workflowCommentService.listComments(REQUEST_ID, USER_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
