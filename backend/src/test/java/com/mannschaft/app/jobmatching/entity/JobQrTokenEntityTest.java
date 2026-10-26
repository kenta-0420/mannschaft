package com.mannschaft.app.jobmatching.entity;

import com.mannschaft.app.jobmatching.enums.JobCheckInType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JobQrTokenEntity} のエンティティ単体テスト。F13.1 Phase 13.1.2。
 */
@DisplayName("JobQrTokenEntity 単体テスト")
class JobQrTokenEntityTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-04-24T10:00:00.000Z");
    /** TTL 60 秒のデフォルトを想定。 */
    private static final Instant EXPIRES_AT = Instant.parse("2026-04-24T10:01:00.000Z");

    private JobQrTokenEntity newToken() {
        return JobQrTokenEntity.builder()
                .jobContractId(1L)
                .type(JobCheckInType.IN)
                .nonce("00000000-0000-4000-8000-000000000001")
                .kid("key-2026-04")
                .shortCode("AB3C9Z")
                .issuedAt(ISSUED_AT)
                .expiresAt(EXPIRES_AT)
                .issuedByUserId(100L)
                .build();
    }

    @Nested
    @DisplayName("markUsed")
    class MarkUsed {

        @Test
        @DisplayName("未使用トークンを消費すると usedAt が記録され isUsed が true になる")
        void 未使用トークンを消費すると_usedAtが記録され_isUsedがtrueになる() {
            // Given
            JobQrTokenEntity token = newToken();
            Instant now = Instant.parse("2026-04-24T10:00:30.000Z");
            assertThat(token.isUsed()).isFalse();
            assertThat(token.getUsedAt()).isNull();

            // When
            token.markUsed(now);

            // Then
            assertThat(token.getUsedAt()).isEqualTo(now);
            assertThat(token.isUsed()).isTrue();
        }

        @Test
        @DisplayName("markUsed は冪等ではなく、後続呼び出しで usedAt が上書きされる（再利用判定は isUsed で別途ガードする前提）")
        void markUsedは冪等ではなく_後続呼び出しでusedAtが上書きされる() {
            // Given
            JobQrTokenEntity token = newToken();
            Instant first = Instant.parse("2026-04-24T10:00:10.000Z");
            Instant second = Instant.parse("2026-04-24T10:00:40.000Z");
            token.markUsed(first);

            // When
            token.markUsed(second);

            // Then
            assertThat(token.getUsedAt()).isEqualTo(second);
        }
    }

    @Nested
    @DisplayName("isExpired")
    class IsExpired {

        @Test
        @DisplayName("expiresAt より前の時刻では false")
        void expiresAtより前の時刻ではfalse() {
            // Given
            JobQrTokenEntity token = newToken();
            Instant beforeExpiry = Instant.parse("2026-04-24T10:00:59.999Z");

            // When / Then
            assertThat(token.isExpired(beforeExpiry)).isFalse();
        }

        @Test
        @DisplayName("expiresAt と同じ時刻では true（境界は失効側）")
        void expiresAtと同じ時刻ではtrue_境界は失効側() {
            // Given
            JobQrTokenEntity token = newToken();

            // When / Then
            assertThat(token.isExpired(EXPIRES_AT)).isTrue();
        }

        @Test
        @DisplayName("expiresAt より後の時刻では true")
        void expiresAtより後の時刻ではtrue() {
            // Given
            JobQrTokenEntity token = newToken();
            Instant afterExpiry = Instant.parse("2026-04-24T10:02:00.000Z");

            // When / Then
            assertThat(token.isExpired(afterExpiry)).isTrue();
        }
    }

    @Nested
    @DisplayName("isWithinIssuedAndExpires")
    class IsWithinIssuedAndExpires {

        @Test
        @DisplayName("issuedAt ちょうどは有効範囲内（両端 include/exclude の半開区間で issuedAt 側は含む）")
        void issuedAtちょうどは有効範囲内() {
            // Given
            JobQrTokenEntity token = newToken();

            // When / Then
            assertThat(token.isWithinIssuedAndExpires(ISSUED_AT)).isTrue();
        }

        @Test
        @DisplayName("issuedAt と expiresAt の中間は有効範囲内")
        void issuedAtとexpiresAtの中間は有効範囲内() {
            // Given
            JobQrTokenEntity token = newToken();
            Instant middle = Instant.parse("2026-04-24T10:00:30.000Z");

            // When / Then
            assertThat(token.isWithinIssuedAndExpires(middle)).isTrue();
        }

        @Test
        @DisplayName("issuedAt より前は有効範囲外")
        void issuedAtより前は有効範囲外() {
            // Given
            JobQrTokenEntity token = newToken();
            Instant before = Instant.parse("2026-04-24T09:59:59.999Z");

            // When / Then
            assertThat(token.isWithinIssuedAndExpires(before)).isFalse();
        }

        @Test
        @DisplayName("expiresAt ちょうどは有効範囲外（半開区間で expiresAt 側は含まない）")
        void expiresAtちょうどは有効範囲外() {
            // Given
            JobQrTokenEntity token = newToken();

            // When / Then
            assertThat(token.isWithinIssuedAndExpires(EXPIRES_AT)).isFalse();
        }

        @Test
        @DisplayName("expiresAt より後は有効範囲外")
        void expiresAtより後は有効範囲外() {
            // Given
            JobQrTokenEntity token = newToken();
            Instant after = Instant.parse("2026-04-24T10:01:00.001Z");

            // When / Then
            assertThat(token.isWithinIssuedAndExpires(after)).isFalse();
        }
    }

    @Nested
    @DisplayName("isUsed")
    class IsUsedTests {

        @Test
        @DisplayName("usedAt が null の場合は false")
        void usedAtがnullの場合はfalse() {
            assertThat(newToken().isUsed()).isFalse();
        }

        @Test
        @DisplayName("usedAt が記録済みの場合は true")
        void usedAtが記録済みの場合はtrue() {
            JobQrTokenEntity token = newToken();
            token.markUsed(Instant.parse("2026-04-24T10:00:30.000Z"));
            assertThat(token.isUsed()).isTrue();
        }
    }
}
