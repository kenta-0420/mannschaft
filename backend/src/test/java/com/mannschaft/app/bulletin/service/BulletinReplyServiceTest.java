package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateReplyRequest;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.bulletin.dto.UpdateReplyRequest;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
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
 * {@link BulletinReplyService} の単体テスト。
 * 返信のCRUD・ツリー構造を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinReplyService 単体テスト")
class BulletinReplyServiceTest {

    @Mock
    private BulletinReplyRepository replyRepository;

    @Mock
    private BulletinThreadRepository threadRepository;

    @Mock
    private BulletinThreadService threadService;

    @Mock
    private BulletinMapper bulletinMapper;

    @InjectMocks
    private BulletinReplyService bulletinReplyService;

    private static final Long THREAD_ID = 100L;
    private static final Long REPLY_ID = 200L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final ScopeType SCOPE_TYPE = ScopeType.TEAM;

    private BulletinThreadEntity createWritableThread() {
        return BulletinThreadEntity.builder()
                .categoryId(5L).scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                .authorId(USER_ID).title("テスト").body("本文").build();
    }

    private BulletinThreadEntity createLockedThread() {
        BulletinThreadEntity thread = createWritableThread();
        thread.toggleLock();
        return thread;
    }

    private BulletinReplyEntity createDefaultReply() {
        return BulletinReplyEntity.builder()
                .threadId(THREAD_ID).authorId(USER_ID).body("返信本文").build();
    }

    @Nested
    @DisplayName("createReply")
    class CreateReply {

        @Test
        @DisplayName("返信作成_正常_レスポンス返却")
        void 返信作成_正常_レスポンス返却() {
            // Given
            CreateReplyRequest request = new CreateReplyRequest(null, "返信本文");

            BulletinThreadEntity thread = createWritableThread();
            BulletinReplyEntity savedReply = createDefaultReply();
            ReplyResponse response = new ReplyResponse(REPLY_ID, THREAD_ID, null, USER_ID, "返信本文", false, 0, null, null, null);

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.save(any(BulletinReplyEntity.class))).willReturn(savedReply);
            given(bulletinMapper.toReplyResponse(savedReply)).willReturn(response);

            // When
            ReplyResponse result = bulletinReplyService.createReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(threadRepository).save(thread);
        }

        @Test
        @DisplayName("返信作成_ロック済みスレッド_BusinessException")
        void 返信作成_ロック済みスレッド_BusinessException() {
            // Given
            CreateReplyRequest request = new CreateReplyRequest(null, "返信本文");

            BulletinThreadEntity thread = createLockedThread();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);

            // When & Then
            assertThatThrownBy(() -> bulletinReplyService.createReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.THREAD_LOCKED));
        }

        @Test
        @DisplayName("返信作成_アーカイブ済みスレッド_BusinessException")
        void 返信作成_アーカイブ済みスレッド_BusinessException() {
            // Given
            CreateReplyRequest request = new CreateReplyRequest(null, "返信本文");

            BulletinThreadEntity thread = createWritableThread();
            thread.archive();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);

            // When & Then
            assertThatThrownBy(() -> bulletinReplyService.createReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.THREAD_ARCHIVED));
        }

        @Test
        @DisplayName("返信作成_親返信指定_親の返信カウントインクリメント")
        void 返信作成_親返信指定_親の返信カウントインクリメント() {
            // Given
            Long parentId = 50L;
            CreateReplyRequest request = new CreateReplyRequest(parentId, "子返信");

            BulletinThreadEntity thread = createWritableThread();
            BulletinReplyEntity parentReply = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).authorId(USER_ID).body("親返信").build();
            BulletinReplyEntity savedReply = createDefaultReply();
            ReplyResponse response = new ReplyResponse(REPLY_ID, THREAD_ID, parentId, USER_ID, "子返信", false, 0, null, null, null);

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(parentId, THREAD_ID)).willReturn(Optional.of(parentReply));
            given(replyRepository.save(any(BulletinReplyEntity.class))).willReturn(savedReply);
            given(bulletinMapper.toReplyResponse(savedReply)).willReturn(response);

            // When
            bulletinReplyService.createReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request);

            // Then
            assertThat(parentReply.getReplyCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("返信作成_親返信が異なるスレッド_BusinessException")
        void 返信作成_親返信が異なるスレッド_BusinessException() {
            // Given
            CreateReplyRequest request = new CreateReplyRequest(999L, "子返信");

            BulletinThreadEntity thread = createWritableThread();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(999L, THREAD_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> bulletinReplyService.createReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.PARENT_REPLY_MISMATCH));
        }
    }

    @Nested
    @DisplayName("updateReply")
    class UpdateReply {

        @Test
        @DisplayName("返信更新_正常_レスポンス返却")
        void 返信更新_正常_レスポンス返却() {
            // Given
            UpdateReplyRequest request = new UpdateReplyRequest("更新本文");

            BulletinThreadEntity thread = createWritableThread();
            BulletinReplyEntity entity = createDefaultReply();
            ReplyResponse response = new ReplyResponse(REPLY_ID, THREAD_ID, null, USER_ID, "更新本文", true, 0, null, null, null);

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(REPLY_ID, THREAD_ID)).willReturn(Optional.of(entity));
            given(replyRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toReplyResponse(entity)).willReturn(response);

            // When
            ReplyResponse result = bulletinReplyService.updateReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, REPLY_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("返信更新_他人の投稿_BusinessException")
        void 返信更新_他人の投稿_BusinessException() {
            // Given
            UpdateReplyRequest request = new UpdateReplyRequest("更新本文");

            BulletinThreadEntity thread = createWritableThread();
            BulletinReplyEntity entity = createDefaultReply();

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(REPLY_ID, THREAD_ID)).willReturn(Optional.of(entity));

            // When & Then
            assertThatThrownBy(() -> bulletinReplyService.updateReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, REPLY_ID, 999L, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.NOT_AUTHOR));
        }
    }

    @Nested
    @DisplayName("deleteReply")
    class DeleteReply {

        @Test
        @DisplayName("返信削除_正常_論理削除とカウントデクリメント")
        void 返信削除_正常_論理削除とカウントデクリメント() {
            // Given
            BulletinThreadEntity thread = createWritableThread();
            thread.incrementReplyCount();
            BulletinReplyEntity entity = createDefaultReply();

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(REPLY_ID, THREAD_ID)).willReturn(Optional.of(entity));

            // When
            bulletinReplyService.deleteReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, REPLY_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            assertThat(thread.getReplyCount()).isEqualTo(0);
            verify(threadRepository).save(thread);
        }
    }
}
