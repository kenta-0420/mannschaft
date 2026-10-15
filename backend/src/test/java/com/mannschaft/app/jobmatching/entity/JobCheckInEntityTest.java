package com.mannschaft.app.jobmatching.entity;

import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JobCheckInEntity} のエンティティ単体テスト。F13.1 Phase 13.1.2。
 */
@DisplayName("JobCheckInEntity 単体テスト")
class JobCheckInEntityTest {

    private JobCheckInEntity newCheckIn() {
        return JobCheckInEntity.builder()
                .jobContractId(1L)
                .workerUserId(100L)
                .type(JobCheckInType.IN)
                .qrTokenId(10L)
                .scannedAt(Instant.parse("2026-04-24T10:00:00.000Z"))
                .serverReceivedAt(Instant.parse("2026-04-24T10:00:00.123Z"))
                .geolocationLat(new BigDecimal("35.681236"))
                .geolocationLng(new BigDecimal("139.767125"))
                .geolocationAccuracyM(new BigDecimal("12.50"))
                .clientUserAgent("Mozilla/5.0 (iPhone)")
                .build();
    }

    @Nested
    @DisplayName("clearGeolocation")
    class ClearGeolocation {

        @Test
        @DisplayName("呼び出すと緯度・経度・精度が null 化され、削除日時が記録される")
        void 呼び出すと緯度経度精度がnull化され_削除日時が記録される() {
            // Given
            JobCheckInEntity entity = newCheckIn();
            Instant deletedAt = Instant.parse("2026-07-23T03:00:00.000Z");
            assertThat(entity.getGeolocationLat()).isNotNull();
            assertThat(entity.getGeolocationLng()).isNotNull();
            assertThat(entity.getGeolocationAccuracyM()).isNotNull();
            assertThat(entity.getGeolocationDeletedAt()).isNull();

            // When
            entity.clearGeolocation(deletedAt);

            // Then
            assertThat(entity.getGeolocationLat()).isNull();
            assertThat(entity.getGeolocationLng()).isNull();
            assertThat(entity.getGeolocationAccuracyM()).isNull();
            assertThat(entity.getGeolocationDeletedAt()).isEqualTo(deletedAt);
        }

        @Test
        @DisplayName("geoAnomaly フラグ・scannedAt 等の他カラムは保持される")
        void 他カラムは保持される() {
            // Given
            JobCheckInEntity entity = newCheckIn();
            entity.markGeoAnomaly();
            Instant scannedAt = entity.getScannedAt();
            Instant deletedAt = Instant.parse("2026-07-23T03:00:00.000Z");

            // When
            entity.clearGeolocation(deletedAt);

            // Then
            assertThat(entity.getGeoAnomaly()).isTrue();
            assertThat(entity.getScannedAt()).isEqualTo(scannedAt);
            assertThat(entity.getClientUserAgent()).isEqualTo("Mozilla/5.0 (iPhone)");
        }
    }

    @Nested
    @DisplayName("markGeoAnomaly")
    class MarkGeoAnomaly {

        @Test
        @DisplayName("初期値は false であり、呼び出すと true になる")
        void 初期値はfalseであり_呼び出すとtrueになる() {
            // Given
            JobCheckInEntity entity = newCheckIn();
            assertThat(entity.getGeoAnomaly()).isFalse();

            // When
            entity.markGeoAnomaly();

            // Then
            assertThat(entity.getGeoAnomaly()).isTrue();
        }

        @Test
        @DisplayName("複数回呼び出しても true のまま（冪等）")
        void 複数回呼び出してもtrueのまま() {
            // Given
            JobCheckInEntity entity = newCheckIn();

            // When
            entity.markGeoAnomaly();
            entity.markGeoAnomaly();

            // Then
            assertThat(entity.getGeoAnomaly()).isTrue();
        }
    }
}
