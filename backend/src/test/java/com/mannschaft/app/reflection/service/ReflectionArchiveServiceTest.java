package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.ArchiveFolderResponse;
import com.mannschaft.app.reflection.dto.BulkArchiveRequest;
import com.mannschaft.app.reflection.dto.BulkArchiveResult;
import com.mannschaft.app.reflection.dto.ReflectionThemeResponse;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ReflectionArchiveService} 単体テスト（F06.5 Phase 3・§12・AC-39〜AC-43）。
 *
 * <p>カバー: archive/restore の状態遷移 / リマインダー CANCEL 呼び出し / bulk-archive 安全弁 /
 * folders GROUP BY / search エスケープ・本人スコープ。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionArchiveService 単体テスト")
class ReflectionArchiveServiceTest {

    @Mock private ReflectionThemeRepository themeRepository;
    @Mock private ReflectionEntryRepository entryRepository;
    @Mock private ReflectionSpacedReminderService reminderService;
    @Mock private ReflectionThemeService themeService;

    /**
     * 認可ゲートは実物を使う（判定対象のリポジトリは上のモックを流用する）。
     * 所有者判定の実体は {@code themeRepository.findByIdAndUserId} のままなので、
     * 各テストのスタブはそのまま認可判定に効く。
     */
    private ReflectionArchiveService service;

    @BeforeEach
    void wireService() {
        service = new ReflectionArchiveService(themeRepository, entryRepository, reminderService,
                themeService, new ReflectionAccessGuard(themeRepository, entryRepository));
    }

    private static final Long USER_ID = 100L;
    private static final UUID THEME_ID = UUID.randomUUID();

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReflectionThemeEntity activeTheme() {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(USER_ID).title("テーマ").build();
        setId(t, THEME_ID);
        return t;
    }

    private ReflectionThemeEntity archivedTheme() {
        ReflectionThemeEntity t = activeTheme();
        t.archive();
        return t;
    }

    // ─── AC-39: archive/restore ──────────────────────────────────────

    private ReflectionThemeResponse stubResponse(ReflectionThemeEntity entity) {
        ReflectionThemeResponse response = ReflectionThemeResponse.builder()
                .id(entity.getId().toString())
                .userId(entity.getUserId())
                .title(entity.getTitle())
                .archivedAt(entity.getArchivedAt())
                .build();
        given(themeService.toResponsePublic(entity)).willReturn(response);
        return response;
    }

