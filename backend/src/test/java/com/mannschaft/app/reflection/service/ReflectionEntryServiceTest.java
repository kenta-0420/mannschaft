package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.CreateBlogPostRequest;
import com.mannschaft.app.cms.service.BlogPostService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.ExportToBlogRequest;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.dto.UpsertReflectionEntryRequest;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReflectionEntryService} 単体テスト（F06.5・§7 #7）。
 *
 * <p>カバー AC: AC-4（upsert）/ AC-18（楽観排他 409・マスク中直接 PUT 409）/ §2.5.1(c)（target_date 範囲 400）/
 * §2.5.1(a)（PENDING 上限 400）/ AC-2（本人所有 404）/ AC-9（新規保存で SPACED 生成）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionEntryService 単体テスト")
class ReflectionEntryServiceTest {

    @Mock private ReflectionEntryRepository entryRepository;
    @Mock private ReflectionThemeRepository themeRepository;
    @Mock private ReflectionSpacedReminderService reminderService;
    @Mock private ReflectionContentSanitizer contentSanitizer;
    @Mock private ReflectionEntryResponseMapper responseMapper;
    @Mock private ReflectionMaskEvaluator maskEvaluator;
    @Mock private UserTimezoneCache userTimezoneCache;
    @Mock private BlogPostService blogPostService;

    /**
     * 認可ゲートは実物を使う（判定対象のリポジトリは上のモックを流用する）。
     * 所有者判定の実体は {@code themeRepository/entryRepository.findByIdAndUserId} のままなので、
     * 各テストのスタブはそのまま認可判定に効く。
     */
    private ReflectionEntryService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long USER_ID = 100L;
    private static final UUID THEME_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now();

