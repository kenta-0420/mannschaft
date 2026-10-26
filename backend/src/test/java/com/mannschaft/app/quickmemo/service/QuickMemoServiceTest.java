package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.quickmemo.QuickMemoErrorCode;
import com.mannschaft.app.quickmemo.dto.CreateQuickMemoRequest;
import com.mannschaft.app.quickmemo.dto.QuickMemoResponse;
import com.mannschaft.app.quickmemo.dto.UpdateQuickMemoRequest;
import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;
import com.mannschaft.app.quickmemo.entity.TagEntity;
import com.mannschaft.app.quickmemo.repository.QuickMemoAttachmentRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import com.mannschaft.app.quickmemo.repository.QuickMemoTagLinkRepository;
import com.mannschaft.app.quickmemo.repository.TagRepository;
import com.mannschaft.app.quickmemo.repository.UserQuickMemoSettingsRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link QuickMemoService} の単体テスト。
 * F02.5 ポイっとメモ機能のサービス層を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuickMemoService 単体テスト")
class QuickMemoServiceTest {

    @Mock
    private QuickMemoRepository memoRepository;

    @Mock
    private QuickMemoTagLinkRepository tagLinkRepository;

    @Mock
    private QuickMemoAttachmentRepository attachmentRepository;

    @Mock
    private TagRepository tagRepository;

    @Mock
    private UserQuickMemoSettingsRepository settingsRepository;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private QuickMemoService quickMemoService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long MEMO_ID = 10L;
    private static final Long TAG_ID = 100L;

    /**
     * 基本的なメモエンティティを生成する。
     */
    private QuickMemoEntity buildMemo(String title, String status) {
        return QuickMemoEntity.builder()
                .userId(USER_ID)
                .title(title)
                .status(status)
                .build();
    }

    /**
     * 基本的なメモエンティティを生成する（IDあり・タイムスタンプあり）。
     */
    private QuickMemoEntity buildMemoWithId(Long id, String title, String status) {
        QuickMemoEntity memo = QuickMemoEntity.builder()
                .userId(USER_ID)
                .title(title)
                .status(status)
                .build();
        // リフレクションでIDとタイムスタンプを設定する代わりに save の戻り値でモックする
        return memo;
    }

    // ========================================
    // listMemos
    // ========================================

    @Nested
    @DisplayName("listMemos")
    class ListMemos {

        @Test
        @DisplayName("listMemos_UNSORTED指定_一覧が返る")
        void listMemos_UNSORTED指定_一覧が返る() {
            // Given
            QuickMemoEntity memo = buildMemo("テストメモ", "UNSORTED");
            Page<QuickMemoEntity> page = new PageImpl<>(List.of(memo));
            given(memoRepository.findByUserIdAndStatusAndDeletedAtIsNull(
                    eq(USER_ID), eq("UNSORTED"), any(PageRequest.class)))
                    .willReturn(page);
            // listMemos は includeAttachments=false のため attachmentRepository は呼ばれない
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());

            // When
            PagedResponse<QuickMemoResponse> result = quickMemoService.listMemos(USER_ID, "UNSORTED", 1, 20);

            // Then
            assertThat(result.getData()).hasSize(1);
            assertThat(result.getData().get(0).title()).isEqualTo("テストメモ");
            assertThat(result.getMeta().getTotal()).isEqualTo(1L);
        }

        @Test
        @DisplayName("listMemos_statusがnull_UNSORTEDとして扱われる")
        void listMemos_statusがnull_UNSORTEDとして扱われる() {
            // Given
            QuickMemoEntity memo = buildMemo("nullステータスメモ", "UNSORTED");
            Page<QuickMemoEntity> page = new PageImpl<>(List.of(memo));
            given(memoRepository.findByUserIdAndStatusAndDeletedAtIsNull(
                    eq(USER_ID), eq("UNSORTED"), any(PageRequest.class)))
                    .willReturn(page);
            // listMemos は includeAttachments=false のため attachmentRepository は呼ばれない
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());

            // When
            PagedResponse<QuickMemoResponse> result = quickMemoService.listMemos(USER_ID, null, 1, 20);

