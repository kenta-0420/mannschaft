package com.mannschaft.app.analytics;

import com.mannschaft.app.analytics.service.DateRangeResolver;
import com.mannschaft.app.analytics.service.DateRangeResolver.DateRange;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DateRangeResolver 単体テスト")
class DateRangeResolverTest {

    private final DateRangeResolver resolver = new DateRangeResolver();

    // ========== resolve ==========

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("正常系: preset指定でプリセット優先")
        void testResolve_preset指定でプリセット優先() {
            // Arrange
            LocalDate from = LocalDate.of(2026, 1, 1);
            LocalDate to = LocalDate.of(2026, 1, 31);

            // Act - preset が非null ならそちらが優先
            DateRange range = resolver.resolve(from, to, DatePreset.LAST_7D);

            // Assert - from/to はプリセットの値になる（固定日ではないが、7日間の範囲であることを確認）
            assertThat(range).isNotNull();
            assertThat(range.getFrom()).isNotNull();
            assertThat(range.getTo()).isNotNull();
            // LAST_7D は today - 6 ～ today なので 7日間
            long days = java.time.temporal.ChronoUnit.DAYS.between(range.getFrom(), range.getTo()) + 1;
            assertThat(days).isEqualTo(7);
        }

        @Test
        @DisplayName("正常系: from/to 直接指定")
        void testResolve_fromTo直接指定() {
            // Arrange
            LocalDate from = LocalDate.of(2026, 3, 1);
            LocalDate to = LocalDate.of(2026, 3, 31);

            // Act
            DateRange range = resolver.resolve(from, to, null);

            // Assert
            assertThat(range.getFrom()).isEqualTo(from);
            assertThat(range.getTo()).isEqualTo(to);
        }

        @Test
        @DisplayName("異常系: from > to で例外")
        void testResolve_fromがtoより後で例外() {
            // Arrange
            LocalDate from = LocalDate.of(2026, 3, 31);
            LocalDate to = LocalDate.of(2026, 3, 1);

            // Act / Assert
            assertThatThrownBy(() -> resolver.resolve(from, to, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_005"));
        }

        @Test
        @DisplayName("異常系: from/to null + preset null で例外")
        void testResolve_全てnullで例外() {
            // Act / Assert
            assertThatThrownBy(() -> resolver.resolve(null, null, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_005"));
        }

        @Test
        @DisplayName("異常系: from null + to指定 + preset null で例外")
        void testResolve_fromのみnullで例外() {
            // Act / Assert
            assertThatThrownBy(() -> resolver.resolve(null, LocalDate.of(2026, 3, 31), null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_005"));
        }
    }

    // ========== validateGranularity ==========

    @Nested
    @DisplayName("validateGranularity")
    class ValidateGranularity {

        @Test
        @DisplayName("正常系: DAILY 90日以内OK")
        void testValidateGranularity_DAILY_90日OK() {
            // Arrange
            DateRange range = new DateRange(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)); // 90日

            // Act / Assert - 例外が発生しないこと
            resolver.validateGranularity(range, Granularity.DAILY);
        }

        @Test
        @DisplayName("異常系: DAILY 91日で例外")
        void testValidateGranularity_DAILY_91日で例外() {
            // Arrange
            DateRange range = new DateRange(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 1)); // 91日

            // Act / Assert
            assertThatThrownBy(() -> resolver.validateGranularity(range, Granularity.DAILY))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_004"));
        }

        @Test
        @DisplayName("正常系: WEEKLY 364日以内OK")
        void testValidateGranularity_WEEKLY_364日OK() {
            // Arrange
            DateRange range = new DateRange(
                    LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 30)); // 364日

            // Act / Assert - 例外が発生しないこと
            resolver.validateGranularity(range, Granularity.WEEKLY);
        }

        @Test
        @DisplayName("正常系: MONTHLY 36ヶ月以内OK")
        void testValidateGranularity_MONTHLY_36ヶ月OK() {
            // Arrange
            DateRange range = new DateRange(
                    LocalDate.of(2023, 4, 1), LocalDate.of(2026, 3, 1)); // 36ヶ月

            // Act / Assert - 例外が発生しないこと
            resolver.validateGranularity(range, Granularity.MONTHLY);
        }
    }

    // ========== validateExportRange ==========

    @Nested
    @DisplayName("validateExportRange")
    class ValidateExportRange {

        @Test
        @DisplayName("正常系: 365日以内OK")
        void testValidateExportRange_365日OK() {
            // Arrange
            DateRange range = new DateRange(
                    LocalDate.of(2025, 4, 1), LocalDate.of(2026, 3, 31)); // 365日

            // Act / Assert - 例外が発生しないこと
            resolver.validateExportRange(range);
        }

        @Test
        @DisplayName("異常系: 366日で例外")
        void testValidateExportRange_366日で例外() {
            // Arrange
            DateRange range = new DateRange(
                    LocalDate.of(2025, 3, 31), LocalDate.of(2026, 3, 31)); // 366日

            // Act / Assert
            assertThatThrownBy(() -> resolver.validateExportRange(range))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("ANALYTICS_007"));
        }
    }

    // ========== resolvePreset ==========

    @Nested
    @DisplayName("resolvePreset (resolve経由)")
    class ResolvePreset {

        @Test
        @DisplayName("LAST_7D: 今日から6日前 ～ 今日")
        void testResolvePreset_LAST_7D() {
            // Act
            DateRange range = resolver.resolve(null, null, DatePreset.LAST_7D);

            // Assert
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
            assertThat(range.getFrom()).isEqualTo(today.minusDays(6));
            assertThat(range.getTo()).isEqualTo(today);
        }

        @Test
        @DisplayName("LAST_30D: 今日から29日前 ～ 今日")
        void testResolvePreset_LAST_30D() {
            // Act
            DateRange range = resolver.resolve(null, null, DatePreset.LAST_30D);

            // Assert
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
            assertThat(range.getFrom()).isEqualTo(today.minusDays(29));
            assertThat(range.getTo()).isEqualTo(today);
        }

        @Test
        @DisplayName("THIS_MONTH: 今月1日 ～ 今日")
        void testResolvePreset_THIS_MONTH() {
            // Act
            DateRange range = resolver.resolve(null, null, DatePreset.THIS_MONTH);

            // Assert
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
            assertThat(range.getFrom()).isEqualTo(today.withDayOfMonth(1));
            assertThat(range.getTo()).isEqualTo(today);
        }

        @Test
        @DisplayName("YTD: 今年1月1日 ～ 今日")
        void testResolvePreset_YTD() {
            // Act
            DateRange range = resolver.resolve(null, null, DatePreset.YTD);

            // Assert
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Tokyo"));
            assertThat(range.getFrom()).isEqualTo(LocalDate.of(today.getYear(), 1, 1));
            assertThat(range.getTo()).isEqualTo(today);
        }
    }
}
