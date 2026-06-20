package com.mannschaft.app.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.digest.DigestProperties;
import com.mannschaft.app.digest.service.ClaudeDigestAiProvider;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import com.mannschaft.app.errorreport.service.ErrorReportClaudeAiProvider;
import com.mannschaft.app.incidentbanner.service.IncidentBannerTranslationProperties;
import com.mannschaft.app.incidentbanner.service.IncidentBannerTranslationProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Claude 外部 API のコスト青天井ガード（AC-11/12/13）の構成テスト。
 *
 * <p>3 プロバイダ（{@link ErrorReportClaudeAiProvider} / {@link ClaudeDigestAiProvider}
 * / {@link IncidentBannerTranslationProvider}）が、
 * タイムアウト・リトライ設定を Properties から読み、public メソッドに
 * {@code @Retryable}（4xx 非リトライ / 5xx・タイムアウト最大3回 指数バックオフ）を
 * 付与していることをリフレクションで検証する。実 API は一切叩かない。</p>
 */
@DisplayName("Claude API コストガード 構成テスト（AC-11/12/13）")
class ClaudeApiCostGuardConfigTest {

    // ===== AC-13: Properties バインド =====

    @Test
    @DisplayName("AC-13: ErrorReportProperties.Ai に timeoutMs/retry がバインドされる")
    void errorReportPropertiesBindsTimeoutAndRetry() {
        Map<String, Object> map = new HashMap<>();
        map.put("mannschaft.error-report.ai.timeout-ms", "7000");
        map.put("mannschaft.error-report.ai.retry-max-attempts", "5");
        map.put("mannschaft.error-report.ai.retry-backoff-delay-ms", "333");

        ErrorReportProperties bound = bind("mannschaft.error-report", map, ErrorReportProperties.class);

        assertThat(bound.getAi().getTimeoutMs()).isEqualTo(7000);
        assertThat(bound.getAi().getRetryMaxAttempts()).isEqualTo(5);
        assertThat(bound.getAi().getRetryBackoffDelayMs()).isEqualTo(333);
    }

