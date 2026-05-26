package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CreateThreadRequest;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.dto.UpdateThreadRequest;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BulletinThreadService} の単体テスト。
 * スレッドのCRUD・検索・状態管理・認可を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinThreadService 単体テスト")
class BulletinThreadServiceTest {

    @Mock
    private BulletinThreadRepository threadRepository;

    @Mock
    private BulletinCategoryService categoryService;

    @Mock
    private BulletinMapper bulletinMapper;

    @Mock
    private BulletinAccessGuard accessGuard;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PostingIdentityService postingIdentityService;

    @InjectMocks
    private BulletinThreadService bulletinThreadService;

    private static final Long THREAD_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long CATEGORY_ID = 5L;
    private static final ScopeType SCOPE_TYPE = ScopeType.TEAM;

    private BulletinThreadEntity createDefaultThread() {
        return BulletinThreadEntity.builder()
                .categoryId(CATEGORY_ID)
                .scopeType(SCOPE_TYPE)
                .scopeId(SCOPE_ID)
                .authorId(USER_ID)
                .title("テストスレッド")
                .body("テスト本文")
                .priority(Priority.INFO)
                .readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                .build();
    }

    private ThreadResponse createThreadResponse() {
        return new ThreadResponse(
                THREAD_ID, CATEGORY_ID, "TEAM", SCOPE_ID, USER_ID,
                "テストスレッド", "テスト本文", "INFO", "COUNT_ONLY",
                false, false, false, 0, 0, null, null, null, null, null);
    }

    // ========================================
    // listThreads
    // ========================================

    @Nested
    @DisplayName("listThreads")
    class ListThreads {

        @Test
        @DisplayName("スレッド一覧取得_正常_ページ返却")
        void スレッド一覧取得_正常_ページ返却() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
            given(threadRepository.findByScopeTypeAndScopeIdOrderByIsPinnedDescUpdatedAtDesc(
                    SCOPE_TYPE, SCOPE_ID, PageRequest.of(0, 10))).willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            Page<ThreadResponse> result = bulletinThreadService.listThreads(SCOPE_TYPE, SCOPE_ID, USER_ID, PageRequest.of(0, 10));

            // Then
            assertThat(result.getContent()).hasSize(1);
            verify(accessGuard).checkMembership(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("スレッド一覧取得_非メンバー_403")
        void スレッド一覧取得_非メンバー_403() {
            // Given: 非メンバーは checkMembership で 403
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).checkMembership(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.listThreads(SCOPE_TYPE, SCOPE_ID, USER_ID, PageRequest.of(0, 10)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    // ========================================
    // listThreadsByCategory
    // ========================================

    @Nested
    @DisplayName("listThreadsByCategory")
    class ListThreadsByCategory {

        @Test
        @DisplayName("カテゴリ指定一覧取得_正常_ページ返却")
        void カテゴリ指定一覧取得_正常_ページ返却() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
            given(threadRepository.findByCategoryIdOrderByIsPinnedDescUpdatedAtDesc(
                    CATEGORY_ID, PageRequest.of(0, 10))).willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            Page<ThreadResponse> result = bulletinThreadService.listThreadsByCategory(
                    SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, USER_ID, PageRequest.of(0, 10));

            // Then
            assertThat(result.getContent()).hasSize(1);
            verify(accessGuard).checkMembership(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }
    }

    // ========================================
    // getThread
    // ========================================

    @Nested
    @DisplayName("getThread")
    class GetThread {

        @Test
        @DisplayName("スレッド詳細取得_正常_レスポンス返却")
        void スレッド詳細取得_正常_レスポンス返却() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            ThreadResponse result = bulletinThreadService.getThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID);

            // Then
            assertThat(result.getTitle()).isEqualTo("テストスレッド");
        }

        @Test
        @DisplayName("スレッド詳細取得_存在しない_BusinessException")
        void スレッド詳細取得_存在しない_BusinessException() {
            // Given
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.getThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.THREAD_NOT_FOUND));
        }
    }

