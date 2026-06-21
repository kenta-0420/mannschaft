package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.CreateReflectionThemeRequest;
import com.mannschaft.app.reflection.dto.ReflectionThemeResponse;
import com.mannschaft.app.reflection.dto.UpdateReflectionThemeRequest;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionSpacedReminderRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ReflectionThemeService} 単体テスト（F06.5・§7 #1〜#5）。
 *
 * <p>カバー: テーマ数上限（§2.5.1 b・AC への DoS 対策）/ 本人所有検証（AC-2 IDOR→404）/
 * exam_date 設定で PRE_EXAM 生成（AC-12）/ exam_date 変更で再生成 / 削除時の配下 entry 論理削除＋CANCEL。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionThemeService 単体テスト")
class ReflectionThemeServiceTest {

    @Mock private ReflectionThemeRepository themeRepository;
    @Mock private ReflectionEntryRepository entryRepository;
    @Mock private ReflectionSpacedReminderRepository reminderRepository;
    @Mock private ReflectionSpacedReminderService reminderService;

    @InjectMocks private ReflectionThemeService service;

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

    private ReflectionThemeEntity ownedTheme(LocalDate examDate) {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(USER_ID).title("数学II").examDate(examDate).build();
        setId(t, THEME_ID);
        return t;
    }

    @Test
    @DisplayName("createTheme: テーマ数上限（100）に達していると 400（THEME_LIMIT_EXCEEDED）")
    void createTheme_limitExceeded_throws() {
        given(themeRepository.countByUserId(USER_ID))
                .willReturn((long) ReflectionConstants.MAX_THEMES_PER_USER);

        assertThatThrownBy(() -> service.createTheme(USER_ID,
                new CreateReflectionThemeRequest("数学", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_THEME_LIMIT_EXCEEDED);

        verify(themeRepository, never()).save(any());
    }

    @Test
    @DisplayName("createTheme: exam_date 指定で PRE_EXAM リマインダを生成")
    void createTheme_withExamDate_generatesPreExam() {
        given(themeRepository.countByUserId(USER_ID)).willReturn(0L);
        given(themeRepository.save(any())).willAnswer(inv -> {
            ReflectionThemeEntity t = inv.getArgument(0);
            setId(t, THEME_ID);
            return t;
        });
        LocalDate exam = LocalDate.now().plusDays(30);

        service.createTheme(USER_ID,
                new CreateReflectionThemeRequest("数学", null, null, null, null, exam));

        verify(reminderService).generatePreExamReminders(any(ReflectionThemeEntity.class));
    }

    @Test
    @DisplayName("AC-2: 他人所有/不在テーマの取得は IDOR 対策で 404（NOT_FOUND）")
    void getTheme_notOwned_throwsNotFound() {
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTheme(USER_ID, THEME_ID))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-12: exam_date を変更すると既存 PENDING PRE_EXAM を CANCEL して再生成")
    void updateTheme_examDateChanged_regenerates() {
        ReflectionThemeEntity theme = ownedTheme(LocalDate.now().plusDays(10));
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));
        given(themeRepository.save(any())).willAnswer(inv -> inv.getArgument(0));
        LocalDate newExam = LocalDate.now().plusDays(40);

        service.updateTheme(USER_ID, THEME_ID,
                new UpdateReflectionThemeRequest(null, null, null, newExam, false));

        verify(reminderService).cancelPendingPreExamForTheme(THEME_ID);
        verify(reminderService).generatePreExamReminders(theme);
        assertThat(theme.getExamDate()).isEqualTo(newExam);
    }

    @Test
    @DisplayName("updateTheme: examDateCleared=true で exam_date を NULL クリアし PRE_EXAM を CANCEL（再生成なし）")
    void updateTheme_examDateCleared_cancelsOnly() {
        ReflectionThemeEntity theme = ownedTheme(LocalDate.now().plusDays(10));
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));
        given(themeRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.updateTheme(USER_ID, THEME_ID,
                new UpdateReflectionThemeRequest(null, null, null, null, true));

        assertThat(theme.getExamDate()).isNull();
        verify(reminderService).cancelPendingPreExamForTheme(THEME_ID);
        verify(reminderService, never()).generatePreExamReminders(any());
    }

    @Test
    @DisplayName("deleteTheme: 配下 entry を論理削除し、各エントリ由来 PENDING リマインダを CANCEL")
    void deleteTheme_cascadeSoftDeleteAndCancel() {
        ReflectionThemeEntity theme = ownedTheme(null);
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme));

        ReflectionEntryEntity e1 = ReflectionEntryEntity.builder()
                .themeId(THEME_ID).userId(USER_ID).targetDate(LocalDate.of(2026, 6, 1))
                .structuredContent("{}").build();
        setId(e1, UUID.randomUUID());
        given(entryRepository.findByThemeIdOrderByTargetDateDesc(THEME_ID)).willReturn(List.of(e1));

        service.deleteTheme(USER_ID, THEME_ID);

        assertThat(e1.getDeletedAt()).isNotNull();
        assertThat(theme.getDeletedAt()).isNotNull();
        verify(reminderService).cancelPendingForEntry(e1.getId());
        verify(reminderService).cancelPendingPreExamForTheme(THEME_ID);
        verify(themeRepository).save(theme);
    }
}