    private static void setId(Object entity, UUID id) {
        try {
            Field f = entity.getClass().getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ReflectionThemeEntity ownedTheme() {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(USER_ID).title("数学").recallIntervalDays("1,3,7,14").build();
        setId(t, THEME_ID);
        return t;
    }

    private ReflectionEntryEntity entry(LocalDate targetDate, Long version) {
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(THEME_ID).userId(USER_ID).targetDate(targetDate)
                .structuredContent("{}").version(version).build();
        setId(e, UUID.randomUUID());
        return e;
    }

    private UpsertReflectionEntryRequest request(LocalDate targetDate, Long expectedVersion) {
        return new UpsertReflectionEntryRequest(THEME_ID, targetDate,
                objectMapper.createObjectNode().put("main_theme", "二次関数"), expectedVersion);
    }

    @BeforeEach
    void stubCommon() {
        service = new ReflectionEntryService(entryRepository, reminderService, contentSanitizer,
                responseMapper, maskEvaluator, userTimezoneCache, blogPostService,
                new ReflectionAccessGuard(themeRepository, entryRepository));
        lenient().when(userTimezoneCache.getTimezone(USER_ID)).thenReturn("Asia/Tokyo");
        lenient().when(contentSanitizer.sanitizeAndSerialize(any()))
                .thenReturn("{\"main_theme\":\"二次関数\"}");
        lenient().when(maskEvaluator.parseIntervals(any())).thenReturn(List.of(1, 3, 7, 14));
        lenient().when(responseMapper.toResponse(any(), any(), any()))
                .thenReturn(ReflectionEntryResponse.builder().isMasked(false).build());
    }

    @Test
    @DisplayName("AC-2: テーマが本人所有でなければ 404（NOT_FOUND）")
    void upsert_themeNotOwned_throwsNotFound() {
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsertEntry(USER_ID, request(TODAY, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("§2.5.1(c): target_date が未来 30 日超なら 400（TARGET_DATE_OUT_OF_RANGE）")
    void upsert_targetDateTooFuture_throws() {
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(ownedTheme()));

        assertThatThrownBy(() -> service.upsertEntry(USER_ID, request(TODAY.plusDays(31), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_TARGET_DATE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("§2.5.1(c): target_date が過去 365 日超なら 400")
    void upsert_targetDateTooPast_throws() {
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(ownedTheme()));

        assertThatThrownBy(() -> service.upsertEntry(USER_ID, request(TODAY.minusDays(366), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_TARGET_DATE_OUT_OF_RANGE);
    }

    @Test
    @DisplayName("AC-4/AC-9: 新規 upsert は保存＋SPACED 生成")
    void upsert_new_savesAndGeneratesReminders() {
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(ownedTheme()));
        given(entryRepository.findIncludingDeletedByThemeIdAndTargetDate(THEME_ID, TODAY))
                .willReturn(Optional.empty());
        given(reminderService.countPendingReminders(USER_ID)).willReturn(0L);
        given(entryRepository.save(any())).willAnswer(inv -> {
            ReflectionEntryEntity e = inv.getArgument(0);
            setId(e, UUID.randomUUID());
            return e;
        });

        service.upsertEntry(USER_ID, request(TODAY, null));

        verify(entryRepository).save(any(ReflectionEntryEntity.class));
        verify(reminderService).generateSpacedReminders(any(), any());
    }

    @Test
    @DisplayName("§2.5.1(a): 新規保存で PENDING 総数が上限超になるなら 400（REMINDER_LIMIT_EXCEEDED）")
    void upsert_pendingLimitExceeded_throws() {
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(ownedTheme()));
        given(entryRepository.findIncludingDeletedByThemeIdAndTargetDate(THEME_ID, TODAY))
                .willReturn(Optional.empty());
        // 既存 PENDING 999 + 追加 4 → 1003 > 1000
        given(reminderService.countPendingReminders(USER_ID)).willReturn(999L);

        assertThatThrownBy(() -> service.upsertEntry(USER_ID, request(TODAY, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_REMINDER_LIMIT_EXCEEDED);

        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-18: 既存更新で expectedVersion 不一致なら 409（VERSION_CONFLICT）")
    void upsert_versionMismatch_throwsConflict() {
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(ownedTheme()));
        ReflectionEntryEntity existing = entry(TODAY, 3L);
        given(entryRepository.findIncludingDeletedByThemeIdAndTargetDate(THEME_ID, TODAY))
                .willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upsertEntry(USER_ID, request(TODAY, 2L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_VERSION_CONFLICT);
    }

    @Test
    @DisplayName("AC-18: 既存更新で expectedVersion 未指定なら 409（VERSION_CONFLICT）")
    void upsert_versionNull_throwsConflict() {
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(ownedTheme()));
        ReflectionEntryEntity existing = entry(TODAY, 0L);
        given(entryRepository.findIncludingDeletedByThemeIdAndTargetDate(THEME_ID, TODAY))
                .willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upsertEntry(USER_ID, request(TODAY, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_VERSION_CONFLICT);
    }

    @Test
    @DisplayName("AC-18/§3.1: マスク中エントリへの直接 PUT は 409（ENTRY_MASKED）")
    void upsert_maskedEntry_throwsConflict() {
        ReflectionThemeEntity theme = ownedTheme();
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));
        // 過去日エントリ（当日でない）→ version 一致だがマスク中
        LocalDate past = TODAY.minusDays(10);
        ReflectionEntryEntity existing = entry(past, 0L);
        given(entryRepository.findIncludingDeletedByThemeIdAndTargetDate(THEME_ID, past))
                .willReturn(Optional.of(existing));
        given(maskEvaluator.isMasked(existing, theme, TODAY)).willReturn(true);

        assertThatThrownBy(() -> service.upsertEntry(USER_ID, request(past, 0L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_ENTRY_MASKED);

        verify(entryRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC-18: 非マスク（当日）エントリは version 一致で更新成功")
    void upsert_sameDayActive_updates() {
        ReflectionThemeEntity theme = ownedTheme();
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));
        ReflectionEntryEntity existing = entry(TODAY, 0L);
        given(entryRepository.findIncludingDeletedByThemeIdAndTargetDate(THEME_ID, TODAY))
                .willReturn(Optional.of(existing));
        given(maskEvaluator.isMasked(existing, theme, TODAY)).willReturn(false);
        given(entryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.upsertEntry(USER_ID, request(TODAY, 0L));

        verify(entryRepository).save(existing);
        // target_date 不変ゆえ SPACED 再生成しない。
        verify(reminderService, never()).generateSpacedReminders(any(), any());
    }

    @Test
    @DisplayName("復活更新: 論理削除済みエントリは旧 PENDING を CANCEL→復活→SPACED 再生成（§2.2）")
    void upsert_restoreDeleted_regenerates() {
        ReflectionThemeEntity theme = ownedTheme();
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));
        ReflectionEntryEntity deleted = entry(TODAY, 0L);
        deleted.softDelete();
        given(entryRepository.findIncludingDeletedByThemeIdAndTargetDate(THEME_ID, TODAY))
                .willReturn(Optional.of(deleted));
        given(reminderService.countPendingReminders(USER_ID)).willReturn(0L);
        given(entryRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.upsertEntry(USER_ID, request(TODAY, null));

        verify(reminderService).cancelPendingForEntry(deleted.getId());
        verify(reminderService).generateSpacedReminders(any(), any());
        org.assertj.core.api.Assertions.assertThat(deleted.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("AC-9: deleteEntry は論理削除＋関連 PENDING リマインダ CANCEL")
    void deleteEntry_softDeleteAndCancel() {
        ReflectionEntryEntity e = entry(TODAY, 0L);
        given(entryRepository.findByIdAndUserId(e.getId(), USER_ID)).willReturn(Optional.of(e));

        service.deleteEntry(USER_ID, e.getId());

        org.assertj.core.api.Assertions.assertThat(e.getDeletedAt()).isNotNull();
        verify(reminderService).cancelPendingForEntry(e.getId());
    }

    // ─── ブログ輸出（§7 #13・§6.3・AC-20） ─────────────────────────

    @Test
    @DisplayName("AC-20: exportToBlog は visibility=PRIVATE 明示で createPost を呼び、exported_blog_post_id を非NULL化（元エントリ残存）")
    void exportToBlog_callsCreatePostWithPrivateAndMarksExported() {
        ReflectionEntryEntity e = entry(TODAY.minusDays(1), 0L);
        ReflectionThemeEntity theme = ownedTheme();
        given(entryRepository.findByIdAndUserId(e.getId(), USER_ID)).willReturn(Optional.of(e));
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));
        given(contentSanitizer.parse(any())).willReturn(
                objectMapper.createObjectNode().put("main_theme", "二次関数"));
        BlogPostResponse created = BlogPostResponse.builder().id(555L).build();
        given(blogPostService.createPost(eq(USER_ID), any(CreateBlogPostRequest.class))).willReturn(created);

        BlogPostResponse res = service.exportToBlog(USER_ID, e.getId(), new ExportToBlogRequest(null));

        ArgumentCaptor<CreateBlogPostRequest> captor = ArgumentCaptor.forClass(CreateBlogPostRequest.class);
        verify(blogPostService).createPost(eq(USER_ID), captor.capture());
        // visibility=PRIVATE 明示（未指定だと MEMBERS_ONLY になるため・§6.3）
        assertThat(captor.getValue().getVisibility()).isEqualTo("PRIVATE");
        // exported_blog_post_id 非NULL化（直接ミューテート markExported）
        assertThat(e.getExportedBlogPostId()).isEqualTo(555L);
        // 元エントリは残存（論理削除しない・AC-20）
        assertThat(e.getDeletedAt()).isNull();
        assertThat(res.getId()).isEqualTo(555L);
    }

    @Test
    @DisplayName("AC-20: 既に輸出済み（exported_blog_post_id 非NULL）のエントリの再輸出は 409（ALREADY_EXPORTED）")
    void exportToBlog_alreadyExported_throwsConflict() {
        ReflectionEntryEntity e = entry(TODAY.minusDays(1), 0L);
        e.markExported(999L);
        given(entryRepository.findByIdAndUserId(e.getId(), USER_ID)).willReturn(Optional.of(e));

        assertThatThrownBy(() -> service.exportToBlog(USER_ID, e.getId(), new ExportToBlogRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_ALREADY_EXPORTED);

        verify(blogPostService, never()).createPost(anyLong(), any());
    }

    @Test
    @DisplayName("AC-2: 他人所有エントリの輸出は 404（NOT_FOUND）")
    void exportToBlog_notOwned_throwsNotFound() {
        UUID entryId = UUID.randomUUID();
        given(entryRepository.findByIdAndUserId(entryId, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.exportToBlog(USER_ID, entryId, new ExportToBlogRequest(null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_NOT_FOUND);
    }
}