    // ========================================
    // searchThreads
    // ========================================

    @Nested
    @DisplayName("searchThreads")
    class SearchThreads {

        @Test
        @DisplayName("全文検索_正常_結果返却")
        void 全文検索_正常_結果返却() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 10), 1);
            given(threadRepository.searchByKeyword("TEAM", SCOPE_ID, "テスト", PageRequest.of(0, 10)))
                    .willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            Page<ThreadResponse> result = bulletinThreadService.searchThreads(
                    SCOPE_TYPE, SCOPE_ID, USER_ID, "テスト", PageRequest.of(0, 10));

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    // ========================================
    // createThread
    // ========================================

    @Nested
    @DisplayName("createThread")
    class CreateThread {

        @Test
        @DisplayName("スレッド作成_正常_レスポンス返却")
        void スレッド作成_正常_レスポンス返却() {
            // Given
            CreateThreadRequest request = new CreateThreadRequest(
                    CATEGORY_ID, "新規スレッド", "新規本文", "IMPORTANT", "INDIVIDUAL", null, null);

            BulletinCategoryEntity category = BulletinCategoryEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("カテゴリ").postMinRole("MEMBER").build();
            BulletinThreadEntity savedEntity = createDefaultThread();
            ThreadResponse response = createThreadResponse();

            given(categoryService.findCategoryOrThrow(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID)).willReturn(category);
            given(threadRepository.save(any(BulletinThreadEntity.class))).willReturn(savedEntity);
            given(bulletinMapper.toThreadResponse(savedEntity)).willReturn(response);

            // When
            ThreadResponse result = bulletinThreadService.createThread(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
            verify(threadRepository).save(any(BulletinThreadEntity.class));
            verify(accessGuard).requireCanCreateThread(USER_ID, SCOPE_TYPE, SCOPE_ID, "MEMBER");
        }

        @Test
        @DisplayName("スレッド作成_SUPPORTER_403")
        void スレッド作成_SUPPORTER_403() {
            // Given: SUPPORTER は requireCanCreateThread で 403
            CreateThreadRequest request = new CreateThreadRequest(
                    CATEGORY_ID, "新規スレッド", "新規本文", "INFO", "COUNT_ONLY", null, null);
            BulletinCategoryEntity category = BulletinCategoryEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("カテゴリ").postMinRole("MEMBER").build();
            given(categoryService.findCategoryOrThrow(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID)).willReturn(category);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).requireCanCreateThread(USER_ID, SCOPE_TYPE, SCOPE_ID, "MEMBER");

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.createThread(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            verify(threadRepository, never()).save(any(BulletinThreadEntity.class));
        }

        @Test
        @DisplayName("スレッド作成_post_min_role超過_403")
        void スレッド作成_post_min_role超過_403() {
            // Given: post_min_role=ADMIN のカテゴリに対し権限不足
            CreateThreadRequest request = new CreateThreadRequest(
                    CATEGORY_ID, "新規スレッド", "新規本文", "INFO", "COUNT_ONLY", null, null);
            BulletinCategoryEntity category = BulletinCategoryEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("管理者専用").postMinRole("ADMIN").build();
            given(categoryService.findCategoryOrThrow(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID)).willReturn(category);
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).requireCanCreateThread(USER_ID, SCOPE_TYPE, SCOPE_ID, "ADMIN");

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.createThread(SCOPE_TYPE, SCOPE_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("スレッド作成_VILLAGEスコープ_postedAs検証が呼ばれる")
        void スレッド作成_VILLAGEスコープ_postedAs検証が呼ばれる() {
            // Given
            UUID villageId = UUID.randomUUID();
            Long teamSubjectId = 567L;
            CreateThreadRequest request = new CreateThreadRequest(
                    CATEGORY_ID, "村への告知", "本文", "INFO", "COUNT_ONLY", null, null,
                    villageId, VillageSubjectType.TEAM, teamSubjectId);

            BulletinCategoryEntity category = BulletinCategoryEntity.builder()
                    .scopeType(ScopeType.VILLAGE).scopeId(0L).name("井戸端").postMinRole("MEMBER").build();
            BulletinThreadEntity savedEntity = createDefaultThread();
            ThreadResponse response = createThreadResponse();

            given(categoryService.findCategoryOrThrow(ScopeType.VILLAGE, 0L, CATEGORY_ID)).willReturn(category);
            given(threadRepository.save(any(BulletinThreadEntity.class))).willReturn(savedEntity);
            given(bulletinMapper.toThreadResponse(savedEntity)).willReturn(response);

            // When
            ThreadResponse result = bulletinThreadService.createThread(
                    ScopeType.VILLAGE, 0L, USER_ID, request);

            // Then: PostingIdentityService が TEAM=567 で検証されること
            assertThat(result).isNotNull();
            verify(postingIdentityService).validatePostingIdentity(
                    eq(USER_ID), eq(villageId), eq(VillageSubjectType.TEAM), eq(teamSubjectId));
        }

        @Test
        @DisplayName("スレッド作成_デフォルト優先度_INFOが設定される")
        void スレッド作成_デフォルト優先度_INFOが設定される() {
            // Given
            CreateThreadRequest request = new CreateThreadRequest(
                    CATEGORY_ID, "新規スレッド", "新規本文", null, null, null, null);

            BulletinCategoryEntity category = BulletinCategoryEntity.builder()
                    .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID).name("カテゴリ").postMinRole("MEMBER").build();
            BulletinThreadEntity savedEntity = createDefaultThread();
            ThreadResponse response = createThreadResponse();

            given(categoryService.findCategoryOrThrow(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID)).willReturn(category);
            given(threadRepository.save(any(BulletinThreadEntity.class))).willReturn(savedEntity);
            given(bulletinMapper.toThreadResponse(savedEntity)).willReturn(response);

            // When
            ThreadResponse result = bulletinThreadService.createThread(SCOPE_TYPE, SCOPE_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }
    }

    // ========================================
    // updateThread
    // ========================================

    @Nested
    @DisplayName("updateThread")
    class UpdateThread {

        @Test
        @DisplayName("スレッド更新_正常_レスポンス返却")
        void スレッド更新_正常_レスポンス返却() {
            // Given
            UpdateThreadRequest request = new UpdateThreadRequest("更新タイトル", "更新本文", "IMPORTANT");

            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();

            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            ThreadResponse result = bulletinThreadService.updateThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("スレッド更新_他人の投稿_非ADMIN_BusinessException")
        void スレッド更新_他人の投稿_非ADMIN_BusinessException() {
            // Given
            UpdateThreadRequest request = new UpdateThreadRequest("更新タイトル", "更新本文", null);

            BulletinThreadEntity entity = createDefaultThread();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            Long otherUserId = 999L;
            given(accessGuard.isAdminOrAbove(otherUserId, SCOPE_TYPE, SCOPE_ID)).willReturn(false);

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.updateThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, otherUserId, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.NOT_AUTHOR));
        }

        @Test
        @DisplayName("スレッド更新_他人の投稿でもADMINなら成功（編集できないバグ是正）")
        void スレッド更新_ADMIN_成功() {
            // Given
            UpdateThreadRequest request = new UpdateThreadRequest("管理者編集", "本文", null);
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            Long adminUserId = 999L;

            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(accessGuard.isAdminOrAbove(adminUserId, SCOPE_TYPE, SCOPE_ID)).willReturn(true);
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            ThreadResponse result = bulletinThreadService.updateThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, adminUserId, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("スレッド更新_ロック中_非ADMIN_423")
        void スレッド更新_ロック中_非ADMIN_423() {
            // Given: ロック中のスレッドは本人でも ADMIN 以外は編集不可
            UpdateThreadRequest request = new UpdateThreadRequest("更新", "本文", null);
            BulletinThreadEntity entity = createDefaultThread();
            entity.toggleLock(); // is_locked = true

            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(accessGuard.isAdminOrAbove(USER_ID, SCOPE_TYPE, SCOPE_ID)).willReturn(false);

            // When & Then（USER_ID は本人だがロック中のため THREAD_LOCKED）
            assertThatThrownBy(() -> bulletinThreadService.updateThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.THREAD_LOCKED));
        }
    }

    // ========================================
    // deleteThread
    // ========================================

    @Nested
    @DisplayName("deleteThread")
    class DeleteThread {

        @Test
        @DisplayName("スレッド削除_本人_論理削除実行_監査ログなし")
        void スレッド削除_本人_論理削除実行() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When: 投稿者本人（USER_ID）が削除
            bulletinThreadService.deleteThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID);

            // Then
            verify(threadRepository).save(entity);
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("スレッド削除_他者をADMINが削除_監査ログ記録")
        void スレッド削除_他者ADMIN_監査ログ() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            Long adminUserId = 999L;
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When: 他者（adminUserId）が削除（requireManageContent は通過＝管理者）
            bulletinThreadService.deleteThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, adminUserId);

            // Then: 監査ログが記録される
            verify(accessGuard).requireManageContent(adminUserId, SCOPE_TYPE, SCOPE_ID);
            verify(auditLogService).record(eq("BULLETIN_THREAD_DELETED"), eq(adminUserId), eq(USER_ID),
                    any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("スレッド削除_他者を非管理者が削除_403")
        void スレッド削除_他者_非管理者_403() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            Long otherUserId = 999L;
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).requireManageContent(otherUserId, SCOPE_TYPE, SCOPE_ID);

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.deleteThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, otherUserId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            verify(threadRepository, never()).save(any());
        }

        @Test
        @DisplayName("スレッド削除_安否確認スレッド_削除拒否")
        void スレッド削除_安否確認_拒否() {
            // Given: source_type=SAFETY_CHECK のスレッドは手動削除不可
            BulletinThreadEntity entity = BulletinThreadEntity.builder()
                    .categoryId(CATEGORY_ID).scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                    .authorId(USER_ID).title("安否確認").body("本文")
                    .priority(Priority.URGENT).readTrackingMode(ReadTrackingMode.INDIVIDUAL)
                    .sourceType("SAFETY_CHECK").build();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            // When & Then: 本人であっても削除拒否
            assertThatThrownBy(() -> bulletinThreadService.deleteThread(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.SAFETY_THREAD_DELETE_FORBIDDEN));
            verify(threadRepository, never()).save(any());
        }
    }

    // ========================================
    // togglePin / toggleLock / archive（管理者操作）
    // ========================================

    @Nested
    @DisplayName("togglePin")
    class TogglePin {

        @Test
        @DisplayName("ピン切替_正常_状態反転")
        void ピン切替_正常_状態反転() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            bulletinThreadService.togglePin(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID);

            // Then
            assertThat(entity.getIsPinned()).isTrue();
            verify(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("ピン切替_管理権限なし_403")
        void ピン切替_権限なし_403() {
            // Given
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.togglePin(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            verify(threadRepository, never()).findByIdAndScopeTypeAndScopeId(anyLong(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("toggleLock")
    class ToggleLock {

        @Test
        @DisplayName("ロック切替_正常_状態反転")
        void ロック切替_正常_状態反転() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            bulletinThreadService.toggleLock(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID);

            // Then
            assertThat(entity.getIsLocked()).isTrue();
            verify(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("ロック切替_管理権限なし_403")
        void ロック切替_権限なし_403() {
            // Given
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.toggleLock(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    @Nested
    @DisplayName("archive")
    class Archive {

        @Test
        @DisplayName("アーカイブ_正常_状態変更")
        void アーカイブ_正常_状態変更() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            bulletinThreadService.archive(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID);

            // Then
            assertThat(entity.getIsArchived()).isTrue();
            verify(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }
    }
}
