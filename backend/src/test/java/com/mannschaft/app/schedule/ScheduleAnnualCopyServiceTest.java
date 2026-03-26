package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.entity.ScheduleAnnualCopyLogEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleEventCategoryEntity;
import com.mannschaft.app.schedule.repository.ScheduleAnnualCopyLogRepository;
import com.mannschaft.app.schedule.repository.ScheduleEventCategoryRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleAnnualCopyService;
import com.mannschaft.app.schedule.service.ScheduleAnnualCopyService.CopyItem;
import com.mannschaft.app.schedule.service.ScheduleAnnualCopyService.CopyResult;
import com.mannschaft.app.schedule.service.ScheduleAnnualCopyService.ShiftedDates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleAnnualCopyService} の単体テスト。
 * 年間行事コピーの日付シフト・コピー実行・ログ取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleAnnualCopyService 単体テスト")
class ScheduleAnnualCopyServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private ScheduleAnnualCopyLogRepository copyLogRepository;

    @Mock
    private ScheduleEventCategoryRepository categoryRepository;

    @InjectMocks
    private ScheduleAnnualCopyService copyService;

    // ========================================
    // テスト用定数
    // ========================================

    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 100L;

    // ========================================
    // calculateSameWeekdayShift
    // ========================================

    @Nested
    @DisplayName("calculateSameWeekdayShift")
    class CalculateSameWeekdayShift {

        @Test
        @DisplayName("同一曜日シフト_第2月曜日_正しい日付を返す")
        void 同一曜日シフト_第2月曜日_正しい日付を返す() {
            // given: 2025/4/14 は第2月曜日
            LocalDateTime source = LocalDateTime.of(2025, 4, 14, 10, 0);

            // when
            ShiftedDates result = copyService.calculateSameWeekdayShift(source, null, 2026);

            // then: 2026年4月の第2月曜日 = 2026/4/13
            assertThat(result.startAt().getMonthValue()).isEqualTo(4);
            assertThat(result.note()).contains("第2月曜日");
        }

        @Test
        @DisplayName("同一曜日シフト_endAtあり_差分が保持される")
        void 同一曜日シフト_endAtあり_差分が保持される() {
            // given
            LocalDateTime sourceStart = LocalDateTime.of(2025, 4, 14, 10, 0);
            LocalDateTime sourceEnd = LocalDateTime.of(2025, 4, 14, 12, 0);

            // when
            ShiftedDates result = copyService.calculateSameWeekdayShift(sourceStart, sourceEnd, 2026);

            // then
            assertThat(result.endAt()).isNotNull();
            assertThat(java.time.Duration.between(result.startAt(), result.endAt()).toHours()).isEqualTo(2);
        }

        @Test
        @DisplayName("同一曜日シフト_年度跨ぎ1月_翌年になる")
        void 同一曜日シフト_年度跨ぎ1月_翌年になる() {
            // given: 2026/1/12 は第2月曜日（年度2025 → ターゲット年度2026）
            LocalDateTime source = LocalDateTime.of(2026, 1, 12, 10, 0);

            // when
            ShiftedDates result = copyService.calculateSameWeekdayShift(source, null, 2026);

            // then: 月は1月のまま、年は2027
            assertThat(result.startAt().getYear()).isEqualTo(2027);
            assertThat(result.startAt().getMonthValue()).isEqualTo(1);
        }
    }

    // ========================================
    // calculateExactDaysShift
    // ========================================

    @Nested
    @DisplayName("calculateExactDaysShift")
    class CalculateExactDaysShift {

        @Test
        @DisplayName("正確日数シフト_1年分_365日シフトされる")
        void 正確日数シフト_1年分_365日シフトされる() {
            // given
            LocalDateTime source = LocalDateTime.of(2025, 4, 1, 10, 0);

            // when
            ShiftedDates result = copyService.calculateExactDaysShift(source, null, 2025, 2026);

            // then
            assertThat(result.startAt().getYear()).isEqualTo(2026);
            assertThat(result.note()).contains("日シフト");
        }

        @Test
        @DisplayName("正確日数シフト_endAtあり_差分が保持される")
        void 正確日数シフト_endAtあり_差分が保持される() {
            // given
            LocalDateTime sourceStart = LocalDateTime.of(2025, 5, 1, 10, 0);
            LocalDateTime sourceEnd = LocalDateTime.of(2025, 5, 1, 15, 0);

            // when
            ShiftedDates result = copyService.calculateExactDaysShift(sourceStart, sourceEnd, 2025, 2026);

            // then
            assertThat(result.endAt()).isNotNull();
            assertThat(java.time.Duration.between(result.startAt(), result.endAt()).toHours()).isEqualTo(5);
        }
    }

    // ========================================
    // validateAcademicYearRange
    // ========================================

    @Nested
    @DisplayName("validateAcademicYearRange")
    class ValidateAcademicYearRange {

        @Test
        @DisplayName("年度範囲検証_範囲内_例外なし")
        void 年度範囲検証_範囲内_例外なし() {
            // given
            LocalDateTime startAt = LocalDateTime.of(2025, 6, 15, 10, 0);

            // when & then（例外なしで正常終了）
            copyService.validateAcademicYearRange(startAt, 2025);
        }

        @Test
        @DisplayName("年度範囲検証_範囲外_例外スロー")
        void 年度範囲検証_範囲外_例外スロー() {
            // given: 2025年度は 2025/4/1 〜 2026/3/31
            LocalDateTime startAt = LocalDateTime.of(2025, 3, 15, 10, 0);

            // when & then
            assertThatThrownBy(() -> copyService.validateAcademicYearRange(startAt, 2025))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleEventCategoryErrorCode.ACADEMIC_YEAR_DATE_MISMATCH);
        }

        @Test
        @DisplayName("年度範囲検証_3月31日_範囲内")
        void 年度範囲検証_3月31日_範囲内() {
            // given: 2026/3/31 は2025年度の最終日
            LocalDateTime startAt = LocalDateTime.of(2026, 3, 31, 10, 0);

            // when & then（例外なしで正常終了）
            copyService.validateAcademicYearRange(startAt, 2025);
        }

        @Test
        @DisplayName("年度範囲検証_4月1日_範囲内")
        void 年度範囲検証_4月1日_範囲内() {
            // given: 2025/4/1 は2025年度の開始日
            LocalDateTime startAt = LocalDateTime.of(2025, 4, 1, 0, 0);

            // when & then（例外なしで正常終了）
            copyService.validateAcademicYearRange(startAt, 2025);
        }
    }

    // ========================================
    // executeCopy
    // ========================================

    @Nested
    @DisplayName("executeCopy")
    class ExecuteCopy {

        @Test
        @DisplayName("コピー実行_正常_スケジュールが複製される")
        void コピー実行_正常_スケジュールが複製される() {
            // given
            ScheduleEntity source = ScheduleEntity.builder()
                    .teamId(SCOPE_ID)
                    .title("運動会")
                    .startAt(LocalDateTime.of(2025, 10, 1, 9, 0))
                    .endAt(LocalDateTime.of(2025, 10, 1, 17, 0))
                    .allDay(false)
                    .eventType(EventType.EVENT)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .build();

            given(scheduleRepository.findById(1L)).willReturn(Optional.of(source));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(copyLogRepository.save(any(ScheduleAnnualCopyLogEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            List<CopyItem> items = List.of(
                    new CopyItem(1L, LocalDateTime.of(2026, 10, 1, 9, 0),
                            LocalDateTime.of(2026, 10, 1, 17, 0), true));

            // when
            CopyResult result = copyService.executeCopy(SCOPE_ID, true, 2025, 2026,
                    DateShiftMode.EXACT_DAYS, items, USER_ID);

            // then
            assertThat(result.totalCopied()).isEqualTo(1);
            assertThat(result.totalSkipped()).isZero();
            verify(scheduleRepository).save(any(ScheduleEntity.class));
            verify(copyLogRepository).save(any(ScheduleAnnualCopyLogEntity.class));
        }

        @Test
        @DisplayName("コピー実行_includeがfalse_スキップされる")
        void コピー実行_includeがfalse_スキップされる() {
            // given
            given(copyLogRepository.save(any(ScheduleAnnualCopyLogEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            List<CopyItem> items = List.of(
                    new CopyItem(1L, LocalDateTime.of(2026, 10, 1, 9, 0), null, false));

            // when
            CopyResult result = copyService.executeCopy(SCOPE_ID, true, 2025, 2026,
                    DateShiftMode.EXACT_DAYS, items, USER_ID);

            // then
            assertThat(result.totalCopied()).isZero();
        }

        @Test
        @DisplayName("コピー実行_同一年度_例外スロー")
        void コピー実行_同一年度_例外スロー() {
            // when & then
            assertThatThrownBy(() -> copyService.executeCopy(SCOPE_ID, true, 2025, 2025,
                    DateShiftMode.EXACT_DAYS, List.of(), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleEventCategoryErrorCode.ANNUAL_COPY_SAME_YEAR);
        }

        @Test
        @DisplayName("コピー実行_ソース不在_スキップされる")
        void コピー実行_ソース不在_スキップされる() {
            // given
            given(scheduleRepository.findById(999L)).willReturn(Optional.empty());
            given(copyLogRepository.save(any(ScheduleAnnualCopyLogEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            List<CopyItem> items = List.of(
                    new CopyItem(999L, LocalDateTime.of(2026, 10, 1, 9, 0), null, true));

            // when
            CopyResult result = copyService.executeCopy(SCOPE_ID, true, 2025, 2026,
                    DateShiftMode.EXACT_DAYS, items, USER_ID);

            // then
            assertThat(result.totalCopied()).isZero();
            assertThat(result.totalSkipped()).isEqualTo(1);
        }
    }

    // ========================================
    // getCopyLogs
    // ========================================

    @Nested
    @DisplayName("getCopyLogs")
    class GetCopyLogs {

        @Test
        @DisplayName("コピーログ取得_チームスコープ_チームのログを返す")
        void コピーログ取得_チームスコープ_チームのログを返す() {
            // given
            ScheduleAnnualCopyLogEntity logEntity = ScheduleAnnualCopyLogEntity.builder()
                    .teamId(SCOPE_ID)
                    .sourceAcademicYear(2025)
                    .targetAcademicYear(2026)
                    .totalCopied(5)
                    .dateShiftMode(DateShiftMode.EXACT_DAYS)
                    .executedBy(USER_ID)
                    .build();
            given(copyLogRepository.findByTeamIdOrderByCreatedAtDesc(SCOPE_ID))
                    .willReturn(List.of(logEntity));

            // when
            List<ScheduleAnnualCopyLogEntity> result = copyService.getCopyLogs(SCOPE_ID, true);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTotalCopied()).isEqualTo(5);
        }

        @Test
        @DisplayName("コピーログ取得_組織スコープ_組織のログを返す")
        void コピーログ取得_組織スコープ_組織のログを返す() {
            // given
            given(copyLogRepository.findByOrganizationIdOrderByCreatedAtDesc(SCOPE_ID))
                    .willReturn(List.of());

            // when
            List<ScheduleAnnualCopyLogEntity> result = copyService.getCopyLogs(SCOPE_ID, false);

            // then
            assertThat(result).isEmpty();
        }
    }
}
