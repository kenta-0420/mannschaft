package com.mannschaft.app.activity;

import com.mannschaft.app.activity.dto.ActivityCommentResponse;
import com.mannschaft.app.activity.dto.CreateCommentRequest;
import com.mannschaft.app.activity.dto.UpdateCommentRequest;
import com.mannschaft.app.activity.entity.ActivityCommentEntity;
import com.mannschaft.app.activity.repository.ActivityCommentRepository;
import com.mannschaft.app.activity.service.ActivityCommentService;
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

@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityCommentService 単体テスト")
class ActivityCommentServiceTest {

    @Mock private ActivityCommentRepository commentRepository;
    @Mock private ActivityMapper activityMapper;

    @InjectMocks
    private ActivityCommentService service;

    private static final Long COMMENT_ID = 10L;
    private static final Long ACTIVITY_ID = 1L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("createComment")
    class CreateComment {
        @Test
        @DisplayName("正常系: コメントが作成される")
        void 作成_正常_保存() {
            CreateCommentRequest request = new CreateCommentRequest("テストコメント");
            ActivityCommentEntity saved = ActivityCommentEntity.builder()
                    .activityResultId(ACTIVITY_ID).userId(USER_ID).body("テストコメント").build();
            given(commentRepository.save(any())).willReturn(saved);
            given(activityMapper.toCommentResponse(saved))
                    .willReturn(new ActivityCommentResponse(1L, ACTIVITY_ID, USER_ID, "テストコメント", null, null));

            ActivityCommentResponse result = service.createComment(ACTIVITY_ID, USER_ID, request);
            assertThat(result).isNotNull();
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
        @Test
        @DisplayName("正常系: コメントが論理削除される")
        void 削除_正常_論理削除() {
            ActivityCommentEntity entity = ActivityCommentEntity.builder()
                    .activityResultId(ACTIVITY_ID).userId(USER_ID).body("コメント").build();
            given(commentRepository.findById(COMMENT_ID)).willReturn(Optional.of(entity));
            service.deleteComment(COMMENT_ID);
            verify(commentRepository).save(entity);
        }
    }
}
