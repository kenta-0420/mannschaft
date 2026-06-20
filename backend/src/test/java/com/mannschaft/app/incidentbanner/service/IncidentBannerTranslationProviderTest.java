package com.mannschaft.app.incidentbanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link IncidentBannerTranslationProvider} の単体テスト（Claude API は WebClient モックで完全に遮断）。
 *
 * <p>実 API は絶対に叩かない。Claude Messages API の tool_use レスポンスをスタブし、
 * ja 原文 → 各対象言語の Map 整形・フォールバック・異常系を検証する。</p>
 */
@DisplayName("IncidentBannerTranslationProvider 単体テスト")
class IncidentBannerTranslationProviderTest {

    private static final List<String> TARGETS = List.of("en", "zh", "ko", "es", "de");

    private IncidentBannerTranslationProvider provider;

    @BeforeEach
    void setUp() {
        provider = new IncidentBannerTranslationProvider(
                new ObjectMapper(), new IncidentBannerTranslationProperties());
    }

    @Test
    @DisplayName("WebClient 未初期化（API キー未設定）なら空 Map を返す")
    void translate_クライアント未初期化なら空Map() {
        // webClient は @PostConstruct を呼んでいないため null のまま
        Map<String, String> result = provider.translate("障害が発生しています", "ja", TARGETS);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("原文が空白なら API を呼ばず空 Map を返す")
    void translate_原文空白なら空Map() {
        ReflectionTestUtils.setField(provider, "webClient", mock(WebClient.class));

        assertThat(provider.translate("   ", "ja", TARGETS)).isEmpty();
        assertThat(provider.translate(null, "ja", TARGETS)).isEmpty();
    }

    @Test
    @DisplayName("対象言語が空なら API を呼ばず空 Map を返す")
    void translate_対象言語空なら空Map() {
        ReflectionTestUtils.setField(provider, "webClient", mock(WebClient.class));

        assertThat(provider.translate("障害です", "ja", List.of())).isEmpty();
        assertThat(provider.translate("障害です", "ja", null)).isEmpty();
    }

    @Test
    @DisplayName("tool_use レスポンスが各言語の訳文 Map に整形される")
    void translate_toolUseを各言語Mapに整形() {
        String response = """
                {
                  "type": "message",
                  "content": [{
                    "type": "tool_use",
                    "name": "record_translations",
                    "input": {
                      "translations": {
                        "en": "Service disruption is occurring.",
                        "zh": "正在发生服务故障。",
                        "ko": "서비스 장애가 발생하고 있습니다.",
                        "es": "Se está produciendo una interrupción del servicio.",
                        "de": "Es liegt eine Störung vor."
                      }
                    }
                  }]
                }
                """;
        ReflectionTestUtils.setField(provider, "webClient", mockWebClient(response));

        Map<String, String> result = provider.translate("障害が発生しています。", "ja", TARGETS);

        assertThat(result)
                .containsEntry("en", "Service disruption is occurring.")
                .containsEntry("zh", "正在发生服务故障。")
                .containsEntry("ko", "서비스 장애가 발생하고 있습니다.")
                .containsEntry("es", "Se está produciendo una interrupción del servicio.")
                .containsEntry("de", "Es liegt eine Störung vor.")
                .hasSize(5);
    }

    @Test
    @DisplayName("一部言語の訳文が欠落/空白ならその言語のみスキップされる")
    void translate_欠落言語はスキップ() {
        String response = """
                {
                  "content": [{
                    "type": "tool_use",
                    "name": "record_translations",
                    "input": {
                      "translations": {
                        "en": "Service disruption.",
                        "zh": "",
                        "ko": "서비스 장애."
                      }
                    }
                  }]
                }
                """;
        ReflectionTestUtils.setField(provider, "webClient", mockWebClient(response));

        Map<String, String> result = provider.translate("障害です", "ja", TARGETS);

        // en/ko のみ得られ、空白の zh と未返却の es/de は含まれない
        assertThat(result).containsOnlyKeys("en", "ko");
    }

    @Test
    @DisplayName("対象外言語キーが混ざっても要求した言語だけ取り込む")
    void translate_対象外キーは無視() {
        String response = """
                {
                  "content": [{
                    "type": "tool_use",
                    "name": "record_translations",
                    "input": {
                      "translations": {
                        "en": "Disruption.",
                        "fr": "Perturbation.",
                        "ja": "障害です"
                      }
                    }
                  }]
                }
                """;
        ReflectionTestUtils.setField(provider, "webClient", mockWebClient(response));

        Map<String, String> result = provider.translate("障害です", "ja", List.of("en"));

        assertThat(result).containsOnlyKeys("en")
                .containsEntry("en", "Disruption.");
    }

    @Test
    @DisplayName("error フィールド付きレスポンスは空 Map（症状を隠さず log.warn・例外は握らない）")
    void translate_APIエラーは空Map() {
        ReflectionTestUtils.setField(provider, "webClient",
                mockWebClient("{\"error\":{\"message\":\"rate limited\"}}"));

        assertThat(provider.translate("障害です", "ja", TARGETS)).isEmpty();
    }

    @Test
    @DisplayName("tool_use ブロックが無い（text のみ）なら空 Map を返す")
    void translate_toolUseなしなら空Map() {
        ReflectionTestUtils.setField(provider, "webClient",
                mockWebClient("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}"));

        assertThat(provider.translate("障害です", "ja", TARGETS)).isEmpty();
    }

    @Test
    @DisplayName("WebClient 呼び出しが例外を投げても握らず空 Map を返す（バナーは原文で機能継続）")
    void translate_呼び出し例外でも空Map() {
        WebClient webClient = mock(WebClient.class);
        given(webClient.post()).willThrow(new RuntimeException("network down"));
        ReflectionTestUtils.setField(provider, "webClient", webClient);

        assertThat(provider.translate("障害です", "ja", TARGETS)).isEmpty();
    }

    /**
     * WebClient の {@code post().bodyValue(...).retrieve().bodyToMono(String.class).block()}
     * を固定レスポンスでスタブする（ErrorReportClaudeAiProviderTest と同じ作法）。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private WebClient mockWebClient(String responseBody) {
        WebClient webClient = mock(WebClient.class);
        WebClient.RequestBodyUriSpec uriSpec = mock(WebClient.RequestBodyUriSpec.class);
        WebClient.RequestHeadersSpec headersSpec = mock(WebClient.RequestHeadersSpec.class);
        WebClient.ResponseSpec responseSpec = mock(WebClient.ResponseSpec.class);
        given(webClient.post()).willReturn(uriSpec);
        given(uriSpec.bodyValue(any())).willReturn((WebClient.RequestHeadersSpec) headersSpec);
        given(headersSpec.retrieve()).willReturn(responseSpec);
        given(responseSpec.bodyToMono(String.class)).willReturn(Mono.just(responseBody));
        return webClient;
    }
}
