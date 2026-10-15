package com.mannschaft.app.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link IpAddressUtils} の単体テスト。
 * リバースプロキシ経由のリクエストに対応したIPアドレス取得を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IpAddressUtils 単体テスト")
class IpAddressUtilsTest {

    @Mock
    private HttpServletRequest request;

    // ========================================
    // getClientIp
    // ========================================

    @Nested
    @DisplayName("getClientIp")
    class GetClientIp {

        @Test
        @DisplayName("正常系: X-Forwarded-Forヘッダーがある場合は最初のIPを返す")
        void getClientIp_XFF単一IP_そのIPを返す() {
            // Given
            given(request.getHeader("X-Forwarded-For")).willReturn("192.168.1.1");

            // When
            String result = IpAddressUtils.getClientIp(request);

            // Then
            assertThat(result).isEqualTo("192.168.1.1");
        }

        @Test
        @DisplayName("正常系: X-Forwarded-Forに複数IPがある場合は最初のIPを返す")
        void getClientIp_XFF複数IP_最初のIPを返す() {
            // Given
            given(request.getHeader("X-Forwarded-For")).willReturn("10.0.0.1, 10.0.0.2, 10.0.0.3");

            // When
            String result = IpAddressUtils.getClientIp(request);

            // Then
            assertThat(result).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("正常系: XFFがなくX-Real-IPがある場合はそれを返す")
        void getClientIp_XRealIP_そのIPを返す() {
            // Given
            given(request.getHeader("X-Forwarded-For")).willReturn(null);
            given(request.getHeader("X-Real-IP")).willReturn("172.16.0.1");

            // When
            String result = IpAddressUtils.getClientIp(request);

            // Then
            assertThat(result).isEqualTo("172.16.0.1");
        }

        @Test
        @DisplayName("正常系: XFFもX-Real-IPもない場合はremoteAddrを返す")
        void getClientIp_ヘッダーなし_remoteAddrを返す() {
            // Given
            given(request.getHeader("X-Forwarded-For")).willReturn(null);
            given(request.getHeader("X-Real-IP")).willReturn(null);
            given(request.getRemoteAddr()).willReturn("127.0.0.1");

            // When
            String result = IpAddressUtils.getClientIp(request);

            // Then
            assertThat(result).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("境界値: XFFが空白文字の場合はX-Real-IPにフォールバック")
        void getClientIp_XFF空白_XRealIPにフォールバック() {
            // Given
            given(request.getHeader("X-Forwarded-For")).willReturn("   ");
            given(request.getHeader("X-Real-IP")).willReturn("172.16.0.1");

            // When
            String result = IpAddressUtils.getClientIp(request);

            // Then
            assertThat(result).isEqualTo("172.16.0.1");
        }

        @Test
        @DisplayName("境界値: XFFもX-Real-IPも空白の場合はremoteAddrを返す")
        void getClientIp_全ヘッダー空白_remoteAddrを返す() {
            // Given
            given(request.getHeader("X-Forwarded-For")).willReturn("");
            given(request.getHeader("X-Real-IP")).willReturn("  ");
            given(request.getRemoteAddr()).willReturn("127.0.0.1");

            // When
            String result = IpAddressUtils.getClientIp(request);

            // Then
            assertThat(result).isEqualTo("127.0.0.1");
        }
    }
}
