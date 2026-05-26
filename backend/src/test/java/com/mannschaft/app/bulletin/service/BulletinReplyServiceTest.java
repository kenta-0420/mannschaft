package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateReplyRequest;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.bulletin.dto.UpdateReplyRequest;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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

    @Mock
    private BulletinAccessGuard accessGuard;

    @Mock
    private AuditLogService auditLogService;

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
            ReplyResponse response = new ReplyResponse(REPLY_ID, THREAD_ID, null, USER_ID, "返信本文", false, 0, null, null, 0, null);

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
            ReplyResponse response = new ReplyResponse(REPLY_ID, THREAD_ID, parentId, USER_ID, "子返信", false, 0, null, null, 1, null);

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

        @Test
        @DisplayName("返信作成_トップレベル_depthが0で保存される")
        void 返信作成_トップレベル_depth0() {
            // Given
            CreateReplyRequest request = new CreateReplyRequest(null, "トップレベル返信");
            BulletinThreadEntity thread = createWritableThread();
            ReplyResponse response = new ReplyResponse(REPLY_ID, THREAD_ID, null, USER_ID, "トップレベル返信", false, 0, null, null, 0, null);

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.save(any(BulletinReplyEntity.class))).willReturn(createDefaultReply());
            given(bulletinMapper.toReplyResponse(any(BulletinReplyEntity.class))).willReturn(response);

            // When
            bulletinReplyService.createReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request);

            // Then: 保存される entity の depth は 0
            ArgumentCaptor<BulletinReplyEntity> captor = ArgumentCaptor.forClass(BulletinReplyEntity.class);
            verify(replyRepository).save(captor.capture());
            assertThat(captor.getValue().getDepth()).isZero();
        }

        @Test
        @DisplayName("返信作成_5階層目まで作成可_depthは親+1で保存される")
        void 返信作成_5階層目まで作成可() {
            // Given: 親 depth=3（4階層目）に対し depth=4（5階層目）の返信を作成
            Long parentId = 50L;
            CreateReplyRequest request = new CreateReplyRequest(parentId, "5階層目の返信");
            BulletinThreadEntity thread = createWritableThread();
            BulletinReplyEntity parentReply = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).parentId(40L).depth(3).authorId(USER_ID).body("4階層目").build();
            ReplyResponse response = new ReplyResponse(REPLY_ID, THREAD_ID, parentId, USER_ID, "5階層目の返信", false, 0, null, null, 4, null);

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(parentId, THREAD_ID)).willReturn(Optional.of(parentReply));
            given(replyRepository.save(any(BulletinReplyEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toReplyResponse(any(BulletinReplyEntity.class))).willReturn(response);

            // When
            bulletinReplyService.createReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request);

            // Then: 新規返信 entity（最後に save された方）の depth は 4
            ArgumentCaptor<BulletinReplyEntity> captor = ArgumentCaptor.forClass(BulletinReplyEntity.class);
            verify(replyRepository, atLeastOnce()).save(captor.capture());
            BulletinReplyEntity newReply = captor.getAllValues().stream()
                    .filter(e -> "5階層目の返信".equals(e.getBody()))
                    .findFirst().orElseThrow();
            assertThat(newReply.getDepth()).isEqualTo(4);
        }

        @Test
        @DisplayName("返信作成_6階層目_REPLY_DEPTH_EXCEEDEDで400")
        void 返信作成_6階層目_400() {
            // Given: 親 depth=4（5階層目）に対する返信は depth=5（6階層目）となり上限超過
            Long parentId = 60L;
            CreateReplyRequest request = new CreateReplyRequest(parentId, "6階層目の返信");
            BulletinThreadEntity thread = createWritableThread();
            BulletinReplyEntity parentReply = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).parentId(50L).depth(4).authorId(USER_ID).body("5階層目").build();

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(parentId, THREAD_ID)).willReturn(Optional.of(parentReply));

            // When & Then
            assertThatThrownBy(() -> bulletinReplyService.createReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.REPLY_DEPTH_EXCEEDED));
            // 深さ超過時は新規返信を保存しない
            verify(replyRepository, never()).save(any(BulletinReplyEntity.class));
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
            ReplyResponse response = new ReplyResponse(REPLY_ID, THREAD_ID, null, USER_ID, "更新本文", true, 0, null, null, 0, null);

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

        @Test
        @DisplayName("返信更新_ロック中スレッド_本人でも423")
        void 返信更新_ロック中_423() {
            // Given: ロック中のスレッドでは本人でも返信編集不可
            UpdateReplyRequest request = new UpdateReplyRequest("更新本文");
            BulletinThreadEntity thread = createLockedThread();
            BulletinReplyEntity entity = createDefaultReply();

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(REPLY_ID, THREAD_ID)).willReturn(Optional.of(entity));

            // When & Then（USER_ID は本人）
            assertThatThrownBy(() -> bulletinReplyService.updateReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, REPLY_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.THREAD_LOCKED));
        }
    }

    @Nested
    @DisplayName("deleteReply")
    class DeleteReply {

        @Test
        @DisplayName("返信削除_本人_論理削除とカウントデクリメント_監査ログなし")
        void 返信削除_本人_論理削除とカウントデクリメント() {
            // Given
            BulletinThreadEntity thread = createWritableThread();
            thread.incrementReplyCount();
            BulletinReplyEntity entity = createDefaultReply();

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(REPLY_ID, THREAD_ID)).willReturn(Optional.of(entity));

            // When: 投稿者本人（USER_ID）が削除
            bulletinReplyService.deleteReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, REPLY_ID, USER_ID);

            // Then
            assertThat(entity.getDeletedAt()).isNotNull();
            assertThat(thread.getReplyCount()).isEqualTo(0);
            verify(threadRepository).save(thread);
            verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("返信削除_他者をADMINが削除_監査ログ記録")
        void 返信削除_他者ADMIN_監査ログ() {
            // Given
            BulletinThreadEntity thread = createWritableThread();
            thread.incrementReplyCount();
            BulletinReplyEntity entity = createDefaultReply(); // authorId = USER_ID
            Long adminUserId = 999L;

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(REPLY_ID, THREAD_ID)).willReturn(Optional.of(entity));

            // When: 他者（adminUserId）が削除
            bulletinReplyService.deleteReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, REPLY_ID, adminUserId);

            // Then: 管理権限チェック通過 + 監査ログ記録
            verify(accessGuard).requireManageContent(adminUserId, SCOPE_TYPE, SCOPE_ID);
            verify(auditLogService).record(eq("BULLETIN_REPLY_DELETED"), eq(adminUserId), eq(USER_ID),
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("返信削除_他者を非管理者が削除_403")
        void 返信削除_他者_非管理者_403() {
            // Given
            BulletinThreadEntity thread = createWritableThread();
            BulletinReplyEntity entity = createDefaultReply();
            Long otherUserId = 999L;

            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByIdAndThreadId(REPLY_ID, THREAD_ID)).willReturn(Optional.of(entity));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).requireManageContent(otherUserId, SCOPE_TYPE, SCOPE_ID);

            // When & Then
            assertThatThrownBy(() -> bulletinReplyService.deleteReply(SCOPE_TYPE, SCOPE_ID, THREAD_ID, REPLY_ID, otherUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            verify(replyRepository, never()).save(any());
        }
    }
}
