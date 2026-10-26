package com.mannschaft.app.common.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MediaUrlResolver} の純ユニットテスト（Mockito）。
 *
 * <p>DB に保存された生の R2 キー（例: {@code team/12/icon/x.png}）を、表示用の
 * 署名付き GET URL（絶対 URL）へ解決する共通部品の挙動を検証する。
 * 画像 URL 404 根治 Phase 1（チームアイコン/バナーの表示 URL 化）の受け入れ条件に対応する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MediaUrlResolver — 生キーから署名付き表示URLへの解決")
class MediaUrlResolverTest {

    @Mock
    private StorageService storageService;

    @InjectMocks
    private MediaUrlResolver mediaUrlResolver;

    @Test
    @DisplayName("AC-1: resolve(null) は null を返し presign を呼ばない")
    void resolve_null_returnsNullWithoutPresign() {
        String result = mediaUrlResolver.resolve(null);

        assertThat(result).isNull();
        verify(storageService, never()).generateDownloadUrl(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("AC-2: resolve(空文字/空白) は null を返し presign を呼ばない")
    void resolve_blank_returnsNullWithoutPresign() {
        assertThat(mediaUrlResolver.resolve("")).isNull();
        assertThat(mediaUrlResolver.resolve("   ")).isNull();
        verify(storageService, never()).generateDownloadUrl(anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("AC-3: resolve(生キー) は presign の結果を返し TTL=3600s で呼ぶ")
    void resolve_validKey_returnsPresignedUrlWith3600sTtl() {
        String key = "team/12/icon/x.png";
        String signedUrl = "http://localhost:9000/test-bucket/team/12/icon/x.png?X-Amz-Signature=abc";
        when(storageService.generateDownloadUrl(eq(key), any(Duration.class))).thenReturn(signedUrl);

        String result = mediaUrlResolver.resolve(key);

        assertThat(result).isEqualTo(signedUrl);

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(storageService).generateDownloadUrl(eq(key), ttlCaptor.capture());
        assertThat(ttlCaptor.getValue()).isEqualTo(Duration.ofSeconds(3600));
    }

    @Test
    @DisplayName("AC-4: presign が例外を投げても伝播させず null に縮退する")
    void resolve_presignThrows_returnsNullWithoutPropagating() {
        String key = "team/12/banner/y.png";
        when(storageService.generateDownloadUrl(eq(key), any(Duration.class)))
                .thenThrow(new RuntimeException("R2 presign 失敗"));

        String result = mediaUrlResolver.resolve(key);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("AC-5: resolveAll は同一キーをメモ化し presign を 1 回だけ呼ぶ")
    void resolveAll_dedupesKeys_callsPresignOnce() {
        String key = "team/12/icon/x.png";
        String signedUrl = "http://localhost:9000/test-bucket/team/12/icon/x.png?X-Amz-Signature=abc";
        when(storageService.generateDownloadUrl(eq(key), any(Duration.class))).thenReturn(signedUrl);

        Map<String, String> result = mediaUrlResolver.resolveAll(List.of(key, key, key));

        assertThat(result).containsEntry(key, signedUrl);
        verify(storageService, times(1)).generateDownloadUrl(eq(key), any(Duration.class));
    }

    @Test
    @DisplayName("AC-5b: resolveAll は null/空白キーを除外し presign を呼ばない")
    void resolveAll_excludesNullAndBlank() {
        Map<String, String> result = mediaUrlResolver.resolveAll(java.util.Arrays.asList(null, "", "   "));

        assertThat(result).isEmpty();
        verify(storageService, never()).generateDownloadUrl(anyString(), any(Duration.class));
    }
}
