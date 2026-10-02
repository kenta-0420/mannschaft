package com.mannschaft.app.scopefolder.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.dto.FolderNotificationSummaryDto;
import com.mannschaft.app.scopefolder.dto.ScopeFolderResponse;
import com.mannschaft.app.scopefolder.entity.AssignedVia;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderItemEntity;
import com.mannschaft.app.scopefolder.entity.enums.ScopeType;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderItemRepository;
import com.mannschaft.app.scopefolder.repository.MyScopeFolderRepository;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * F15.3 §6.4 {@link MyScopeFolderQueryService} の通知集計クエリ検証。
 *
 * <p>未読件数集計が単一クエリで N+1 を起こさないこと、未読 0 件のフォルダも結果に含むこと、
 * IDOR で他人のフォルダ参照が遮断されることを確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MyScopeFolderQueryService 通知フィルタ単体テスト (F15.3)")
class MyScopeFolderNotificationFilterTest {

    @Mock
    private MyScopeFolderRepository folderRepository;

    @Mock
    private MyScopeFolderItemRepository itemRepository;

    @InjectMocks
    private MyScopeFolderQueryService queryService;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long FOLDER_A = 10L;
    private static final Long FOLDER_B = 11L;
    private static final Long FOLDER_C = 12L;

    private MyScopeFolderEntity folder(Long id, Long userId, int sortOrder, boolean isDefault, String name) {
        return MyScopeFolderEntity.builder()
                .id(id)
                .userId(userId)
                .scopeType(ScopeType.TEAM)
                .name(name)
                .color(null)
                .icon(null)
                .isDefault(isDefault)
                .sortOrder(sortOrder)
                .build();
    }

    private MyScopeFolderItemEntity item(Long id, Long folderId, Long scopeId) {
        return MyScopeFolderItemEntity.builder()
                .id(id)
                .folderId(folderId)
                .scopeId(scopeId)
                .sortOrder(0)
                .assignedVia(AssignedVia.MANUAL)
                .build();
    }

    // ============================================================
    // getNotificationSummary
    // ============================================================

    @Nested
    @DisplayName("getNotificationSummary")
    class GetNotificationSummary {

        @Test
        @DisplayName("正常系: 複数フォルダ×複数アイテムの集計値が正しく返る（未読 0 件も含む）")
        void summary_正常系_複数フォルダ() {
            // Given: aggregateFolderUnreadCounts は単一クエリで [folderId, unreadCount] のリストを返す
            given(itemRepository.aggregateFolderUnreadCounts(USER_ID, ScopeType.TEAM.name()))
                    .willReturn(List.<Object[]>of(
                            new Object[]{FOLDER_A, 5L},
                            new Object[]{FOLDER_B, 0L},  // 未読 0 件のフォルダも含む（タブの「すべて」集計用）
                            new Object[]{FOLDER_C, 2L}
                    ));

            // When
            List<FolderNotificationSummaryDto> result = queryService.getNotificationSummary(USER_ID, ScopeType.TEAM);

            // Then
            assertThat(result).hasSize(3);
            assertThat(result).extracting(FolderNotificationSummaryDto::folderId)
                    .containsExactly(FOLDER_A, FOLDER_B, FOLDER_C);
            assertThat(result).extracting(FolderNotificationSummaryDto::unreadCount)
                    .containsExactly(5L, 0L, 2L);
            // 集計クエリが 1 回だけ呼ばれていることを確認（N+1 防止）
            verify(itemRepository, times(1)).aggregateFolderUnreadCounts(USER_ID, ScopeType.TEAM.name());
            verifyNoMoreInteractions(itemRepository);
        }

