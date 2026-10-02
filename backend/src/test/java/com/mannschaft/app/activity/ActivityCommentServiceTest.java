package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.ActivityCommentResponse;
import com.mannschaft.app.activity.dto.CreateCommentRequest;
import com.mannschaft.app.activity.dto.UpdateCommentRequest;
import com.mannschaft.app.activity.entity.ActivityCommentEntity;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityCommentRepository;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.activity.service.ActivityCommentService;
import com.mannschaft.app.activity.service.ActivityScopeAccessGuard;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityCommentService 単体テスト")
class ActivityCommentServiceTest {

    @Mock private ActivityCommentRepository commentRepository;
    @Mock private ActivityResultRepository resultRepository;
    @Mock private ActivityMapper activityMapper;
    @Mock private ActivityScopeAccessGuard scopeAccessGuard;

    @InjectMocks
    private ActivityCommentService service;

    private static final Long COMMENT_ID = 10L;
    private static final Long ACTIVITY_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 7L;

    private ActivityResultEntity teamActivity() {
        return ActivityResultEntity.builder()
                .scopeType(ActivityScopeType.TEAM).scopeId(SCOPE_ID).title("練習").build();
    }

    @Nested
    @DisplayName("listComments")
    class ListComments {

        // AC-1 / AC-3: 他スコープ会員は閲覧不可（IDOR封じ）
        @Test
        @DisplayName("listComments_他スコープ会員は403（COMMON_002）")
        void 一覧_他スコープ_403() {
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(teamActivity()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(scopeAccessGuard).checkMembership(USER_ID, ActivityScopeType.TEAM, SCOPE_ID);

            assertThatThrownBy(() -> service.listComments(ACTIVITY_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        // AC-2: 自スコープ会員は従来通り成功（非回帰）
        @Test
        @DisplayName("listComments_自スコープ会員は成功（非回帰）")
        void 一覧_自スコープ_成功() {
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(teamActivity()));
            given(commentRepository.findByActivityResultIdOrderByCreatedAtAsc(ACTIVITY_ID))
                    .willReturn(List.of());
            given(activityMapper.toCommentResponseList(any())).willReturn(List.of());

            List<ActivityCommentResponse> result = service.listComments(ACTIVITY_ID, USER_ID);
            assertThat(result).isNotNull();
            verify(scopeAccessGuard).checkMembership(USER_ID, ActivityScopeType.TEAM, SCOPE_ID);
        }
    }

    @Nested
    @DisplayName("createComment")
    class CreateComment {
        // AC-2: 自スコープ会員は従来通り成功
        @Test
        @DisplayName("createComment_自スコープ会員は成功（非回帰）")
        void 作成_正常_保存() {
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(teamActivity()));
            CreateCommentRequest request = new CreateCommentRequest("テストコメント");
            ActivityCommentEntity saved = ActivityCommentEntity.builder()
                    .activityResultId(ACTIVITY_ID).userId(USER_ID).body("テストコメント").build();
            given(commentRepository.save(any())).willReturn(saved);
            given(activityMapper.toCommentResponse(saved))
                    .willReturn(new ActivityCommentResponse(1L, ACTIVITY_ID, USER_ID, "テストコメント", null, null));

            ActivityCommentResponse result = service.createComment(ACTIVITY_ID, USER_ID, request);
            assertThat(result).isNotNull();
            verify(scopeAccessGuard).checkMembership(USER_ID, ActivityScopeType.TEAM, SCOPE_ID);
        }

        // AC-1: 他スコープ会員は投稿不可
        @Test
        @DisplayName("createComment_他スコープ会員は403（COMMON_002）")
        void 作成_他スコープ_403() {
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(teamActivity()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(scopeAccessGuard).checkMembership(USER_ID, ActivityScopeType.TEAM, SCOPE_ID);

            assertThatThrownBy(() -> service.createComment(ACTIVITY_ID, USER_ID, new CreateCommentRequest("x")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }
    }

    @Nested
    @DisplayName("updateComment")
    class UpdateComment {
        @Test
        @DisplayName("異常系: コメント不在でACTIVITY_004例外")
        void 更新_不在_例外() {
            given(commentRepository.findById(COMMENT_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.updateComment(COMMENT_ID, USER_ID, new UpdateCommentRequest("更新")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_004"));
        }

        @Test
        @DisplayName("異常系: 他ユーザーのコメント更新でACTIVITY_008例外")
        void 更新_他ユーザー_例外() {
            ActivityCommentEntity entity = ActivityCommentEntity.builder()
                    .activityResultId(ACTIVITY_ID).userId(999L).body("他人のコメント").build();
            given(commentRepository.findById(COMMENT_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.updateComment(COMMENT_ID, USER_ID, new UpdateCommentRequest("更新")))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_008"));
        }
    }

    @Nested
    @DisplayName("deleteComment")
    class DeleteComment {

        // AC-2: 本人は削除成功（非回帰）
        @Test
        @DisplayName("deleteComment_本人は成功（非回帰）")
        void 削除_本人_論理削除() {
            ActivityCommentEntity entity = ActivityCommentEntity.builder()
                    .activityResultId(ACTIVITY_ID).userId(USER_ID).body("コメント").build();
            given(commentRepository.findById(COMMENT_ID)).willReturn(Optional.of(entity));
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(teamActivity()));

            assertThatCode(() -> service.deleteComment(COMMENT_ID, USER_ID)).doesNotThrowAnyException();
            verify(commentRepository).save(entity);
        }

        // AC-1: 他スコープ会員（非本人・非管理者）は削除不可
        @Test
        @DisplayName("deleteComment_他スコープ会員は403（COMMON_002）")
        void 削除_他スコープ_403() {
            ActivityCommentEntity entity = ActivityCommentEntity.builder()
                    .activityResultId(ACTIVITY_ID).userId(999L).body("他人のコメント").build();
            given(commentRepository.findById(COMMENT_ID)).willReturn(Optional.of(entity));
            given(resultRepository.findById(ACTIVITY_ID)).willReturn(Optional.of(teamActivity()));
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(scopeAccessGuard).checkOwnerOrAdmin(
                            eq(USER_ID), eq(999L), eq(ActivityScopeType.TEAM), eq(SCOPE_ID));

            assertThatThrownBy(() -> service.deleteComment(COMMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
        }

        @Test
        @DisplayName("異常系: コメント不在でACTIVITY_004例外")
        void 削除_不在_例外() {
            given(commentRepository.findById(COMMENT_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteComment(COMMENT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ACTIVITY_004"));
        }
    }
}
