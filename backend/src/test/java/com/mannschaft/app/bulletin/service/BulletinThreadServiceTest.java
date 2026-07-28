package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.Priority;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.TargetType;
import com.mannschaft.app.bulletin.dto.CreateThreadRequest;
import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.dto.UpdateThreadRequest;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.bulletin.repository.BulletinReactionRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import com.mannschaft.app.village.service.VillageBulletinAccessService;
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
    private BulletinArchiveFolderService archiveFolderService;

    @Mock
    private PostingIdentityService postingIdentityService;

    @Mock
    private VillageBulletinAccessService villageBulletinAccessService;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private BulletinCategoryRepository categoryRepository;

    @Mock
    private BulletinReadStatusRepository readStatusRepository;

    @Mock
    private BulletinReactionRepository reactionRepository;

    @InjectMocks
    private BulletinThreadService bulletinThreadService;

    private static final UUID VILLAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID OTHER_VILLAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

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
        return ThreadResponse.builder()
                .id(THREAD_ID)
                .categoryId(CATEGORY_ID)
                .scopeType("TEAM")
                .scopeId(SCOPE_ID)
                .author(new ThreadResponse.AuthorDto(USER_ID, null, null))
                .title("テストスレッド")
                .body("テスト本文")
                .priority("INFO")
                .readTrackingMode("COUNT_ONLY")
                .isPinned(false)
                .isLocked(false)
                .isArchived(false)
                .replyCount(0)
                .readCount(0)
                .isRead(false)
                .reactionSummary(java.util.Collections.emptyMap())
                .myReactions(java.util.Collections.emptyList())
                .build();
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
            given(threadRepository.findByScopeTypeAndScopeIdAndCategoryIdOrderByIsPinnedDescUpdatedAtDesc(
                    SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, PageRequest.of(0, 10))).willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            Page<ThreadResponse> result = bulletinThreadService.listThreadsByCategory(
                    SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, USER_ID, PageRequest.of(0, 10));

            // Then
            assertThat(result.getContent()).hasSize(1);
            verify(accessGuard).checkMembership(USER_ID, SCOPE_TYPE, SCOPE_ID);
            // カテゴリの帰属検証が呼ばれること（越境 categoryId 差し込みの封鎖）
            verify(categoryService).findCategoryOrThrow(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID);
        }

        @Test
        @DisplayName("カテゴリ指定一覧取得_越境categoryId_CATEGORY_NOT_FOUNDで遮断されスレッド取得に到達しない")
        void カテゴリ指定一覧取得_越境categoryId_遮断() {
            // Given: 当該スコープに属さない categoryId は findCategoryOrThrow が 404 を投げる
            given(categoryService.findCategoryOrThrow(SCOPE_TYPE, SCOPE_ID, CATEGORY_ID))
                    .willThrow(new BusinessException(BulletinErrorCode.CATEGORY_NOT_FOUND));

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.listThreadsByCategory(
                    SCOPE_TYPE, SCOPE_ID, CATEGORY_ID, USER_ID, PageRequest.of(0, 10)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.CATEGORY_NOT_FOUND));
            // 遮断後にスレッド取得クエリへ到達していないこと
            verify(threadRepository, never())
                    .findByScopeTypeAndScopeIdAndCategoryIdOrderByIsPinnedDescUpdatedAtDesc(
                            any(), anyLong(), anyLong(), any());
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
        @DisplayName("アーカイブ_isArchived=true_アーカイブ状態になる")
        void アーカイブ_true_状態変更() {
            // Given
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            bulletinThreadService.archive(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, true, null);

            // Then
            assertThat(entity.getIsArchived()).isTrue();
            verify(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("アーカイブ_isArchived=false_アーカイブ解除される")
        void アーカイブ_false_解除() {
            // Given: 既にアーカイブ済みのスレッド
            BulletinThreadEntity entity = createDefaultThread();
            entity.archive();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            // When
            bulletinThreadService.archive(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, false, null);

            // Then
            assertThat(entity.getIsArchived()).isFalse();
            verify(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("アーカイブ_管理権限なし_403_認可維持")
        void アーカイブ_権限なし_403() {
            // Given: 非管理者は requireManageContent で 403（双方向化しても認可は維持）
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);

            // When & Then
            assertThatThrownBy(() -> bulletinThreadService.archive(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, true, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
            verify(threadRepository, never()).findByIdAndScopeTypeAndScopeId(anyLong(), any(), anyLong());
        }
    }

    // ========================================
    // 保管庫フォルダ連携（archive 拡張 / listArchiveThreads / moveThreadToFolder）
    // ========================================

    @Nested
    @DisplayName("保管庫フォルダ連携")
    class ArchiveFolderIntegration {

        @Test
        @DisplayName("アーカイブ時にフォルダ指定_検証してフォルダ割当")
        void アーカイブ_フォルダ指定_割当() {
            UUID folderId = UUID.randomUUID();
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            bulletinThreadService.archive(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, true, folderId);

            assertThat(entity.getIsArchived()).isTrue();
            assertThat(entity.getArchiveFolderId()).isEqualTo(folderId);
            verify(archiveFolderService).validateFolderInScope(SCOPE_TYPE, SCOPE_ID, folderId);
        }

        @Test
        @DisplayName("アーカイブ解除時_フォルダがNULLにリセットされる")
        void アーカイブ解除_フォルダNULL() {
            UUID folderId = UUID.randomUUID();
            BulletinThreadEntity entity = createDefaultThread();
            entity.archive();
            entity.assignArchiveFolder(folderId);
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            bulletinThreadService.archive(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, false, folderId);

            assertThat(entity.getIsArchived()).isFalse();
            assertThat(entity.getArchiveFolderId()).isNull();
        }

        @Test
        @DisplayName("フォルダ振り分け_未アーカイブスレッド_409")
        void 振り分け_未アーカイブ_409() {
            BulletinThreadEntity entity = createDefaultThread(); // is_archived=false
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            assertThatThrownBy(() -> bulletinThreadService.moveThreadToFolder(
                    SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, UUID.randomUUID()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.THREAD_NOT_ARCHIVED));
        }

        @Test
        @DisplayName("フォルダ振り分け_アーカイブ済み_フォルダ割当")
        void 振り分け_正常() {
            UUID folderId = UUID.randomUUID();
            BulletinThreadEntity entity = createDefaultThread();
            entity.archive();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(entity)).willReturn(entity);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            bulletinThreadService.moveThreadToFolder(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID, folderId);

            assertThat(entity.getArchiveFolderId()).isEqualTo(folderId);
            verify(archiveFolderService).validateFolderInScope(SCOPE_TYPE, SCOPE_ID, folderId);
            verify(accessGuard).requireManageContent(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("保管庫スレッド一覧_未分類_直下クエリ")
        void 保管庫一覧_未分類() {
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1);
            given(threadRepository.findByScopeTypeAndScopeIdAndIsArchivedTrueAndArchiveFolderIdIsNull(
                    SCOPE_TYPE, SCOPE_ID, PageRequest.of(0, 20))).willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            Page<ThreadResponse> result = bulletinThreadService.listArchiveThreads(
                    SCOPE_TYPE, SCOPE_ID, USER_ID, null, false, PageRequest.of(0, 20));

            assertThat(result.getContent()).hasSize(1);
            verify(accessGuard).checkMembership(USER_ID, SCOPE_TYPE, SCOPE_ID);
        }

        @Test
        @DisplayName("保管庫スレッド一覧_全件_allクエリ")
        void 保管庫一覧_全件() {
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1);
            given(threadRepository.findByScopeTypeAndScopeIdAndIsArchivedTrue(
                    SCOPE_TYPE, SCOPE_ID, PageRequest.of(0, 20))).willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            Page<ThreadResponse> result = bulletinThreadService.listArchiveThreads(
                    SCOPE_TYPE, SCOPE_ID, USER_ID, null, true, PageRequest.of(0, 20));

            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("保管庫スレッド一覧_フォルダ指定_scope検証してフォルダクエリ")
        void 保管庫一覧_フォルダ指定() {
            UUID folderId = UUID.randomUUID();
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1);
            given(threadRepository.findByScopeTypeAndScopeIdAndIsArchivedTrueAndArchiveFolderId(
                    SCOPE_TYPE, SCOPE_ID, folderId, PageRequest.of(0, 20))).willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            Page<ThreadResponse> result = bulletinThreadService.listArchiveThreads(
                    SCOPE_TYPE, SCOPE_ID, USER_ID, folderId, false, PageRequest.of(0, 20));

            assertThat(result.getContent()).hasSize(1);
            verify(archiveFolderService).validateFolderInScope(SCOPE_TYPE, SCOPE_ID, folderId);
        }
    }

    // ========================================================================
    // F17.1 村掲示板グローバル方式 — 村スレッド一覧・詳細（読取経路 + 可視性認可）
    // ========================================================================

    private BulletinThreadEntity createVillageThread() {
        return BulletinThreadEntity.builder()
                .categoryId(CATEGORY_ID)
                .scopeType(ScopeType.VILLAGE)
                .scopeId(0L)
                .scopeVillageId(VILLAGE_ID)
                .authorId(USER_ID)
                .title("村のスレッド")
                .body("村の本文")
                .priority(Priority.INFO)
                .readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                .build();
    }

    @Nested
    @DisplayName("listVillageThreads（村スレッド一覧）")
    class ListVillageThreads {

        @Test
        @DisplayName("村スレッド一覧_カテゴリ未指定_可視性認可してピン優先一覧")
        void 村一覧_カテゴリ未指定() {
            BulletinThreadEntity entity = createVillageThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1);
            given(threadRepository.findByScopeVillageIdOrderByIsPinnedDescUpdatedAtDesc(
                    VILLAGE_ID, PageRequest.of(0, 20))).willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            Page<ThreadResponse> result = bulletinThreadService.listVillageThreads(
                    VILLAGE_ID, null, USER_ID, PageRequest.of(0, 20));

            assertThat(result.getContent()).hasSize(1);
            // 可視性認可が呼ばれること
            verify(villageBulletinAccessService).checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID);
            // カテゴリ未指定なので全件メソッドを使う
            verify(threadRepository).findByScopeVillageIdOrderByIsPinnedDescUpdatedAtDesc(
                    VILLAGE_ID, PageRequest.of(0, 20));
        }

        @Test
        @DisplayName("村スレッド一覧_カテゴリ指定_カテゴリ絞り込みクエリ")
        void 村一覧_カテゴリ指定() {
            BulletinThreadEntity entity = createVillageThread();
            ThreadResponse response = createThreadResponse();
            Page<BulletinThreadEntity> page = new PageImpl<>(List.of(entity), PageRequest.of(0, 20), 1);
            given(threadRepository.findByScopeVillageIdAndCategoryIdOrderByIsPinnedDescUpdatedAtDesc(
                    VILLAGE_ID, CATEGORY_ID, PageRequest.of(0, 20))).willReturn(page);
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            Page<ThreadResponse> result = bulletinThreadService.listVillageThreads(
                    VILLAGE_ID, CATEGORY_ID, USER_ID, PageRequest.of(0, 20));

            assertThat(result.getContent()).hasSize(1);
            verify(threadRepository).findByScopeVillageIdAndCategoryIdOrderByIsPinnedDescUpdatedAtDesc(
                    VILLAGE_ID, CATEGORY_ID, PageRequest.of(0, 20));
        }

        @Test
        @DisplayName("村スレッド一覧_MEMBERS_ONLY非メンバー_403で弾かれクエリは走らない")
        void 村一覧_認可失敗_403() {
            doThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN))
                    .when(villageBulletinAccessService)
                    .checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID);

            assertThatThrownBy(() -> bulletinThreadService.listVillageThreads(
                    VILLAGE_ID, null, USER_ID, PageRequest.of(0, 20)))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_VIEW_FORBIDDEN);

            verify(threadRepository, never()).findByScopeVillageIdOrderByIsPinnedDescUpdatedAtDesc(
                    any(), any());
        }
    }

    @Nested
    @DisplayName("getVillageThread / getThreadGlobal（村スレッド詳細）")
    class GetVillageThread {

        @Test
        @DisplayName("村スレッド詳細_所有村一致_可視性認可して200相当")
        void 村詳細_正常() {
            BulletinThreadEntity entity = createVillageThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findByIdAndScopeVillageId(THREAD_ID, VILLAGE_ID))
                    .willReturn(Optional.of(entity));
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            ThreadResponse result = bulletinThreadService.getVillageThread(VILLAGE_ID, THREAD_ID, USER_ID);

            assertThat(result).isNotNull();
            verify(villageBulletinAccessService).checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID);
        }

        @Test
        @DisplayName("村スレッド詳細_他村のスレッド_THREAD_NOT_FOUND（404相当）")
        void 村詳細_他村_404() {
            given(threadRepository.findByIdAndScopeVillageId(THREAD_ID, OTHER_VILLAGE_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> bulletinThreadService.getVillageThread(OTHER_VILLAGE_ID, THREAD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(BulletinErrorCode.THREAD_NOT_FOUND);
        }

        @Test
        @DisplayName("グローバル詳細_VILLAGEスレッド_村可視性認可経路")
        void グローバル詳細_村スレッド() {
            BulletinThreadEntity entity = createVillageThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            ThreadResponse result = bulletinThreadService.getThreadGlobal(THREAD_ID, USER_ID);

            assertThat(result).isNotNull();
            // VILLAGE は村可視性認可、所属認可（accessGuard）は呼ばれない
            verify(villageBulletinAccessService).checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID);
            verify(accessGuard, never()).checkMembership(anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("グローバル詳細_TEAMスレッド_所属認可経路へ委譲")
        void グローバル詳細_チームスレッド() {
            BulletinThreadEntity entity = createDefaultThread();
            ThreadResponse response = createThreadResponse();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(bulletinMapper.toThreadResponse(entity)).willReturn(response);

            ThreadResponse result = bulletinThreadService.getThreadGlobal(THREAD_ID, USER_ID);

            assertThat(result).isNotNull();
            // TEAM は所属認可、村可視性認可は呼ばれない
            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, SCOPE_ID);
            verify(villageBulletinAccessService, never()).checkVillageBulletinViewAccess(any(), any());
        }

        @Test
        @DisplayName("グローバル詳細_スレッド不在_THREAD_NOT_FOUND（404相当）")
        void グローバル詳細_不在_404() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> bulletinThreadService.getThreadGlobal(THREAD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(BulletinErrorCode.THREAD_NOT_FOUND);
        }
    }

    // ========================================================================
    // F17.1 村掲示板グローバル方式 — 書込・モデレーション（足軽C）
    // ========================================================================

    private static final Long OTHER_USER_ID = 99L;

    /** author が別ユーザーの村スレッド（モデレーション/他者投稿の検証用）。 */
    private BulletinThreadEntity villageThreadByOther() {
        return BulletinThreadEntity.builder()
                .categoryId(CATEGORY_ID)
                .scopeType(ScopeType.VILLAGE)
                .scopeId(0L)
                .scopeVillageId(VILLAGE_ID)
                .authorId(OTHER_USER_ID)
                .title("他者の村スレッド")
                .body("本文")
                .priority(Priority.INFO)
                .readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                .build();
    }

    @Nested
    @DisplayName("updateThreadGlobal（グローバル更新）")
    class UpdateThreadGlobal {

        @Test
        @DisplayName("村スレッド_投稿者本人_モデレーター認可不要で更新")
        void 村_本人更新() {
            BulletinThreadEntity entity = createVillageThread(); // author = USER_ID
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(threadRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toThreadResponse(any())).willReturn(createThreadResponse());

            UpdateThreadRequest req = new UpdateThreadRequest("新題名", "新本文", "URGENT");
            bulletinThreadService.updateThreadGlobal(THREAD_ID, USER_ID, req);

            // 本人なのでモデレーター認可は呼ばれない
            verify(villageBulletinAccessService, never()).checkVillageBulletinModerator(any(), any());
            assertThat(entity.getTitle()).isEqualTo("新題名");
        }

        @Test
        @DisplayName("村スレッド_他者投稿_モデレーターなら更新可")
        void 村_他者投稿モデレーター更新() {
            BulletinThreadEntity entity = villageThreadByOther();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(threadRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toThreadResponse(any())).willReturn(createThreadResponse());

            UpdateThreadRequest req = new UpdateThreadRequest("題名", "本文", null);
            bulletinThreadService.updateThreadGlobal(THREAD_ID, USER_ID, req);

            verify(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);
        }

        @Test
        @DisplayName("村スレッド_他者投稿_非モデレーター_403")
        void 村_他者投稿非モデレーター_403() {
            BulletinThreadEntity entity = villageThreadByOther();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            doThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN))
                    .when(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);

            UpdateThreadRequest req = new UpdateThreadRequest("題名", "本文", null);
            assertThatThrownBy(() -> bulletinThreadService.updateThreadGlobal(THREAD_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN);
        }

        @Test
        @DisplayName("TEAMスレッド_既存updateThreadへ委譲")
        void チーム_委譲() {
            BulletinThreadEntity entity = createDefaultThread(); // TEAM, author = USER_ID
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toThreadResponse(any())).willReturn(createThreadResponse());

            UpdateThreadRequest req = new UpdateThreadRequest("題名", "本文", null);
            bulletinThreadService.updateThreadGlobal(THREAD_ID, USER_ID, req);

            // 既存経路の所属認可が効く
            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, SCOPE_ID);
            verify(villageBulletinAccessService, never()).checkVillageBulletinModerator(any(), any());
        }

        @Test
        @DisplayName("スレッド不在_THREAD_NOT_FOUND")
        void 不在_404() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.empty());
            UpdateThreadRequest req = new UpdateThreadRequest("題名", "本文", null);
            assertThatThrownBy(() -> bulletinThreadService.updateThreadGlobal(THREAD_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(BulletinErrorCode.THREAD_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleteThreadGlobal（グローバル削除）")
    class DeleteThreadGlobal {

        @Test
        @DisplayName("村スレッド_本人削除_モデレーター認可不要")
        void 村_本人削除() {
            BulletinThreadEntity entity = createVillageThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));

            bulletinThreadService.deleteThreadGlobal(THREAD_ID, USER_ID);

            assertThat(entity.getDeletedAt()).isNotNull();
            verify(villageBulletinAccessService, never()).checkVillageBulletinModerator(any(), any());
        }

        @Test
        @DisplayName("村スレッド_他者投稿_非モデレーター_403")
        void 村_他者投稿非モデレーター_403() {
            BulletinThreadEntity entity = villageThreadByOther();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            doThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN))
                    .when(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);

            assertThatThrownBy(() -> bulletinThreadService.deleteThreadGlobal(THREAD_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN);
        }

        @Test
        @DisplayName("TEAMスレッド_既存deleteThreadへ委譲")
        void チーム_委譲() {
            BulletinThreadEntity entity = createDefaultThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            bulletinThreadService.deleteThreadGlobal(THREAD_ID, USER_ID);

            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, SCOPE_ID);
            assertThat(entity.getDeletedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("モデレーション set 方式（priority/pin/lock/archive）")
    class Moderation {

        @Test
        @DisplayName("村_pin設定_モデレーター認可してset")
        void 村_pin設定() {
            BulletinThreadEntity entity = createVillageThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(threadRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toThreadResponse(any())).willReturn(createThreadResponse());

            bulletinThreadService.setPinGlobal(THREAD_ID, USER_ID, true);

            verify(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);
            assertThat(entity.getIsPinned()).isTrue();
        }

        @Test
        @DisplayName("村_lock解除_set方式でfalseを反映")
        void 村_lock解除() {
            BulletinThreadEntity entity = createVillageThread();
            entity.setLocked(true);
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(threadRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toThreadResponse(any())).willReturn(createThreadResponse());

            bulletinThreadService.setLockGlobal(THREAD_ID, USER_ID, false);

            verify(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);
            assertThat(entity.getIsLocked()).isFalse();
        }

        @Test
        @DisplayName("村_priority変更_モデレーターのみ")
        void 村_priority変更() {
            BulletinThreadEntity entity = createVillageThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(threadRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toThreadResponse(any())).willReturn(createThreadResponse());

            bulletinThreadService.changePriorityGlobal(THREAD_ID, USER_ID, "URGENT");

            verify(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);
            assertThat(entity.getPriority()).isEqualTo(Priority.URGENT);
        }

        @Test
        @DisplayName("村_pin設定_非モデレーター_403")
        void 村_pin非モデレーター_403() {
            BulletinThreadEntity entity = createVillageThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            doThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN))
                    .when(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);

            assertThatThrownBy(() -> bulletinThreadService.setPinGlobal(THREAD_ID, USER_ID, true))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN);
        }

        @Test
        @DisplayName("TEAM_pin設定_既存管理権限を要求")
        void チーム_pin設定_管理権限() {
            BulletinThreadEntity entity = createDefaultThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(threadRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toThreadResponse(any())).willReturn(createThreadResponse());

            bulletinThreadService.setPinGlobal(THREAD_ID, USER_ID, true);

            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, SCOPE_ID);
            verify(accessGuard).requireManageContent(USER_ID, ScopeType.TEAM, SCOPE_ID);
            verify(villageBulletinAccessService, never()).checkVillageBulletinModerator(any(), any());
        }

        @Test
        @DisplayName("村_archive_モデレーター認可してアーカイブ")
        void 村_archive() {
            BulletinThreadEntity entity = createVillageThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(threadRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toThreadResponse(any())).willReturn(createThreadResponse());

            bulletinThreadService.archiveGlobal(THREAD_ID, USER_ID, true);

            verify(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);
            assertThat(entity.getIsArchived()).isTrue();
        }

        @Test
        @DisplayName("TEAM_archive_既存archiveへ委譲")
        void チーム_archive委譲() {
            BulletinThreadEntity entity = createDefaultThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(entity));
            given(threadRepository.findByIdAndScopeTypeAndScopeId(THREAD_ID, ScopeType.TEAM, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(threadRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toThreadResponse(any())).willReturn(createThreadResponse());

            bulletinThreadService.archiveGlobal(THREAD_ID, USER_ID, true);

            verify(accessGuard).requireManageContent(USER_ID, ScopeType.TEAM, SCOPE_ID);
            assertThat(entity.getIsArchived()).isTrue();
        }
    }

    @Nested
    @DisplayName("createThreadGlobal（グローバル作成）")
    class CreateThreadGlobal {

        @Test
        @DisplayName("VILLAGE作成_既存createThreadへ委譲_主体検証実行")
        void 村_作成委譲() {
            given(threadRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toThreadResponse(any())).willReturn(createThreadResponse());

            CreateThreadRequest req = new CreateThreadRequest(
                    null, "題名", "本文", "INFO", "COUNT_ONLY", null, null,
                    VILLAGE_ID, VillageSubjectType.USER, USER_ID);
            bulletinThreadService.createThreadGlobal(ScopeType.VILLAGE, 0L, USER_ID, req);

            // VILLAGE 作成は所属認可 + 作成権限 + 投稿主体検証が走る
            verify(accessGuard).checkMembership(USER_ID, ScopeType.VILLAGE, 0L);
            verify(postingIdentityService)
                    .validatePostingIdentity(USER_ID, VILLAGE_ID, VillageSubjectType.USER, USER_ID);
        }
    }

    // ========================================================================
    // enrichment（投稿者名/アバター・カテゴリ名/色・既読・リアクション集計）契約テスト
    // ========================================================================

    @Nested
    @DisplayName("enrichThreads（一覧/詳細の enrichment 契約 + N+1 番人）")
    class EnrichThreads {

        private static final Long THREAD_ID_2 = 200L;
        private static final Long THREAD_ID_3 = 300L;
        private static final Long AUTHOR_2 = 22L;
        private static final Long AUTHOR_3 = 33L;
        private static final Long CATEGORY_ID_2 = 6L;

        private BulletinThreadEntity threadWith(Long id, Long authorId, Long categoryId) {
            BulletinThreadEntity e = BulletinThreadEntity.builder()
                    .categoryId(categoryId)
                    .scopeType(ScopeType.TEAM)
                    .scopeId(SCOPE_ID)
                    .authorId(authorId)
                    .title("スレ" + id)
                    .body("本文" + id)
                    .priority(Priority.INFO)
                    .readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                    .build();
            org.springframework.test.util.ReflectionTestUtils.setField(e, "id", id);
            return e;
        }

        private void stubBaseMapper() {
            // 基底変換はフラット DTO を返す（実 mapper を模した素直なフラット応答）
            given(bulletinMapper.toThreadResponse(any(BulletinThreadEntity.class)))
                    .willAnswer(inv -> {
                        BulletinThreadEntity e = inv.getArgument(0);
                        return ThreadResponse.builder()
                                .id(e.getId())
                                .categoryId(e.getCategoryId())
                                .scopeType("TEAM")
                                .scopeId(e.getScopeId())
                                .author(new ThreadResponse.AuthorDto(e.getAuthorId(), null, null))
                                .title(e.getTitle())
                                .body(e.getBody())
                                .priority("INFO")
                                .readTrackingMode("COUNT_ONLY")
                                .isPinned(false).isLocked(false).isArchived(false)
                                .replyCount(0).readCount(0).isRead(false)
                                .reactionSummary(java.util.Collections.emptyMap())
                                .myReactions(java.util.Collections.emptyList())
                                .build();
                    });
        }

        @Test
        @DisplayName("AC-2: author.displayName が NameResolver 解決値で入る")
        void 投稿者表示名が解決される() {
            BulletinThreadEntity t1 = threadWith(THREAD_ID, USER_ID, CATEGORY_ID);
            stubBaseMapper();
            given(nameResolverService.resolveUserDisplayNames(any()))
                    .willReturn(java.util.Map.of(USER_ID, "田中太郎"));

            List<ThreadResponse> result = bulletinThreadService.enrichThreads(List.of(t1), USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getAuthor().id()).isEqualTo(USER_ID);
            assertThat(result.get(0).getAuthor().displayName()).isEqualTo("田中太郎");
        }

        @Test
        @DisplayName("AC-2: 未解決の投稿者はフォールバック表示名になる")
        void 投稿者表示名_未解決はフォールバック() {
            BulletinThreadEntity t1 = threadWith(THREAD_ID, USER_ID, CATEGORY_ID);
            stubBaseMapper();
            given(nameResolverService.resolveUserDisplayNames(any()))
                    .willReturn(java.util.Collections.emptyMap());

            List<ThreadResponse> result = bulletinThreadService.enrichThreads(List.of(t1), USER_ID);

            assertThat(result.get(0).getAuthor().displayName()).isEqualTo("不明なユーザー");
        }

        @Test
        @DisplayName("AC-2: avatarUrl が NameResolverService から解決される（auth 直参照を避け common 経由）")
        void アバターURLが解決される() {
            BulletinThreadEntity t1 = threadWith(THREAD_ID, USER_ID, CATEGORY_ID);
            stubBaseMapper();
            given(nameResolverService.resolveUserAvatarUrls(any()))
                    .willReturn(java.util.Map.of(USER_ID, "https://cdn/avatar.png"));

            List<ThreadResponse> result = bulletinThreadService.enrichThreads(List.of(t1), USER_ID);

            assertThat(result.get(0).getAuthor().avatarUrl()).isEqualTo("https://cdn/avatar.png");
        }

        @Test
        @DisplayName("AC-3: isRead が既読 threadId 集合に基づき true/false")
        void 既読フラグが集合で決まる() {
            BulletinThreadEntity read = threadWith(THREAD_ID, USER_ID, CATEGORY_ID);
            BulletinThreadEntity unread = threadWith(THREAD_ID_2, AUTHOR_2, CATEGORY_ID);
            stubBaseMapper();
            given(readStatusRepository.findReadThreadIds(any(), eq(USER_ID)))
                    .willReturn(List.of(THREAD_ID));

            List<ThreadResponse> result = bulletinThreadService.enrichThreads(List.of(read, unread), USER_ID);

            assertThat(result.get(0).getIsRead()).isTrue();
            assertThat(result.get(1).getIsRead()).isFalse();
        }

        @Test
        @DisplayName("AC-4: categoryName/color が categoryId から解決される")
        void カテゴリ名と色が解決される() {
            BulletinThreadEntity t1 = threadWith(THREAD_ID, USER_ID, CATEGORY_ID);
            stubBaseMapper();
            BulletinCategoryEntity category = BulletinCategoryEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(SCOPE_ID).name("お知らせ").color("#00FF00").build();
            org.springframework.test.util.ReflectionTestUtils.setField(category, "id", CATEGORY_ID);
            given(categoryRepository.findAllById(any())).willReturn(List.of(category));

            List<ThreadResponse> result = bulletinThreadService.enrichThreads(List.of(t1), USER_ID);

            assertThat(result.get(0).getCategoryName()).isEqualTo("お知らせ");
            assertThat(result.get(0).getCategoryColor()).isEqualTo("#00FF00");
        }

        @Test
        @DisplayName("AC-5: reactionSummary/myReactions が集計で入る")
        void リアクション集計が入る() {
            BulletinThreadEntity t1 = threadWith(THREAD_ID, USER_ID, CATEGORY_ID);
            stubBaseMapper();
            given(reactionRepository.countByTargetIdsGroupedByEmoji(eq(TargetType.THREAD), any()))
                    .willReturn(List.of(
                            new Object[]{THREAD_ID, "👍", 3L},
                            new Object[]{THREAD_ID, "❤️", 1L}));
            given(reactionRepository.findUserReactionsByTargetIds(eq(TargetType.THREAD), any(), eq(USER_ID)))
                    .willReturn(List.<Object[]>of(new Object[]{THREAD_ID, "👍"}));

            List<ThreadResponse> result = bulletinThreadService.enrichThreads(List.of(t1), USER_ID);

            assertThat(result.get(0).getReactionSummary()).containsEntry("👍", 3).containsEntry("❤️", 1);
            assertThat(result.get(0).getMyReactions()).containsExactly("👍");
        }

        @Test
        @DisplayName("AC-8: N+1 番人 — スレッド3件でも各依存呼び出しは1回")
        void N1番人_各依存は1回のみ() {
            BulletinThreadEntity t1 = threadWith(THREAD_ID, USER_ID, CATEGORY_ID);
            BulletinThreadEntity t2 = threadWith(THREAD_ID_2, AUTHOR_2, CATEGORY_ID_2);
            BulletinThreadEntity t3 = threadWith(THREAD_ID_3, AUTHOR_3, CATEGORY_ID);
            stubBaseMapper();

            bulletinThreadService.enrichThreads(List.of(t1, t2, t3), USER_ID);

            // 件数に比例しない（各 1 回）
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveUserDisplayNames(any());
            verify(nameResolverService, org.mockito.Mockito.times(1)).resolveUserAvatarUrls(any());
            verify(categoryRepository, org.mockito.Mockito.times(1)).findAllById(any());
            verify(readStatusRepository, org.mockito.Mockito.times(1)).findReadThreadIds(any(), eq(USER_ID));
            verify(reactionRepository, org.mockito.Mockito.times(1))
                    .countByTargetIdsGroupedByEmoji(eq(TargetType.THREAD), any());
            verify(reactionRepository, org.mockito.Mockito.times(1))
                    .findUserReactionsByTargetIds(eq(TargetType.THREAD), any(), eq(USER_ID));
        }

        @Test
        @DisplayName("AC-10: 空一覧は空リストを返し例外を投げない")
        void 空一覧は空リスト() {
            List<ThreadResponse> result = bulletinThreadService.enrichThreads(List.of(), USER_ID);

            assertThat(result).isEmpty();
            // 空ならどの依存も呼ばれない
            verify(nameResolverService, never()).resolveUserDisplayNames(any());
            verify(reactionRepository, never()).countByTargetIdsGroupedByEmoji(any(), any());
        }
    }
}
