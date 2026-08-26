package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinErrorCode;
import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ReadTrackingMode;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.CategoryResponse;
import com.mannschaft.app.bulletin.dto.CreateCategoryRequest;
import com.mannschaft.app.bulletin.dto.DeleteCategoryResponse;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.dto.ReplyResponse;
import com.mannschaft.app.bulletin.dto.UpdateCategoryRequest;
import com.mannschaft.app.bulletin.entity.BulletinCategoryEntity;
import com.mannschaft.app.bulletin.entity.BulletinReadStatusEntity;
import com.mannschaft.app.bulletin.entity.BulletinReplyEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinCategoryRepository;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinReplyRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.service.PostingIdentityService;
import com.mannschaft.app.village.service.VillageBulletinAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 村掲示板グローバル方式 サービス層（カテゴリ CRUD / 返信 / 既読の村スコープ経路）の単体テスト。
 *
 * <p>村モデレーター認可・名称重複・未分類化・ネスト深さ制限・他村403/404・委譲を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("村掲示板グローバル方式 サービス層 単体テスト")
class GlobalBulletinServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long CATEGORY_ID = 5L;
    private static final Long THREAD_ID = 100L;
    private static final Long REPLY_ID = 200L;
    private static final Long SCOPE_ID = 10L;
    private static final UUID VILLAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private BulletinThreadEntity villageThread(boolean locked) {
        return BulletinThreadEntity.builder()
                .scopeType(ScopeType.VILLAGE)
                .scopeId(0L)
                .scopeVillageId(VILLAGE_ID)
                .authorId(USER_ID)
                .title("題名")
                .body("本文")
                .readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                .isLocked(locked)
                .isArchived(false)
                .build();
    }

    private BulletinThreadEntity teamThread() {
        return BulletinThreadEntity.builder()
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .authorId(USER_ID)
                .title("題名")
                .body("本文")
                .readTrackingMode(ReadTrackingMode.COUNT_ONLY)
                .isLocked(false)
                .isArchived(false)
                .build();
    }

    // ========================================================================
    // BulletinCategoryService — グローバル CRUD
    // ========================================================================

    @Nested
    @DisplayName("BulletinCategoryService グローバルCRUD")
    class CategoryGlobal {

        @Mock
        private BulletinCategoryRepository categoryRepository;
        @Mock
        private BulletinThreadRepository threadRepository;
        @Mock
        private BulletinMapper bulletinMapper;
        @Mock
        private BulletinAccessGuard accessGuard;
        @Mock
        private VillageBulletinAccessService villageBulletinAccessService;

        @InjectMocks
        private BulletinCategoryService service;

        private BulletinCategoryEntity villageCategory() {
            return BulletinCategoryEntity.builder()
                    .scopeType(ScopeType.VILLAGE)
                    .scopeId(0L)
                    .scopeVillageId(VILLAGE_ID)
                    .name("一般")
                    .build();
        }

        @Test
        @DisplayName("村カテゴリ作成_モデレーター認可OK_村スコープで保存して201相当")
        void village作成_成功() {
            given(categoryRepository.existsByScopeVillageIdAndName(VILLAGE_ID, "一般")).willReturn(false);
            given(categoryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toCategoryResponse(any())).willReturn(
                    new CategoryResponse(CATEGORY_ID, "VILLAGE", 0L, "一般", null, 0, null, "MEMBER", USER_ID, null, null));

            CategoryResponse res = service.createCategoryGlobal(ScopeType.VILLAGE, 0L, VILLAGE_ID, USER_ID,
                    new CreateCategoryRequest("一般", null, null, null, null));

            assertThat(res.getName()).isEqualTo("一般");
            verify(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);
            ArgumentCaptor<BulletinCategoryEntity> captor = ArgumentCaptor.forClass(BulletinCategoryEntity.class);
            verify(categoryRepository).save(captor.capture());
            assertThat(captor.getValue().getScopeVillageId()).isEqualTo(VILLAGE_ID);
            assertThat(captor.getValue().getScopeType()).isEqualTo(ScopeType.VILLAGE);
        }

        @Test
        @DisplayName("村カテゴリ作成_モデレーターでない_403を伝播")
        void village作成_非モデレーター_403() {
            doThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN))
                    .when(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);

            assertThatThrownBy(() -> service.createCategoryGlobal(ScopeType.VILLAGE, 0L, VILLAGE_ID, USER_ID,
                    new CreateCategoryRequest("一般", null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN));
            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("村カテゴリ作成_名称重複_DUPLICATE_CATEGORY_NAME")
        void village作成_名称重複_409() {
            given(categoryRepository.existsByScopeVillageIdAndName(VILLAGE_ID, "一般")).willReturn(true);

            assertThatThrownBy(() -> service.createCategoryGlobal(ScopeType.VILLAGE, 0L, VILLAGE_ID, USER_ID,
                    new CreateCategoryRequest("一般", null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.DUPLICATE_CATEGORY_NAME));
            verify(categoryRepository, never()).save(any());
        }

        @Test
        @DisplayName("カテゴリ作成_VILLAGEでscope_village_id欠落_VILLAGE_NOT_FOUND")
        void village作成_村id欠落_404() {
            assertThatThrownBy(() -> service.createCategoryGlobal(ScopeType.VILLAGE, 0L, null, USER_ID,
                    new CreateCategoryRequest("一般", null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(VillageErrorCode.VILLAGE_NOT_FOUND));
        }

        @Test
        @DisplayName("カテゴリ作成_TEAM_既存createCategoryへ委譲（村認可は呼ばない）")
        void team作成_委譲() {
            given(categoryRepository.existsByScopeTypeAndScopeIdAndName(ScopeType.TEAM, SCOPE_ID, "一般"))
                    .willReturn(false);
            given(categoryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toCategoryResponse(any())).willReturn(
                    new CategoryResponse(CATEGORY_ID, "TEAM", SCOPE_ID, "一般", null, 0, null, "MEMBER", USER_ID, null, null));

            service.createCategoryGlobal(ScopeType.TEAM, SCOPE_ID, null, USER_ID,
                    new CreateCategoryRequest("一般", null, null, null, null));

            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, SCOPE_ID);
            verify(villageBulletinAccessService, never()).checkVillageBulletinModerator(any(), any());
        }

        @Test
        @DisplayName("村カテゴリ削除_配下スレッドを未分類化してから論理削除")
        void village削除_未分類化() {
            BulletinCategoryEntity cat = villageCategory();
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.of(cat));
            given(threadRepository.bulkSetCategoryIdNullByCategoryId(CATEGORY_ID)).willReturn(2);

            DeleteCategoryResponse res = service.deleteCategoryGlobal(CATEGORY_ID, USER_ID);

            assertThat(res.getAffectedThreadCount()).isEqualTo(2);
            verify(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);
            verify(threadRepository).bulkSetCategoryIdNullByCategoryId(CATEGORY_ID);
            verify(categoryRepository).save(cat);
            assertThat(cat.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("カテゴリ更新_存在しない_CATEGORY_NOT_FOUND")
        void 更新_不存在_404() {
            given(categoryRepository.findById(CATEGORY_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateCategoryGlobal(CATEGORY_ID, USER_ID,
                    new UpdateCategoryRequest("一般", null, null, null, null)))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.CATEGORY_NOT_FOUND));
        }
    }

    // ========================================================================
    // BulletinReplyService — グローバル CRUD
    // ========================================================================

    @Nested
    @DisplayName("BulletinReplyService グローバルCRUD")
    class ReplyGlobal {

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
        private com.mannschaft.app.auth.service.AuditLogService auditLogService;
        @Mock
        private VillageBulletinAccessService villageBulletinAccessService;
        @Mock
        private PostingIdentityService postingIdentityService;

        @InjectMocks
        private BulletinReplyService service;

        @Test
        @DisplayName("村返信作成_村メンバー検証OK_保存して返信カウント増加")
        void village返信作成_成功() {
            BulletinThreadEntity thread = villageThread(false);
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            given(replyRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toReplyResponse(any())).willReturn(
                    new ReplyResponse(REPLY_ID, THREAD_ID, null, USER_ID, "本文", false, 0,
                            LocalDateTime.now(), LocalDateTime.now(), 0, List.of()));

            ReplyResponse res = service.createReplyGlobal(THREAD_ID, null, USER_ID, "本文");

            assertThat(res.getBody()).isEqualTo("本文");
            verify(postingIdentityService).validatePostingIdentity(
                    USER_ID, VILLAGE_ID, VillageSubjectType.USER, USER_ID);
            verify(replyRepository).save(any());
            verify(threadRepository).save(thread);
        }

        @Test
        @DisplayName("村返信作成_ロック中_THREAD_LOCKED")
        void village返信作成_ロック_423() {
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(villageThread(true)));

            assertThatThrownBy(() -> service.createReplyGlobal(THREAD_ID, null, USER_ID, "本文"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.THREAD_LOCKED));
            verify(replyRepository, never()).save(any());
        }

        @Test
        @DisplayName("村ネスト返信作成_depth=5（6階層目）_REPLY_DEPTH_EXCEEDED")
        void villageネスト_深さ超過_400() {
            BulletinThreadEntity thread = villageThread(false);
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            BulletinReplyEntity parent = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).depth(4).authorId(USER_ID).body("親").build();
            given(replyRepository.findByIdAndThreadId(REPLY_ID, THREAD_ID)).willReturn(Optional.of(parent));

            assertThatThrownBy(() -> service.createReplyGlobal(THREAD_ID, REPLY_ID, USER_ID, "本文"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.REPLY_DEPTH_EXCEEDED));
            verify(replyRepository, never()).save(any());
        }

        @Test
        @DisplayName("村ネスト返信作成_replyId経由でthreadId逆引きして委譲")
        void villageネスト作成_逆引き() {
            BulletinThreadEntity thread = villageThread(false);
            BulletinReplyEntity parent = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).depth(0).authorId(USER_ID).body("親").build();
            given(replyRepository.findById(REPLY_ID)).willReturn(Optional.of(parent));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            given(replyRepository.findByIdAndThreadId(REPLY_ID, THREAD_ID)).willReturn(Optional.of(parent));
            given(replyRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
            given(bulletinMapper.toReplyResponse(any())).willReturn(
                    new ReplyResponse(REPLY_ID + 1, THREAD_ID, REPLY_ID, USER_ID, "子", false, 0,
                            LocalDateTime.now(), LocalDateTime.now(), 1, List.of()));

            ReplyResponse res = service.createNestedReplyGlobal(REPLY_ID, USER_ID, "子");

            assertThat(res.getParentId()).isEqualTo(REPLY_ID);
            verify(postingIdentityService).validatePostingIdentity(
                    USER_ID, VILLAGE_ID, VillageSubjectType.USER, USER_ID);
        }

        @Test
        @DisplayName("村返信更新_他人の返信_NOT_AUTHOR")
        void village返信更新_他人_403() {
            BulletinReplyEntity reply = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).depth(0).authorId(OTHER_USER_ID).body("本文").build();
            given(replyRepository.findById(REPLY_ID)).willReturn(Optional.of(reply));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(villageThread(false)));

            assertThatThrownBy(() -> service.updateReplyGlobal(REPLY_ID, USER_ID, "修正"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(BulletinErrorCode.NOT_AUTHOR));
            verify(replyRepository, never()).save(any());
        }

        @Test
        @DisplayName("村返信削除_本人_モデレーター認可は不要")
        void village返信削除_本人() {
            BulletinReplyEntity reply = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).depth(0).authorId(USER_ID).body("本文").build();
            BulletinThreadEntity thread = villageThread(false);
            given(replyRepository.findById(REPLY_ID)).willReturn(Optional.of(reply));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));

            service.deleteReplyGlobal(REPLY_ID, USER_ID);

            assertThat(reply.getDeletedAt()).isNotNull();
            verify(villageBulletinAccessService, never()).checkVillageBulletinModerator(any(), any());
            verify(threadRepository).save(thread);
        }

        @Test
        @DisplayName("村返信削除_他人_村モデレーター認可を要求")
        void village返信削除_他人_モデレーター() {
            BulletinReplyEntity reply = BulletinReplyEntity.builder()
                    .threadId(THREAD_ID).depth(0).authorId(OTHER_USER_ID).body("本文").build();
            BulletinThreadEntity thread = villageThread(false);
            given(replyRepository.findById(REPLY_ID)).willReturn(Optional.of(reply));
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));

            service.deleteReplyGlobal(REPLY_ID, USER_ID);

            verify(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);
            verify(auditLogService).record(any(), eq(USER_ID), eq(OTHER_USER_ID), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("返信一覧_TEAM_既存listRepliesへ委譲（村認可は呼ばない）")
        void team一覧_委譲() {
            BulletinThreadEntity thread = teamThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            given(threadService.findThreadOrThrow(ScopeType.TEAM, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(replyRepository.findByThreadIdAndParentIdIsNullOrderByCreatedAtAsc(eq(THREAD_ID), any()))
                    .willReturn(org.springframework.data.domain.Page.empty());

            service.listRepliesGlobal(THREAD_ID, USER_ID, org.springframework.data.domain.PageRequest.of(0, 20));

            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, SCOPE_ID);
            verify(villageBulletinAccessService, never()).checkVillageBulletinViewAccess(any(), any());
        }
    }

    // ========================================================================
    // BulletinReadStatusService — グローバル既読
    // ========================================================================

    @Nested
    @DisplayName("BulletinReadStatusService グローバル既読")
    class ReadGlobal {

        @Mock
        private BulletinReadStatusRepository readStatusRepository;
        @Mock
        private BulletinThreadRepository threadRepository;
        @Mock
        private BulletinThreadService threadService;
        @Mock
        private BulletinMapper bulletinMapper;
        @Mock
        private BulletinAccessGuard accessGuard;
        @Mock
        private VillageBulletinAccessService villageBulletinAccessService;

        @InjectMocks
        private BulletinReadStatusService service;

        @Test
        @DisplayName("村既読マーク_閲覧認可OK_未読なら既読化して既読数増加")
        void village既読_新規() {
            BulletinThreadEntity thread = villageThread(false);
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            given(readStatusRepository.existsByThreadIdAndUserId(thread.getId(), USER_ID)).willReturn(false);

            service.markAsReadGlobal(THREAD_ID, USER_ID);

            verify(villageBulletinAccessService).checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID);
            verify(readStatusRepository).save(any());
            verify(threadRepository).save(thread);
        }

        @Test
        @DisplayName("村既読マーク_既読済み_冪等（保存しない）")
        void village既読_冪等() {
            BulletinThreadEntity thread = villageThread(false);
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            given(readStatusRepository.existsByThreadIdAndUserId(thread.getId(), USER_ID)).willReturn(true);

            service.markAsReadGlobal(THREAD_ID, USER_ID);

            verify(readStatusRepository, never()).save(any());
        }

        @Test
        @DisplayName("村一括既読_未読のみ既読化_既読済みは二重計上しない")
        void village一括既読_差分() {
            given(threadRepository.findIdsByScopeVillageId(VILLAGE_ID))
                    .willReturn(List.of(THREAD_ID, THREAD_ID + 1, THREAD_ID + 2));
            // THREAD_ID は既読済み → スキップ。残り2件を既読化。
            given(readStatusRepository.findReadThreadIds(any(), eq(USER_ID))).willReturn(List.of(THREAD_ID));
            given(threadRepository.findById(any())).willReturn(Optional.of(villageThread(false)));

            int marked = service.markAllAsReadGlobal(ScopeType.VILLAGE, 0L, VILLAGE_ID, USER_ID);

            assertThat(marked).isEqualTo(2);
            verify(villageBulletinAccessService).checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID);
        }

        @Test
        @DisplayName("村一括既読_scope_village_id欠落_COMMON_001")
        void village一括既読_村id欠落_400() {
            assertThatThrownBy(() -> service.markAllAsReadGlobal(ScopeType.VILLAGE, 0L, null, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_001));
        }

        @Test
        @DisplayName("既読マーク_TEAM_既存所属認可へ委譲（村認可は呼ばない）")
        void team既読_委譲() {
            BulletinThreadEntity thread = teamThread();
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            given(readStatusRepository.existsByThreadIdAndUserId(thread.getId(), USER_ID)).willReturn(false);

            service.markAsReadGlobal(THREAD_ID, USER_ID);

            verify(accessGuard).checkMembership(USER_ID, ScopeType.TEAM, SCOPE_ID);
            verify(villageBulletinAccessService, never()).checkVillageBulletinViewAccess(any(), any());
        }

        @Test
        @DisplayName("村既読者一覧_COUNT_ONLYかつ非モデレーター_空配列（プライバシー保護）")
        void village既読者一覧_count_only_空() {
            BulletinThreadEntity thread = villageThread(false);
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            // 非モデレーター（checkVillageBulletinModerator が例外）
            doThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN))
                    .when(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);

            List<ReadStatusResponse> res = service.listReadUsersGlobal(THREAD_ID, USER_ID, null);

            assertThat(res).isEmpty();
            verify(villageBulletinAccessService).checkVillageBulletinViewAccess(VILLAGE_ID, USER_ID);
        }

        @Test
        @DisplayName("村既読者一覧_filter=unreadは村モデレーターのみ_非モデレーターはCOMMON_002")
        void village既読者一覧_unread_非モデレーター_403() {
            BulletinThreadEntity thread = villageThread(false);
            given(threadRepository.findById(THREAD_ID)).willReturn(Optional.of(thread));
            doThrow(new BusinessException(VillageErrorCode.VILLAGE_BULLETIN_MODERATE_FORBIDDEN))
                    .when(villageBulletinAccessService).checkVillageBulletinModerator(VILLAGE_ID, USER_ID);

            assertThatThrownBy(() -> service.listReadUsersGlobal(THREAD_ID, USER_ID, "unread"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(CommonErrorCode.COMMON_002));
        }
    }
}
