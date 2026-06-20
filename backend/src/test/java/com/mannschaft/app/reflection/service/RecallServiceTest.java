package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.timezone.UserTimezoneCache;
import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.CreateRecallAttemptRequest;
import com.mannschaft.app.reflection.dto.ReflectionEntryResponse;
import com.mannschaft.app.reflection.entity.RecallAttemptEntity;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.RecallAttemptRepository;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RecallService} 単体テスト（F06.5・§7 #10〜#11・§3.1）。
 *
 * <p>カバー AC: AC-7（保存＝開示で revealed_at 記録＋original 返却）/ AC-22（FORGOT で翌日 SPACED 再生成）/
 * AC-2（他人エントリ 404）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecallService 単体テスト")
class RecallServiceTest {

    @Mock private RecallAttemptRepository recallAttemptRepository;
    @Mock private ReflectionEntryRepository entryRepository;
    @Mock private ReflectionThemeRepository themeRepository;
    @Mock private ReflectionSpacedReminderService reminderService;
    @Mock private ReflectionContentSanitizer contentSanitizer;
    @Mock private ReflectionEntryResponseMapper responseMapper;
    @Mock private UserTimezoneCache userTimezoneCache;

    @InjectMocks private RecallService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    private ReflectionEntryEntity entry() {
        ReflectionEntryEntity e = ReflectionEntryEntity.builder()
                .themeId(THEME_ID).userId(USER_ID).targetDate(LocalDate.of(2026, 6, 1))
                .structuredContent("{}").build();
        setId(e, UUID.randomUUID());
        return e;
    }

    private ReflectionThemeEntity theme() {
        ReflectionThemeEntity t = ReflectionThemeEntity.builder()
                .userId(USER_ID).title("数学").recallIntervalDays("1,3,7,14").build();
        setId(t, THEME_ID);
        return t;
    }

    private CreateRecallAttemptRequest request(RecallSelfRating rating) {
        return new CreateRecallAttemptRequest(
                objectMapper.createObjectNode().put("note", "思い出した"), rating);
    }

    @BeforeEach
    void stubCommon() {
        lenient().when(userTimezoneCache.getTimezone(USER_ID)).thenReturn("Asia/Tokyo");
        lenient().when(contentSanitizer.sanitizeRecalledContent(any())).thenReturn("{\"note\":\"x\"}");
        lenient().when(responseMapper.toRevealedResponse(any(), any()))
                .thenReturn(ReflectionEntryResponse.builder().isMasked(false).build());
    }

    @Test
    @DisplayName("AC-7: recall 保存で revealed_at 記録＋original 開示応答（toRevealedResponse）")
    void recordRecall_savesRevealsOriginal() {
        ReflectionEntryEntity e = entry();
        given(entryRepository.findByIdAndUserId(e.getId(), USER_ID)).willReturn(Optional.of(e));
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme()));

        ReflectionEntryResponse resp = service.recordRecall(USER_ID, e.getId(),
                request(RecallSelfRating.REMEMBERED));

        ArgumentCaptor<RecallAttemptEntity> captor = ArgumentCaptor.forClass(RecallAttemptEntity.class);
        verify(recallAttemptRepository).save(captor.capture());
        assertThat(captor.getValue().getRevealedAt()).isNotNull();
        assertThat(captor.getValue().getSelfRating()).isEqualTo(RecallSelfRating.REMEMBERED);
        assertThat(resp.isMasked()).isFalse();
        verify(responseMapper).toRevealedResponse(any(), any());
        // REMEMBERED は翌日再スケジュールしない。
        verify(reminderService, never()).scheduleNextDaySpacedReminder(any(), any());
    }

    @Test
    @DisplayName("AC-22: FORGOT で翌日（recall_date+1）SPACED を再生成")
    void recordRecall_forgot_reschedulesNextDay() {
        ReflectionEntryEntity e = entry();
        given(entryRepository.findByIdAndUserId(e.getId(), USER_ID)).willReturn(Optional.of(e));
        given(themeRepository.findByIdAndUserId(THEME_ID, USER_ID)).willReturn(Optional.of(theme()));

        service.recordRecall(USER_ID, e.getId(), request(RecallSelfRating.FORGOT));

        verify(reminderService).scheduleNextDaySpacedReminder(any(), any());
    }

    @Test
    @DisplayName("AC-2: 他人/不在エントリの recall は 404（NOT_FOUND）")
    void recordRecall_notOwned_throwsNotFound() {
        UUID entryId = UUID.randomUUID();
        given(entryRepository.findByIdAndUserId(entryId, USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.recordRecall(USER_ID, entryId,
                request(RecallSelfRating.REMEMBERED)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(ReflectionErrorCode.REFLECTION_NOT_FOUND);

        verify(recallAttemptRepository, never()).save(any());
    }
}