    @Test
    @DisplayName("AC-39: archiveTheme で archived_at がセットされる")
    void archiveTheme_setsArchivedAt() {
        ReflectionThemeEntity theme = activeTheme();
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));
        given(entryRepository.findByThemeIdOrderByTargetDateDesc(THEME_ID)).willReturn(List.of());
        given(themeRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(themeService.toResponsePublic(any())).willReturn(
                ReflectionThemeResponse.builder().id(THEME_ID.toString()).userId(USER_ID)
                        .title("テーマ").build());

        service.archiveTheme(USER_ID, THEME_ID);

        assertThat(theme.getArchivedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC-39: 既にアーカイブ済みのテーマへの再 archive は 409（ALREADY_ARCHIVED）")
    void archiveTheme_alreadyArchived_throws() {
        ReflectionThemeEntity theme = archivedTheme();
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));

        assertThatThrownBy(() -> service.archiveTheme(USER_ID, THEME_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_ALREADY_ARCHIVED);

        verify(themeRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-39: 他人テーマへの archive は 404（REFLECTION_NOT_FOUND）")
    void archiveTheme_otherUser_throwsNotFound() {
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.archiveTheme(USER_ID, THEME_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-40: restoreTheme で archived_at が null に戻る")
    void restoreTheme_clearsArchivedAt() {
        ReflectionThemeEntity theme = archivedTheme();
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));
        given(themeRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(themeService.toResponsePublic(any())).willReturn(
                ReflectionThemeResponse.builder().id(THEME_ID.toString()).userId(USER_ID)
                        .title("テーマ").build());

        service.restoreTheme(USER_ID, THEME_ID);

        assertThat(theme.getArchivedAt()).isNull();
    }

    @Test
    @DisplayName("AC-40: アクティブなテーマへの restore は 409（NOT_ARCHIVED）")
    void restoreTheme_notArchived_throws() {
        ReflectionThemeEntity theme = activeTheme();
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));

        assertThatThrownBy(() -> service.restoreTheme(USER_ID, THEME_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_NOT_ARCHIVED);
    }

    // ─── AC-41: リマインダー CANCEL ──────────────────────────────────

    @Test
    @DisplayName("AC-41: archiveTheme 後、配下エントリの SPACED PENDING リマインダーが CANCEL される")
    void archiveTheme_cancelsSPACEDReminders() {
        ReflectionThemeEntity theme = activeTheme();
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));

        UUID entryId1 = UUID.randomUUID();
        UUID entryId2 = UUID.randomUUID();
        ReflectionEntryEntity e1 = ReflectionEntryEntity.builder()
                .themeId(THEME_ID).userId(USER_ID).targetDate(LocalDate.of(2026, 6, 1))
                .structuredContent("{}").build();
        setId(e1, entryId1);
        ReflectionEntryEntity e2 = ReflectionEntryEntity.builder()
                .themeId(THEME_ID).userId(USER_ID).targetDate(LocalDate.of(2026, 6, 2))
                .structuredContent("{}").build();
        setId(e2, entryId2);
        given(entryRepository.findByThemeIdOrderByTargetDateDesc(THEME_ID))
                .willReturn(List.of(e1, e2));
        given(themeRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(themeService.toResponsePublic(any())).willReturn(
                ReflectionThemeResponse.builder().id(THEME_ID.toString()).userId(USER_ID)
                        .title("テーマ").build());

        service.archiveTheme(USER_ID, THEME_ID);

        // SPACED: 各エントリに対して cancelPendingForEntry が呼ばれる
        verify(reminderService).cancelPendingForEntry(entryId1);
        verify(reminderService).cancelPendingForEntry(entryId2);
    }

    @Test
    @DisplayName("AC-41: archiveTheme 後、テーマの PRE_EXAM PENDING リマインダーが CANCEL される")
    void archiveTheme_cancelsPREEXAMReminders() {
        ReflectionThemeEntity theme = activeTheme();
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));
        given(entryRepository.findByThemeIdOrderByTargetDateDesc(THEME_ID)).willReturn(List.of());
        given(themeRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(themeService.toResponsePublic(any())).willReturn(
                ReflectionThemeResponse.builder().id(THEME_ID.toString()).userId(USER_ID)
                        .title("テーマ").build());

        service.archiveTheme(USER_ID, THEME_ID);

        // PRE_EXAM: cancelPendingPreExamForTheme が呼ばれる
        verify(reminderService).cancelPendingPreExamForTheme(THEME_ID);
    }

    @Test
    @DisplayName("AC-41: restoreTheme 後、リマインダーが自動生成されない（INSERT されない）")
    void restoreTheme_doesNotRegenerateReminders() {
        ReflectionThemeEntity theme = archivedTheme();
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));
        given(themeRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        given(themeService.toResponsePublic(any())).willReturn(
                ReflectionThemeResponse.builder().id(THEME_ID.toString()).userId(USER_ID)
                        .title("テーマ").build());

        service.restoreTheme(USER_ID, THEME_ID);

        // リマインダー生成メソッドは一切呼ばれない
        verify(reminderService, never()).generateSpacedReminders(any(), any());
        verify(reminderService, never()).generatePreExamReminders(any());
        verify(reminderService, never()).cancelPendingForEntry(any());
        verify(reminderService, never()).cancelPendingPreExamForTheme(any());
    }

    // ─── AC-42: folders ──────────────────────────────────────────────

    @Test
    @DisplayName("AC-42: getFolders が GROUP BY 結果を ArchiveFolderResponse のリストとして返す")
    void getFolders_returnsGroupedList() {
        List<ArchiveFolderResponse> expected = List.of(
                ArchiveFolderResponse.builder().academicYear(2026).termLabel("1学期")
                        .subjectName("数学").themeCount(3).build(),
                ArchiveFolderResponse.builder().academicYear(null).termLabel(null)
                        .subjectName(null).themeCount(1).build()
        );
        given(themeRepository.findArchivedFolders(USER_ID)).willReturn(expected);

        List<ArchiveFolderResponse> result = service.getFolders(USER_ID);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).academicYear()).isEqualTo(2026);
        assertThat(result.get(0).termLabel()).isEqualTo("1学期");
        assertThat(result.get(0).themeCount()).isEqualTo(3);
        // NULL グループも含む
        assertThat(result.get(1).academicYear()).isNull();
        assertThat(result.get(1).themeCount()).isEqualTo(1);
    }

    // ─── AC-43: search / LIKE エスケープ ─────────────────────────────

    @Test
    @DisplayName("AC-43: % を含む keyword が全件ヒットせず正しくエスケープされる")
    void escapeLikeKeyword_percentEscaped() {
        assertThat(ReflectionArchiveService.escapeLikeKeyword("abc%def"))
                .isEqualTo("abc\\%def");
        assertThat(ReflectionArchiveService.escapeLikeKeyword("100%"))
                .isEqualTo("100\\%");
        assertThat(ReflectionArchiveService.escapeLikeKeyword("a_b"))
                .isEqualTo("a\\_b");
        assertThat(ReflectionArchiveService.escapeLikeKeyword("a\\b"))
                .isEqualTo("a\\\\b");
    }

    @Test
    @DisplayName("AC-43: search が本人スコープで検索を実行する")
    void search_callsRepositoryWithUserId() {
        ReflectionThemeEntity theme = activeTheme();
        Page<ReflectionThemeEntity> page = new PageImpl<>(List.of(theme));
        given(themeRepository.searchArchived(eq(USER_ID), any(), any(), any(), any(), any(), any()))
                .willReturn(page);
        given(themeService.toResponsePublic(any())).willReturn(
                ReflectionThemeResponse.builder().id(THEME_ID.toString()).userId(USER_ID)
                        .title("テーマ").build());

        Page<ReflectionThemeResponse> result =
                service.search(USER_ID, true, 2026, "1学期", "数学", null, 0, 20);

        verify(themeRepository).searchArchived(eq(USER_ID), eq(true), eq(2026), eq("1学期"),
                eq("数学"), isNull(), any());
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-43: size 上限（50）超過で REFLECTION_CONTENT_INVALID (400)")
    void search_sizeLimitExceeded_throws() {
        assertThatThrownBy(() -> service.search(USER_ID, null, null, null, null, null, 0, 51))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
    }

    // ─── bulk-archive ────────────────────────────────────────────────

    @Test
    @DisplayName("bulk-archive: 3フィールドすべて null で 400（BULK_ARCHIVE_NO_CONDITION）")
    void bulkArchive_noCondition_throws() {
        assertThatThrownBy(() -> service.bulkArchive(USER_ID,
                new BulkArchiveRequest(null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_BULK_ARCHIVE_NO_CONDITION);
    }

    @Test
    @DisplayName("bulk-archive: 条件合致テーマを一括アーカイブし archivedCount を返す")
    void bulkArchive_archivesMatchingThemes() {
        ReflectionThemeEntity t1 = activeTheme();
        ReflectionThemeEntity t2 = activeTheme();
        UUID t2Id = UUID.randomUUID();
        setId(t2, t2Id);

        given(themeRepository.findActiveByCondition(USER_ID, 2025, "1学期", null))
                .willReturn(List.of(t1, t2));
        given(entryRepository.findByThemeIdOrderByTargetDateDesc(any())).willReturn(List.of());

        BulkArchiveResult result = service.bulkArchive(USER_ID,
                new BulkArchiveRequest(2025, "1学期", null));

        assertThat(result.archivedCount()).isEqualTo(2);
        assertThat(t1.getArchivedAt()).isNotNull();
        assertThat(t2.getArchivedAt()).isNotNull();
        verify(reminderService, times(2)).cancelPendingPreExamForTheme(any());
        verify(themeRepository).saveAll(any());
    }

    @Test
    @DisplayName("bulk-archive: 0件マッチでも 200 を返す（archivedCount=0）")
    void bulkArchive_zeroMatch_returnsZero() {
        given(themeRepository.findActiveByCondition(USER_ID, 2020, null, null))
                .willReturn(List.of());

        BulkArchiveResult result = service.bulkArchive(USER_ID,
                new BulkArchiveRequest(2020, null, null));

        assertThat(result.archivedCount()).isEqualTo(0);
        verify(themeRepository, never()).saveAll(any());
    }
}
