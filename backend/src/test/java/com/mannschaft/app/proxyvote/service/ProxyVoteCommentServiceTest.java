package com.mannschaft.app.proxyvote.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxyvote.ProxyVoteErrorCode;
import com.mannschaft.app.proxyvote.ProxyVoteMapper;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.dto.CommentResponse;
import com.mannschaft.app.proxyvote.dto.CreateCommentRequest;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionCommentEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteMotionEntity;
import com.mannschaft.app.proxyvote.entity.ProxyVoteSessionEntity;
import com.mannschaft.app.proxyvote.repository.ProxyVoteMotionCommentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ProxyVoteCommentService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProxyVoteCommentService 単体テスト")
class ProxyVoteCommentServiceTest {

    @Mock private ProxyVoteSessionService sessionService;
    @Mock private ProxyVoteMotionCommentRepository commentRepository;
    @Mock private ProxyVoteMapper mapper;
    @Mock private AccessControlService accessControlService;

    @InjectMocks
    private ProxyVoteCommentService service;

    private static final Long MOTION_ID = 1L;
    private static final Long USER_ID = 100L;

    @Nested
    @DisplayName("createComment")
    class CreateComment {

        @Test
        @DisplayName("異常系: セッションがOPEN以外でコメント投稿不可")
        void セッションOPEN以外() {
            ProxyVoteMotionEntity motion = ProxyVoteMotionEntity.builder().sessionId(10L).build();
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.CLOSED).build();

            given(sessionService.findMotionOrThrow(MOTION_ID)).willReturn(motion);
            given(sessionService.findSessionOrThrow(10L)).willReturn(session);

            CreateCommentRequest request = new CreateCommentRequest("テスト");

            assertThatThrownBy(() -> service.createComment(MOTION_ID, request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.STATUS_MUST_BE_OPEN);
        }

        @Test
        @DisplayName("正常系: コメント投稿成功")
        void コメント投稿成功() {
            ProxyVoteMotionEntity motion = ProxyVoteMotionEntity.builder().sessionId(10L).build();
            ProxyVoteSessionEntity session = ProxyVoteSessionEntity.builder()
                    .status(SessionStatus.OPEN).build();

            given(sessionService.findMotionOrThrow(MOTION_ID)).willReturn(motion);
            given(sessionService.findSessionOrThrow(10L)).willReturn(session);
            ProxyVoteMotionCommentEntity saved = ProxyVoteMotionCommentEntity.builder()
                    .motionId(MOTION_ID).userId(USER_ID).body("テスト").build();
            given(commentRepository.save(any())).willReturn(saved);
            given(mapper.toCommentResponse(saved)).willReturn(null);

            service.createComment(MOTION_ID, new CreateCommentRequest("テスト"), USER_ID);

            verify(commentRepository).save(any());
        }
    }

    @Nested
    @DisplayName("deleteComment")
    class DeleteComment {

        @Test
        @DisplayName("異常系: コメントが見つからない")
        void コメント不存在() {
            given(commentRepository.findById(99L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteComment(MOTION_ID, 99L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.COMMENT_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: motionId不一致でコメント見つからないエラー")
        void motionId不一致() {
            ProxyVoteMotionCommentEntity comment = ProxyVoteMotionCommentEntity.builder()
                    .motionId(999L).userId(USER_ID).body("テスト").build();
            given(commentRepository.findById(1L)).willReturn(Optional.of(comment));

            assertThatThrownBy(() -> service.deleteComment(MOTION_ID, 1L, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ProxyVoteErrorCode.COMMENT_NOT_FOUND);
        }
    }
}