    @Test
    @DisplayName("AC-13: ErrorReportProperties.Ai のタイムアウト/リトライ既定値（5秒・3回・200ms）")
    void errorReportPropertiesDefaults() {
        ErrorReportProperties.Ai ai = new ErrorReportProperties().getAi();
        assertThat(ai.getTimeoutMs()).isEqualTo(5000);
        assertThat(ai.getRetryMaxAttempts()).isEqualTo(3);
        assertThat(ai.getRetryBackoffDelayMs()).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-13: DigestProperties に timeoutMs/retry がバインドされる")
    void digestPropertiesBindsTimeoutAndRetry() {
        Map<String, Object> map = new HashMap<>();
        map.put("mannschaft.digest.timeout-ms", "9000");
        map.put("mannschaft.digest.retry-max-attempts", "2");
        map.put("mannschaft.digest.retry-backoff-delay-ms", "150");

        DigestProperties bound = bind("mannschaft.digest", map, DigestProperties.class);

        assertThat(bound.getTimeoutMs()).isEqualTo(9000);
        assertThat(bound.getRetryMaxAttempts()).isEqualTo(2);
        assertThat(bound.getRetryBackoffDelayMs()).isEqualTo(150);
    }

    @Test
    @DisplayName("AC-13: DigestProperties のタイムアウト/リトライ既定値（5秒・3回・200ms）")
    void digestPropertiesDefaults() {
        DigestProperties props = new DigestProperties();
        assertThat(props.getTimeoutMs()).isEqualTo(5000);
        assertThat(props.getRetryMaxAttempts()).isEqualTo(3);
        assertThat(props.getRetryBackoffDelayMs()).isEqualTo(200);
    }

    @Test
    @DisplayName("AC-13: IncidentBannerTranslationProperties に timeoutMs がバインドされる")
    void incidentBannerPropertiesBindsTimeout() {
        Map<String, Object> map = new HashMap<>();
        map.put("mannschaft.incident-banner.translation.timeout-ms", "4500");

        IncidentBannerTranslationProperties bound = bind(
                "mannschaft.incident-banner.translation", map,
                IncidentBannerTranslationProperties.class);

        assertThat(bound.getTimeoutMs()).isEqualTo(4500);
    }

    @Test
    @DisplayName("AC-13: IncidentBannerTranslationProperties のタイムアウト既定値（5秒）")
    void incidentBannerPropertiesDefault() {
        assertThat(new IncidentBannerTranslationProperties().getTimeoutMs()).isEqualTo(5000);
    }

    // ===== AC-11/12: @Retryable 付与（4xx 非リトライ / 5xx・タイムアウト最大3回） =====

    @Test
    @DisplayName("AC-11/12: ErrorReportClaudeAiProvider.analyze に @Retryable が付与され 4xx 非リトライ")
    void errorReportProviderHasRetryable() throws NoSuchMethodException {
        Method analyze = ErrorReportClaudeAiProvider.class
                .getMethod("analyze", com.mannschaft.app.errorreport.service.SanitizedErrorContext.class);
        assertRetryableConfigured(analyze);
        assertHasRecover(ErrorReportClaudeAiProvider.class);
    }

    @Test
    @DisplayName("AC-11/12: ClaudeDigestAiProvider.generate に @Retryable が付与され 4xx 非リトライ")
    void digestProviderHasRetryable() {
        Method generate = Arrays.stream(ClaudeDigestAiProvider.class.getMethods())
                .filter(m -> m.getName().equals("generate"))
                .findFirst().orElseThrow();
        assertRetryableConfigured(generate);
        assertHasRecover(ClaudeDigestAiProvider.class);
    }

    @Test
    @DisplayName("AC-11/12: IncidentBannerTranslationProvider.translate に @Retryable が付与され 4xx 非リトライ")
    void incidentBannerProviderHasRetryable() throws NoSuchMethodException {
        Method translate = IncidentBannerTranslationProvider.class
                .getMethod("translate", String.class, String.class, List.class);
        assertRetryableConfigured(translate);
        assertHasRecover(IncidentBannerTranslationProvider.class);
    }

    // ===== ヘルパ =====

    private void assertRetryableConfigured(Method method) {
        Retryable retryable = AnnotationUtils.findAnnotation(method, Retryable.class);
        assertThat(retryable)
                .as("%s に @Retryable が付与されている", method.getName())
                .isNotNull();

        // 最大3回（指数バックオフ・multiplier>1）
        assertThat(retryable.maxAttempts()).isEqualTo(3);
        Backoff backoff = retryable.backoff();
        assertThat(backoff.delay()).isGreaterThan(0);
        assertThat(backoff.multiplier()).isGreaterThan(1.0);

        // 4xx（400/401/403）はリトライ対象外
        List<Class<? extends Throwable>> noRetry = Arrays.asList(retryable.noRetryFor());
        assertThat(noRetry)
                .as("4xx は noRetryFor に含まれる")
                .contains(WebClientResponseException.BadRequest.class,
                        WebClientResponseException.Unauthorized.class,
                        WebClientResponseException.Forbidden.class);
    }

    private void assertHasRecover(Class<?> clazz) {
        boolean hasRecover = Arrays.stream(clazz.getDeclaredMethods())
                .anyMatch(m -> AnnotationUtils.findAnnotation(m, Recover.class) != null);
        assertThat(hasRecover)
                .as("%s に @Recover メソッドが存在する", clazz.getSimpleName())
                .isTrue();
    }

    private <T> T bind(String prefix, Map<String, Object> map, Class<T> type) {
        ConfigurationPropertySource source = new MapConfigurationPropertySource(map);
        Binder binder = new Binder(source);
        return binder.bind(prefix, Bindable.of(type)).orElseGet(() -> {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
