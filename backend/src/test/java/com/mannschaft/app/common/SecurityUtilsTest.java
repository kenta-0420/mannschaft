package com.mannschaft.app.common;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SecurityUtils} の単体テスト。
 * SecurityContextHolder からユーザーIDを取得するロジックを検証する。
 */
@DisplayName("SecurityUtils 単体テスト")
class SecurityUtilsTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ========================================
    // getCurrentUserId
    // ========================================

    @Nested
    @DisplayName("getCurrentUserId")
    class GetCurrentUserId {

        @Test
        @DisplayName("正常系: 認証済みユーザーのIDが返る")
        void getCurrentUserId_認証済み_IDが返る() {
            // Given
            Authentication auth = new UsernamePasswordAuthenticationToken("123", null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);

            // When
            Long result = SecurityUtils.getCurrentUserId();

            // Then
            assertThat(result).isEqualTo(123L);
        }

        @Test
        @DisplayName("異常系: 認証情報がnullの場合はCOMMON_000例外")
        void getCurrentUserId_認証情報null_COMMON000例外() {
            // Given: SecurityContextにAuthenticationなし
            SecurityContextHolder.clearContext();

            // When / Then
            assertThatThrownBy(SecurityUtils::getCurrentUserId)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_000"));
        }

        @Test
        @DisplayName("異常系: anonymousUserの場合はCOMMON_000例外")
        void getCurrentUserId_anonymousUser_COMMON000例外() {
            // Given
            Authentication auth = new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);

            // When / Then
            assertThatThrownBy(SecurityUtils::getCurrentUserId)
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_000"));
        }

        @Test
        @DisplayName("正常系: 異なるユーザーIDでも正しく返る")
        void getCurrentUserId_異なるユーザーID_正しく返る() {
            // Given
            Authentication auth = new UsernamePasswordAuthenticationToken("999", null, List.of());
            SecurityContextHolder.getContext().setAuthentication(auth);

            // When
            Long result = SecurityUtils.getCurrentUserId();

            // Then
            assertThat(result).isEqualTo(999L);
        }
    }
}