            // Then
            assertThat(result.getData()).hasSize(1);
            // statusがnullの場合、UNSORTEDとして検索されることを確認
            verify(memoRepository).findByUserIdAndStatusAndDeletedAtIsNull(
                    eq(USER_ID), eq("UNSORTED"), any());
        }
    }

    // ========================================
    // getMemoDetail
    // ========================================

    @Nested
    @DisplayName("getMemoDetail")
    class GetMemoDetail {

        @Test
        @DisplayName("getMemoDetail_存在するメモID_詳細が返る")
        void getMemoDetail_存在するメモID_詳細が返る() {
            // Given
            QuickMemoEntity memo = buildMemo("詳細メモ", "UNSORTED");
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID)).willReturn(Optional.of(memo));
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());
            given(attachmentRepository.findByMemoIdOrderBySortOrderAsc(any())).willReturn(List.of());

            // When
            QuickMemoResponse result = quickMemoService.getMemoDetail(MEMO_ID, USER_ID);

            // Then
            assertThat(result.title()).isEqualTo("詳細メモ");
            assertThat(result.status()).isEqualTo("UNSORTED");
            assertThat(result.tags()).isEmpty();
            assertThat(result.attachments()).isEmpty();
        }

        @Test
        @DisplayName("getMemoDetail_存在しないmemoId_BusinessException(QM_001)が投げられる")
        void getMemoDetail_存在しないmemoId_BusinessException_QM_001_が投げられる() {
            // Given
            given(memoRepository.findByIdAndUserId(MEMO_ID, USER_ID)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> quickMemoService.getMemoDetail(MEMO_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_001");
                    });
        }
    }

    // ========================================
    // searchMemos
    // ========================================

    @Nested
    @DisplayName("searchMemos")
    class SearchMemos {

        @Test
        @DisplayName("searchMemos_空文字列_空リストが返る")
        void searchMemos_空文字列_空リストが返る() {
            // When
            List<QuickMemoResponse> result = quickMemoService.searchMemos(USER_ID, "");

            // Then
            assertThat(result).isEmpty();
            verify(memoRepository, never()).searchByKeyword(anyLong(), anyString());
        }

        @Test
        @DisplayName("searchMemos_キーワードあり_マッチするメモリストが返る")
        void searchMemos_キーワードあり_マッチするメモリストが返る() {
            // Given
            QuickMemoEntity memo = buildMemo("テスト検索メモ", "UNSORTED");
            given(memoRepository.searchByKeyword(eq(USER_ID), anyString())).willReturn(List.of(memo));
            // searchMemos は includeAttachments=false のため attachmentRepository は呼ばれない
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());

            // When
            List<QuickMemoResponse> result = quickMemoService.searchMemos(USER_ID, "テスト");

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).title()).isEqualTo("テスト検索メモ");
        }
    }

    // ========================================
    // createMemo
    // ========================================

    @Nested
    @DisplayName("createMemo")
    class CreateMemo {

        @Test
        @DisplayName("createMemo_タイトルあり_メモが作成される")
        void createMemo_タイトルあり_メモが作成される() {
            // Given
            long unsortedCount = 0L;
            given(memoRepository.countByUserIdAndStatusAndDeletedAtIsNull(USER_ID, "UNSORTED"))
                    .willReturn(unsortedCount);
            QuickMemoEntity savedMemo = buildMemo("明示的タイトル", "UNSORTED");
            given(memoRepository.save(any(QuickMemoEntity.class))).willReturn(savedMemo);
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            CreateQuickMemoRequest req = new CreateQuickMemoRequest(
                    "明示的タイトル", null, null, null, null);

            // When
            QuickMemoResponse result = quickMemoService.createMemo(USER_ID, req, "ja");

            // Then
            assertThat(result.title()).isEqualTo("明示的タイトル");
            verify(memoRepository).save(any(QuickMemoEntity.class));
        }

        @Test
        @DisplayName("createMemo_タイトルが空_自動補完される（Accept-Language: ja）")
        void createMemo_タイトルが空_自動補完される_Accept_Language_ja() {
            // Given
            given(memoRepository.countByUserIdAndStatusAndDeletedAtIsNull(USER_ID, "UNSORTED"))
                    .willReturn(0L);
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(memoRepository.save(any(QuickMemoEntity.class))).willAnswer(invocation -> {
                QuickMemoEntity entity = invocation.getArgument(0);
                return entity;
            });
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());

            CreateQuickMemoRequest req = new CreateQuickMemoRequest(null, null, null, null, null);

            // When
            QuickMemoResponse result = quickMemoService.createMemo(USER_ID, req, "ja");

            // Then
            assertThat(result.title()).startsWith("無題メモ_");
        }

        @Test
        @DisplayName("createMemo_タイトルが空_英語Accept-Languageで英語補完される")
        void createMemo_タイトルが空_英語Accept_Languageで英語補完される() {
            // Given
            given(memoRepository.countByUserIdAndStatusAndDeletedAtIsNull(USER_ID, "UNSORTED"))
                    .willReturn(0L);
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            given(memoRepository.save(any(QuickMemoEntity.class))).willAnswer(invocation -> {
                QuickMemoEntity entity = invocation.getArgument(0);
                return entity;
            });
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());

            CreateQuickMemoRequest req = new CreateQuickMemoRequest(null, null, null, null, null);

            // When
            QuickMemoResponse result = quickMemoService.createMemo(USER_ID, req, "en-US,en;q=0.9");

            // Then
            assertThat(result.title()).startsWith("Untitled Memo_");
        }

        @Test
        @DisplayName("createMemo_UNSORTED上限500件超過_BusinessException(QM_002)が投げられる")
        void createMemo_UNSORTED上限500件超過_BusinessException_QM_002_が投げられる() {
            // Given
            given(memoRepository.countByUserIdAndStatusAndDeletedAtIsNull(USER_ID, "UNSORTED"))
                    .willReturn(500L);

            CreateQuickMemoRequest req = new CreateQuickMemoRequest(
                    "タイトル", null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> quickMemoService.createMemo(USER_ID, req, "ja"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_002");
                    });
            verify(memoRepository, never()).save(any());
        }

        @Test
        @DisplayName("createMemo_タグIDあり_タグが添付される")
        void createMemo_タグIDあり_タグが添付される() {
            // Given
            given(memoRepository.countByUserIdAndStatusAndDeletedAtIsNull(USER_ID, "UNSORTED"))
                    .willReturn(0L);
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            QuickMemoEntity savedMemo = buildMemo("タグ付きメモ", "UNSORTED");
            given(memoRepository.save(any(QuickMemoEntity.class))).willReturn(savedMemo);

            TagEntity tag = TagEntity.builder()
                    .scopeType("PERSONAL")
                    .scopeId(USER_ID)
                    .name("テストタグ")
                    .color("#FF0000")
                    .createdBy(USER_ID)
                    .build();
            given(tagRepository.findByIdAndScopeTypeAndScopeId(TAG_ID, "PERSONAL", USER_ID))
                    .willReturn(Optional.of(tag));
            given(tagLinkRepository.existsByMemoIdAndTagId(any(), eq(TAG_ID))).willReturn(false);
            given(tagLinkRepository.save(any())).willReturn(null);
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of(TAG_ID));
            given(tagRepository.findAllById(List.of(TAG_ID))).willReturn(List.of(tag));

            CreateQuickMemoRequest req = new CreateQuickMemoRequest(
                    "タグ付きメモ", null, List.of(TAG_ID), null, null);

            // When
            QuickMemoResponse result = quickMemoService.createMemo(USER_ID, req, "ja");

            // Then
            assertThat(result.title()).isEqualTo("タグ付きメモ");
            verify(tagLinkRepository).save(any());
            verify(tagRepository).incrementUsageCount(TAG_ID);
        }

        @Test
        @DisplayName("createMemo_tagIdsが11件_BusinessException(QM_015)が投げられる")
        void createMemo_tagIdsが11件_BusinessException_QM_015_が投げられる() {
            // Given
            given(memoRepository.countByUserIdAndStatusAndDeletedAtIsNull(USER_ID, "UNSORTED"))
                    .willReturn(0L);
            given(settingsRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
            QuickMemoEntity savedMemo = buildMemo("タグ超過メモ", "UNSORTED");
            given(memoRepository.save(any(QuickMemoEntity.class))).willReturn(savedMemo);

            // 11個のタグIDを作成
            List<Long> tagIds = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
            CreateQuickMemoRequest req = new CreateQuickMemoRequest(
                    "タグ超過メモ", null, tagIds, null, null);

            // When / Then
            assertThatThrownBy(() -> quickMemoService.createMemo(USER_ID, req, "ja"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_015");
                    });
        }
    }

    // ========================================
    // updateMemo
    // ========================================

    @Nested
    @DisplayName("updateMemo")
    class UpdateMemo {

        @Test
        @DisplayName("updateMemo_タイトル更新_更新されたメモが返る")
        void updateMemo_タイトル更新_更新されたメモが返る() {
            // Given
            QuickMemoEntity existingMemo = QuickMemoEntity.builder()
                    .userId(USER_ID)
                    .title("旧タイトル")
                    .status("UNSORTED")
                    .build();
            // createdAt が必要なため、toBuilder でタイムスタンプをセット
            QuickMemoEntity memoWithTimestamp = existingMemo.toBuilder()
                    .build();

            QuickMemoEntity updatedMemo = QuickMemoEntity.builder()
                    .userId(USER_ID)
                    .title("新タイトル")
                    .status("UNSORTED")
                    .build();

            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(existingMemo));
            given(memoRepository.save(any(QuickMemoEntity.class))).willReturn(updatedMemo);
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());
            given(attachmentRepository.findByMemoIdOrderBySortOrderAsc(any())).willReturn(List.of());

            UpdateQuickMemoRequest req = new UpdateQuickMemoRequest(
                    "新タイトル", null, null, null, null);

            // When
            QuickMemoResponse result = quickMemoService.updateMemo(MEMO_ID, USER_ID, req);

            // Then
            assertThat(result.title()).isEqualTo("新タイトル");
            verify(memoRepository).save(any(QuickMemoEntity.class));
        }

        @Test
        @DisplayName("updateMemo_存在しないmemoId_BusinessException(QM_001)が投げられる")
        void updateMemo_存在しないmemoId_BusinessException_QM_001_が投げられる() {
            // Given
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.empty());

            UpdateQuickMemoRequest req = new UpdateQuickMemoRequest(
                    "タイトル", null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> quickMemoService.updateMemo(MEMO_ID, USER_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_001");
                    });
        }
    }

    // ========================================
    // deleteMemo / undeleteMemo
    // ========================================

    @Nested
    @DisplayName("deleteMemo / undeleteMemo")
    class DeleteAndUndelete {

        @Test
        @DisplayName("deleteMemo_正常_論理削除される")
        void deleteMemo_正常_論理削除される() {
            // Given
            QuickMemoEntity memo = buildMemo("削除するメモ", "UNSORTED");
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(memo));
            given(memoRepository.save(any(QuickMemoEntity.class))).willReturn(memo);

            // When
            quickMemoService.deleteMemo(MEMO_ID, USER_ID);

            // Then
            // softDelete() が呼ばれ、deletedAt が設定されることを確認
            assertThat(memo.isDeleted()).isTrue();
            verify(memoRepository).save(memo);
        }

        @Test
        @DisplayName("deleteMemo_存在しないmemoId_BusinessException(QM_001)が投げられる")
        void deleteMemo_存在しないmemoId_BusinessException_QM_001_が投げられる() {
            // Given
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> quickMemoService.deleteMemo(MEMO_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_001");
                    });
        }

        @Test
        @DisplayName("undeleteMemo_削除済みメモ_復元される")
        void undeleteMemo_削除済みメモ_復元される() {
            // Given
            // softDelete()が呼ばれたエンティティを模倣する（deletedAtが設定済み）
            QuickMemoEntity deletedMemo = QuickMemoEntity.builder()
                    .userId(USER_ID)
                    .title("復元メモ")
                    .status("UNSORTED")
                    .build();
            deletedMemo.softDelete(); // deletedAt を設定

            QuickMemoEntity restoredMemo = deletedMemo.toBuilder().deletedAt(null).build();

            given(memoRepository.findById(MEMO_ID)).willReturn(Optional.of(deletedMemo));
            given(memoRepository.save(any(QuickMemoEntity.class))).willReturn(restoredMemo);
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());
            given(attachmentRepository.findByMemoIdOrderBySortOrderAsc(any())).willReturn(List.of());

            // When
            QuickMemoResponse result = quickMemoService.undeleteMemo(MEMO_ID, USER_ID);

            // Then
            assertThat(result.title()).isEqualTo("復元メモ");
            verify(memoRepository).save(any(QuickMemoEntity.class));
        }

        @Test
        @DisplayName("undeleteMemo_削除されていないメモ_BusinessException(QM_001)が投げられる")
        void undeleteMemo_削除されていないメモ_BusinessException_QM_001_が投げられる() {
            // Given
            // deletedAt が null（未削除）のメモ
            QuickMemoEntity activeMemo = buildMemo("未削除メモ", "UNSORTED");
            // isDeleted() == false なのでフィルタを通過しない

            given(memoRepository.findById(MEMO_ID)).willReturn(Optional.of(activeMemo));

            // When / Then
            assertThatThrownBy(() -> quickMemoService.undeleteMemo(MEMO_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException businessEx = (BusinessException) ex;
                        assertThat(businessEx.getErrorCode().getCode()).isEqualTo("QM_001");
                    });
        }
    }

    // ========================================
    // archiveMemo / restoreMemo
    // ========================================

    @Nested
    @DisplayName("archiveMemo / restoreMemo")
    class ArchiveAndRestore {

        @Test
        @DisplayName("archiveMemo_正常_ARCHIVEDになる")
        void archiveMemo_正常_ARCHIVEDになる() {
            // Given
            QuickMemoEntity memo = buildMemo("アーカイブメモ", "UNSORTED");
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(memo));
            given(memoRepository.save(any(QuickMemoEntity.class))).willAnswer(invocation -> {
                QuickMemoEntity entity = invocation.getArgument(0);
                return entity;
            });
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());
            given(attachmentRepository.findByMemoIdOrderBySortOrderAsc(any())).willReturn(List.of());

            // When
            QuickMemoResponse result = quickMemoService.archiveMemo(MEMO_ID, USER_ID);

            // Then
            // archive() が呼ばれてステータスが ARCHIVED に変わることを確認
            assertThat(memo.getStatus()).isEqualTo("ARCHIVED");
            assertThat(result.status()).isEqualTo("ARCHIVED");
        }

        @Test
        @DisplayName("restoreMemo_正常_UNSORTEDに戻る")
        void restoreMemo_正常_UNSORTEDに戻る() {
            // Given
            QuickMemoEntity memo = buildMemo("復活メモ", "ARCHIVED");
            given(memoRepository.findByIdAndUserIdForUpdate(MEMO_ID, USER_ID))
                    .willReturn(Optional.of(memo));
            given(memoRepository.save(any(QuickMemoEntity.class))).willAnswer(invocation -> {
                QuickMemoEntity entity = invocation.getArgument(0);
                return entity;
            });
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());
            given(attachmentRepository.findByMemoIdOrderBySortOrderAsc(any())).willReturn(List.of());

            // When
            QuickMemoResponse result = quickMemoService.restoreMemo(MEMO_ID, USER_ID);

            // Then
            // restore() が呼ばれてステータスが UNSORTED に変わることを確認
            assertThat(memo.getStatus()).isEqualTo("UNSORTED");
            assertThat(result.status()).isEqualTo("UNSORTED");
        }
    }

    // ========================================
    // listTrash
    // ========================================

    @Nested
    @DisplayName("listTrash")
    class ListTrash {

        @Test
        @DisplayName("listTrash_削除済みメモ一覧_ページング付きで返る")
        void listTrash_削除済みメモ一覧_ページング付きで返る() {
            // Given
            QuickMemoEntity deletedMemo1 = buildMemo("削除済みメモ1", "UNSORTED");
            QuickMemoEntity deletedMemo2 = buildMemo("削除済みメモ2", "ARCHIVED");
            deletedMemo1.softDelete();
            deletedMemo2.softDelete();

            Page<QuickMemoEntity> page = new PageImpl<>(List.of(deletedMemo1, deletedMemo2),
                    PageRequest.of(0, 20), 2L);
            given(memoRepository.findByUserIdAndDeletedAtIsNotNull(eq(USER_ID), any(PageRequest.class)))
                    .willReturn(page);
            // listTrash は includeAttachments=false のため attachmentRepository は呼ばれない
            given(tagLinkRepository.findTagIdsByMemoId(any())).willReturn(List.of());

            // When
            PagedResponse<QuickMemoResponse> result = quickMemoService.listTrash(USER_ID, 1, 20);

            // Then
            assertThat(result.getData()).hasSize(2);
            assertThat(result.getMeta().getTotal()).isEqualTo(2L);
            assertThat(result.getMeta().getPage()).isEqualTo(1);
            assertThat(result.getMeta().getSize()).isEqualTo(20);
        }
    }
}
