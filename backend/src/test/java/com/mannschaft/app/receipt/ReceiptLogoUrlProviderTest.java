package com.mannschaft.app.receipt;

import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 領収書ロゴ URL 生成の単体テスト（F08.12 実機E2E 欠陥③の根治・試練先行 red）。
 *
 * <h2>守るバグ</h2>
 * <p>{@code ReceiptLogoUrlProvider} の実装が {@code @Profile("prod")} /
 * {@code @Profile("!prod")} で二分され、prod 以外では
 * {@code https://cdn.example.com/<key>?signed=placeholder} というダミー文字列を返していた。
 * その結果、ローカル・検証・E2E 環境ではロゴが一切表示されなかった。
 * しかし画像ストレージはローカルでも MinIO（S3 互換）で実際に動いており、
 * 正準実装 {@link MediaUrlResolver} はプロファイル分岐を持たず常に presign している。
 * よってこの分岐自体が誤りであり、環境によらず実 URL を返すのが正しい。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("領収書ロゴ URL 生成（欠陥③）")
class ReceiptLogoUrlProviderTest {

    @Mock
    private StorageService storageService;

    private ReceiptLogoUrlProvider provider;

    @BeforeEach
    void setUp() {
        provider = new DefaultReceiptLogoUrlProvider(new MediaUrlResolver(storageService));
    }

    @Test
    @DisplayName("実際の署名付き URL を返す（placeholder 文字列を返さない）")
    void generatesRealSignedUrl() {
        given(storageService.generateDownloadUrl(eq("receipt/logo/1.png"), any(Duration.class)))
                .willReturn("http://localhost:9000/mannschaft/receipt/logo/1.png?X-Amz-Signature=abc");

        String url = provider.generateLogoUrl("receipt/logo/1.png");

        assertThat(url).isEqualTo("http://localhost:9000/mannschaft/receipt/logo/1.png?X-Amz-Signature=abc");
        assertThat(url).doesNotContain("cdn.example.com").doesNotContain("signed=placeholder");
    }

    @Test
    @DisplayName("キーが null / 空なら presign を呼ばず null を返す")
    void returnsNullForBlankKey() {
        assertThat(provider.generateLogoUrl(null)).isNull();
        assertThat(provider.generateLogoUrl("")).isNull();
        assertThat(provider.generateLogoUrl("   ")).isNull();
        verifyNoInteractions(storageService);
    }

    @Test
    @DisplayName("presign 失敗時は例外を伝播させず null へ縮退する（MediaUrlResolver の流儀）")
    void degradesToNullOnPresignFailure() {
        given(storageService.generateDownloadUrl(any(), any(Duration.class)))
                .willThrow(new RuntimeException("presign failed"));

        assertThat(provider.generateLogoUrl("receipt/logo/1.png")).isNull();
    }

    @Test
    @DisplayName("実装はプロファイルで切り替わらない（@Profile が付いていない）")
    void implementationIsNotProfileScoped() {
        assertThat(DefaultReceiptLogoUrlProvider.class.getAnnotation(Profile.class))
                .as("プロファイル分岐を復活させると、prod 以外でロゴが出ない欠陥が再発する")
                .isNull();
    }

    @Test
    @DisplayName("placeholder 実装は存在しない（削除済み）")
    void placeholderImplementationIsGone() {
        assertThat(Arrays.stream(new String[] {
                "com.mannschaft.app.receipt.PlaceholderReceiptLogoUrlProvider",
                "com.mannschaft.app.receipt.R2ReceiptLogoUrlProvider"})
                .filter(ReceiptLogoUrlProviderTest::classExists)
                .toList())
                .as("プロファイル二分岐の実装クラスは削除され、DefaultReceiptLogoUrlProvider に一本化される")
                .isEmpty();
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