        @Test
        @DisplayName("正常系: 数値型バリエーション（Integer/BigInteger/Long）を Long に正規化する")
        void summary_数値型を正規化() {
            // Given: ネイティブクエリは DB によって Integer / BigInteger / Long を返し得る
            given(itemRepository.aggregateFolderUnreadCounts(USER_ID, ScopeType.TEAM.name()))
                    .willReturn(List.<Object[]>of(
                            new Object[]{FOLDER_A.intValue(), java.math.BigInteger.valueOf(7L)}
                    ));

            // When
            List<FolderNotificationSummaryDto> result = queryService.getNotificationSummary(USER_ID, ScopeType.TEAM);

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).folderId()).isEqualTo(FOLDER_A);
            assertThat(result.get(0).unreadCount()).isEqualTo(7L);
        }

        @Test
        @DisplayName("正常系: フォルダが無いユーザーは空リストが返る")
        void summary_空リスト() {
            given(itemRepository.aggregateFolderUnreadCounts(USER_ID, ScopeType.TEAM.name()))
                    .willReturn(List.of());

            List<FolderNotificationSummaryDto> result = queryService.getNotificationSummary(USER_ID, ScopeType.TEAM);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("IDOR 担保: 集計クエリは user_id を必ず WHERE に含めて呼ばれる（他人の通知は混入しない）")
        void summary_IDOR_userIdが渡る() {
            given(itemRepository.aggregateFolderUnreadCounts(USER_ID, ScopeType.TEAM.name()))
                    .willReturn(List.of());

            queryService.getNotificationSummary(USER_ID, ScopeType.TEAM);

            // 他ユーザーの ID で集計クエリは絶対に呼ばれない
            verify(itemRepository).aggregateFolderUnreadCounts(USER_ID, ScopeType.TEAM.name());
            verify(itemRepository, times(0))
                    .aggregateFolderUnreadCounts(OTHER_USER_ID, ScopeType.TEAM.name());
        }
    }

    // ============================================================
    // getFoldersWithUnread
    // ============================================================

    @Nested
    @DisplayName("getFoldersWithUnread")
    class GetFoldersWithUnread {

        @Test
        @DisplayName("正常系: フォルダ×アイテム×未読集計を 3 クエリ以内で返す（N+1 防止）")
        void getFoldersWithUnread_3クエリで返る() {
            // Given
            MyScopeFolderEntity fa = folder(FOLDER_A, USER_ID, 0, false, "プロジェクトX");
            MyScopeFolderEntity fb = folder(FOLDER_B, USER_ID, 1, false, "プロジェクトY");
            MyScopeFolderEntity defaultF = folder(FOLDER_C, USER_ID, 9999, true, "未分類");
            given(folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of(fa, fb, defaultF));
            given(itemRepository.findByFolderIdIn(List.of(FOLDER_A, FOLDER_B, FOLDER_C)))
                    .willReturn(List.of(
                            item(1L, FOLDER_A, 100L),
                            item(2L, FOLDER_A, 101L),
                            item(3L, FOLDER_B, 102L)
                    ));
            given(itemRepository.aggregateFolderUnreadCounts(USER_ID, ScopeType.TEAM.name()))
                    .willReturn(List.<Object[]>of(
                            new Object[]{FOLDER_A, 5L},
                            new Object[]{FOLDER_B, 0L},
                            new Object[]{FOLDER_C, 1L}
                    ));

            // When
            List<ScopeFolderResponse> result = queryService.getFoldersWithUnread(USER_ID, ScopeType.TEAM);

            // Then
            assertThat(result).hasSize(3);
            assertThat(result).extracting(ScopeFolderResponse::id)
                    .containsExactly(FOLDER_A, FOLDER_B, FOLDER_C);
            assertThat(result).extracting(ScopeFolderResponse::notificationUnreadCount)
                    .containsExactly(5L, 0L, 1L);
            assertThat(result.get(2).isDefault()).isTrue();
            assertThat(result.get(0).itemScopeIds()).containsExactly(100L, 101L);
            assertThat(result.get(1).itemScopeIds()).containsExactly(102L);
            assertThat(result.get(2).itemScopeIds()).isEmpty();

            // クエリ数の確認: folders / items / aggregate の 3 本のみ（フォルダ件数に依存しない）
            verify(folderRepository, times(1))
                    .findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(USER_ID, ScopeType.TEAM);
            verify(itemRepository, times(1)).findByFolderIdIn(List.of(FOLDER_A, FOLDER_B, FOLDER_C));
            verify(itemRepository, times(1)).aggregateFolderUnreadCounts(USER_ID, ScopeType.TEAM.name());
            verifyNoMoreInteractions(folderRepository);
        }

        @Test
        @DisplayName("正常系: フォルダ無しユーザーはアイテム・集計クエリを発行しない")
        void getFoldersWithUnread_フォルダ無しでクエリ短絡() {
            given(folderRepository.findByUserIdAndScopeTypeAndDeletedAtIsNullOrderBySortOrder(USER_ID, ScopeType.TEAM))
                    .willReturn(List.of());

            List<ScopeFolderResponse> result = queryService.getFoldersWithUnread(USER_ID, ScopeType.TEAM);

            assertThat(result).isEmpty();
            verifyNoMoreInteractions(itemRepository);
        }
    }

    // ============================================================
    // getScopeIdsInFolder (IDOR)
    // ============================================================

    @Nested
    @DisplayName("getScopeIdsInFolder (IDOR 検証)")
    class GetScopeIdsInFolder {

        @Test
        @DisplayName("IDOR 防止: 他人所有のフォルダは SCOPE_FOLDER_NOT_FOUND になる")
        void getScopeIds_他人のフォルダで404() {
            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_A, USER_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> queryService.getScopeIdsInFolder(USER_ID, FOLDER_A))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND.getCode()));
        }

        @Test
        @DisplayName("正常系: 自分のフォルダなら scopeId 一覧が返る")
        void getScopeIds_正常系() {
            MyScopeFolderEntity f = folder(FOLDER_A, USER_ID, 0, false, "プロジェクトX");
            given(folderRepository.findByIdAndUserIdAndDeletedAtIsNull(FOLDER_A, USER_ID))
                    .willReturn(Optional.of(f));
            given(itemRepository.findByFolderIdOrderBySortOrder(FOLDER_A))
                    .willReturn(List.of(item(1L, FOLDER_A, 100L), item(2L, FOLDER_A, 101L)));

            List<Long> result = queryService.getScopeIdsInFolder(USER_ID, FOLDER_A);

            assertThat(result).containsExactly(100L, 101L);
        }
    }
}
