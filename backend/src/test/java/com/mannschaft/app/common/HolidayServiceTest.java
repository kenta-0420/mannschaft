package com.mannschaft.app.common;

import com.mannschaft.app.common.entity.HolidayMasterEntity;
import com.mannschaft.app.common.repository.HolidayMasterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link HolidayService} の単体テスト。
 * 祝日判定・祝日一覧取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HolidayService 単体テスト")
class HolidayServiceTest {

    @Mock
    private HolidayMasterRepository holidayMasterRepository;

    @InjectMocks
    private HolidayService holidayService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final LocalDate NEW_YEAR = LocalDate.of(2026, 1, 1);
    private static final LocalDate NORMAL_DAY = LocalDate.of(2026, 3, 25);
    private static final Long SCOPE_ID = 10L;

    private HolidayMasterEntity createHoliday(LocalDate date, String name) {
        return HolidayMasterEntity.builder()
                .id(1L)
                .scopeType("SYSTEM")
                .scopeId(0L)
                .holidayDate(date)
                .name(name)
                .country("JP")
                .isRecurring(false)
                .build();
    }

    // ========================================
    // isSystemHoliday
    // ========================================

    @Nested
    @DisplayName("isSystemHoliday")
    class IsSystemHoliday {

        @Test
        @DisplayName("正常系: システム祝日の場合trueを返す")
        void isSystemHoliday_祝日_true() {
            // Given
            given(holidayMasterRepository.existsByScopeTypeAndScopeIdAndHolidayDate(
                    "SYSTEM", 0L, NEW_YEAR)).willReturn(true);

            // When
            boolean result = holidayService.isSystemHoliday(NEW_YEAR);

            // Then
            assertThat(result).isTrue();
            verify(holidayMasterRepository).existsByScopeTypeAndScopeIdAndHolidayDate("SYSTEM", 0L, NEW_YEAR);
        }

        @Test
        @DisplayName("正常系: 平日の場合falseを返す")
        void isSystemHoliday_平日_false() {
            // Given
            given(holidayMasterRepository.existsByScopeTypeAndScopeIdAndHolidayDate(
                    "SYSTEM", 0L, NORMAL_DAY)).willReturn(false);

            // When
            boolean result = holidayService.isSystemHoliday(NORMAL_DAY);

            // Then
            assertThat(result).isFalse();
        }
    }

    // ========================================
    // isHoliday
    // ========================================

    @Nested
    @DisplayName("isHoliday")
    class IsHoliday {

        @Test
        @DisplayName("正常系: スコープ固有の祝日の場合trueを返す")
        void isHoliday_スコープ祝日_true() {
            // Given
            given(holidayMasterRepository.isHoliday(NEW_YEAR, "TEAM", SCOPE_ID)).willReturn(true);

            // When
            boolean result = holidayService.isHoliday(NEW_YEAR, "TEAM", SCOPE_ID);

            // Then
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("正常系: 祝日でない場合falseを返す")
        void isHoliday_祝日でない_false() {
            // Given
            given(holidayMasterRepository.isHoliday(NORMAL_DAY, "TEAM", SCOPE_ID)).willReturn(false);

            // When
            boolean result = holidayService.isHoliday(NORMAL_DAY, "TEAM", SCOPE_ID);

            // Then
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("正常系: ORGANIZATIONスコープで祝日判定")
        void isHoliday_ORGANIZATIONスコープ_正常判定() {
            // Given
            given(holidayMasterRepository.isHoliday(NEW_YEAR, "ORGANIZATION", SCOPE_ID)).willReturn(true);

            // When
            boolean result = holidayService.isHoliday(NEW_YEAR, "ORGANIZATION", SCOPE_ID);

            // Then
            assertThat(result).isTrue();
        }
    }

    // ========================================
    // getHolidaysInRange
    // ========================================

    @Nested
    @DisplayName("getHolidaysInRange")
    class GetHolidaysInRange {

        @Test
        @DisplayName("正常系: 期間内の祝日一覧が返る")
        void getHolidaysInRange_期間内祝日_一覧が返る() {
            // Given
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to = LocalDate.of(2026, 1, 31);
            HolidayMasterEntity holiday1 = createHoliday(LocalDate.of(2026, 1, 1), "元日");
            HolidayMasterEntity holiday2 = createHoliday(LocalDate.of(2026, 1, 13), "成人の日");
            given(holidayMasterRepository.findHolidaysInRange(from, to, "TEAM", SCOPE_ID))
                    .willReturn(List.of(holiday1, holiday2));

            // When
            List<HolidayMasterEntity> result = holidayService.getHolidaysInRange(from, to, "TEAM", SCOPE_ID);

            // Then
            assertThat(result).hasSize(2);
            verify(holidayMasterRepository).findHolidaysInRange(from, to, "TEAM", SCOPE_ID);
        }

        @Test
        @DisplayName("境界値: 期間内に祝日がない場合は空リスト")
        void getHolidaysInRange_祝日なし_空リスト() {
            // Given
            LocalDate from = LocalDate.of(2026, 6, 1);
            LocalDate to = LocalDate.of(2026, 6, 30);
            given(holidayMasterRepository.findHolidaysInRange(from, to, "TEAM", SCOPE_ID))
                    .willReturn(List.of());

            // When
            List<HolidayMasterEntity> result = holidayService.getHolidaysInRange(from, to, "TEAM", SCOPE_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getSystemHolidaysByYear
    // ========================================

    @Nested
    @DisplayName("getSystemHolidaysByYear")
    class GetSystemHolidaysByYear {

        @Test
        @DisplayName("正常系: 年間のシステム祝日一覧が返る")
        void getSystemHolidaysByYear_正常_祝日一覧が返る() {
            // Given
            int year = 2026;
            HolidayMasterEntity holiday1 = createHoliday(LocalDate.of(2026, 1, 1), "元日");
            HolidayMasterEntity holiday2 = createHoliday(LocalDate.of(2026, 2, 11), "建国記念の日");
            given(holidayMasterRepository.findSystemHolidaysByYear(year))
                    .willReturn(List.of(holiday1, holiday2));

            // When
            List<HolidayMasterEntity> result = holidayService.getSystemHolidaysByYear(year);

            // Then
            assertThat(result).hasSize(2);
            verify(holidayMasterRepository).findSystemHolidaysByYear(year);
        }

        @Test
        @DisplayName("境界値: 祝日が登録されていない年は空リスト")
        void getSystemHolidaysByYear_祝日なし_空リスト() {
            // Given
            int year = 2099;
            given(holidayMasterRepository.findSystemHolidaysByYear(year)).willReturn(List.of());

            // When
            List<HolidayMasterEntity> result = holidayService.getSystemHolidaysByYear(year);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
