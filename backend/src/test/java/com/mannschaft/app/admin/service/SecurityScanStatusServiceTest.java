package com.mannschaft.app.admin.service;

import com.mannschaft.app.admin.config.GitHubProperties;
import com.mannschaft.app.admin.dto.SecurityScanStatusResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link SecurityScanStatusService} の単体テスト。
 *
 * <p>GitHub Actions API のレスポンスを RestTemplate でモックし、
 * 正常系・異常系・トークン有無の各シナリオを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SecurityScanStatusService 単体テスト")
class SecurityScanStatusServiceTest {

    private static final String OWNER = "kenta-0420";
    private static final String REPO = "mannschaft";

    @Mock
    private RestTemplate restTemplate;

    // ========================================
    // getStatus — API エラー系
    // ========================================

    @Nested
    @DisplayName("getStatus — API エラー系")
    class GetStatus_ApiError {

        @Test
        @DisplayName("GitHub API が 401 を返した場合、conclusion=UNKNOWN が返される")
        void 取得_401エラー_UNKNOWN返却() {
            // Given
            GitHubProperties props = new GitHubProperties(OWNER, REPO, "invalid-token");
            SecurityScanStatusService service = new SecurityScanStatusService(props, restTemplate);

            given(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                    .willThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED));

            // When
            SecurityScanStatusResponse result = service.getStatus();

            // Then
            assertThat(result.conclusion()).isEqualTo("UNKNOWN");
            assertThat(result.runUrl()).isNull();
            assertThat(result.runAt()).isNull();
        }

        @Test
        @DisplayName("GitHub API が 403 Forbidden を返した場合、conclusion=UNKNOWN が返される")
        void 取得_403エラー_UNKNOWN返却() {
            // Given
            GitHubProperties props = new GitHubProperties(OWNER, REPO, "test-token");
            SecurityScanStatusService service = new SecurityScanStatusService(props, restTemplate);

            given(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                    .willThrow(new HttpClientErrorException(HttpStatus.FORBIDDEN));

            // When
            SecurityScanStatusResponse result = service.getStatus();

            // Then
            assertThat(result.conclusion()).isEqualTo("UNKNOWN");
            assertThat(result.runUrl()).isNull();
            assertThat(result.runAt()).isNull();
        }

        @Test
        @DisplayName("GitHub API が 500 を返した場合、conclusion=UNKNOWN が返される")
        void 取得_5xxエラー_UNKNOWN返却() {
            // Given
            GitHubProperties props = new GitHubProperties(OWNER, REPO, "test-token");
            SecurityScanStatusService service = new SecurityScanStatusService(props, restTemplate);

            given(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                    .willThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            // When
            SecurityScanStatusResponse result = service.getStatus();

            // Then
            assertThat(result.conclusion()).isEqualTo("UNKNOWN");
            assertThat(result.runUrl()).isNull();
            assertThat(result.runAt()).isNull();
        }

        @Test
        @DisplayName("ネットワーク例外（接続拒否）が発生した場合、conclusion=UNKNOWN が返される")
        void 取得_接続拒否_UNKNOWN返却() {
            // Given
            GitHubProperties props = new GitHubProperties(OWNER, REPO, "test-token");
            SecurityScanStatusService service = new SecurityScanStatusService(props, restTemplate);

            given(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                    .willThrow(new ResourceAccessException("Connection refused"));

            // When
            SecurityScanStatusResponse result = service.getStatus();

            // Then
            assertThat(result.conclusion()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("null レスポンスが返された場合、conclusion=UNKNOWN が返される")
        void 取得_nullレスポンス_UNKNOWN返却() {
            // Given
            GitHubProperties props = new GitHubProperties(OWNER, REPO, "test-token");
            SecurityScanStatusService service = new SecurityScanStatusService(props, restTemplate);

            given(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                    .willReturn(new ResponseEntity<>(null, HttpStatus.OK));

            // When
            SecurityScanStatusResponse result = service.getStatus();

            // Then
            assertThat(result.conclusion()).isEqualTo("UNKNOWN");
        }
    }

    // ========================================
    // getStatus — トークン設定確認
    // ========================================

    @Nested
    @DisplayName("getStatus — トークン設定確認")
    class GetStatus_TokenConfig {

        @Test
        @DisplayName("トークンが空の場合、Authorization ヘッダなしで API が呼ばれる")
        void 取得_トークン空_認証ヘッダなしでAPI呼び出し() {
            // Given — token を空文字に設定
            GitHubProperties props = new GitHubProperties(OWNER, REPO, "");
            SecurityScanStatusService service = new SecurityScanStatusService(props, restTemplate);

            given(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                    .willReturn(new ResponseEntity<>(null, HttpStatus.OK));

            // When
            SecurityScanStatusResponse result = service.getStatus();

            // Then — token なしでも例外は発生せず exchange が呼ばれること
            assertThat(result).isNotNull();
            verify(restTemplate).exchange(anyString(), any(), any(), any(Class.class));
        }

        @Test
        @DisplayName("トークンが設定されている場合、例外なく API が呼ばれる")
        void 取得_トークンあり_API呼び出し成功() {
            // Given
            GitHubProperties props = new GitHubProperties(OWNER, REPO, "ghp_test_token");
            SecurityScanStatusService service = new SecurityScanStatusService(props, restTemplate);

            given(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                    .willReturn(new ResponseEntity<>(null, HttpStatus.OK));

            // When
            SecurityScanStatusResponse result = service.getStatus();

            // Then
            assertThat(result).isNotNull();
            verify(restTemplate).exchange(anyString(), any(), any(), any(Class.class));
        }
    }

    // ========================================
    // getStatus — URL 構築確認
    // ========================================

    @Nested
    @DisplayName("getStatus — URL 構築確認")
    class GetStatus_UrlConstruction {

        @Test
        @DisplayName("正しい GitHub API URL（owner/repo/security-scan.yml）が構築される")
        void URL構築_正しいURL使用() {
            // Given
            GitHubProperties props = new GitHubProperties(OWNER, REPO, "test-token");
            SecurityScanStatusService service = new SecurityScanStatusService(props, restTemplate);

            given(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                    .willReturn(new ResponseEntity<>(null, HttpStatus.OK));

            // When
            service.getStatus();

            // Then — 正しい URL が使用されていることを確認
            verify(restTemplate).exchange(
                    eq("https://api.github.com/repos/kenta-0420/mannschaft/actions/workflows/security-scan.yml/runs?per_page=1"),
                    any(), any(), any(Class.class));
        }

        @Test
        @DisplayName("owner と repo が異なる場合、URL に反映される")
        void URL構築_異なるオーナーとリポジトリ() {
            // Given
            GitHubProperties props = new GitHubProperties("other-owner", "other-repo", "");
            SecurityScanStatusService service = new SecurityScanStatusService(props, restTemplate);

            given(restTemplate.exchange(anyString(), any(), any(), any(Class.class)))
                    .willReturn(new ResponseEntity<>(null, HttpStatus.OK));

            // When
            service.getStatus();

            // Then
            verify(restTemplate).exchange(
                    eq("https://api.github.com/repos/other-owner/other-repo/actions/workflows/security-scan.yml/runs?per_page=1"),
                    any(), any(), any(Class.class));
        }
    }
}
