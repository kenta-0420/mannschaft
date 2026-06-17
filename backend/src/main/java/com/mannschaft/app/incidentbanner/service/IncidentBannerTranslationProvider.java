package com.mannschaft.app.incidentbanner.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Claude API を用いた障害告知バナーの自動翻訳プロバイダ。
 *
 * <p>{@code mannschaft.claude.api-key} を共用し、安価な Haiku 系モデルで
 * 原文（sourceLang）を各 targetLang に翻訳する。{@link com.mannschaft.app.digest.service.ClaudeDigestAiProvider}
 * の WebClient／キー注入の作法を写経している。</p>
 *
 * <p>翻訳に失敗した場合は症状を隠さず {@code log.warn} で正直に記録し、
 * 当該言語のみスキップする（呼び出し側は originalLanguage にフォールバックするため
 * バナー自体は機能し続ける）。例外の握り潰しは行わない。</p>
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class IncidentBannerTranslationProvider {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** 翻訳は短文のため安価な Haiku 系モデルを使用する（digest と同じ既定値）。 */
    private static final String MODEL = "claude-haiku-4-5";
    private static final int MAX_TOKENS = 1024;

    private final ObjectMapper objectMapper;

    @Value("${mannschaft.claude.api-key:}")
    private String apiKey;

    private WebClient webClient;

    @PostConstruct
    void init() {
        if (apiKey != null && !apiKey.isBlank()) {
            webClient = WebClient.builder()
                    .baseUrl(CLAUDE_API_URL)
                    .defaultHeader("x-api-key", apiKey)
                    .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                    .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(1024 * 1024))
                    .build();
            log.info("障害告知バナー翻訳 Claude API クライアント初期化完了");
        } else {
            log.warn("Claude API キーが未設定です。障害告知バナーの自動翻訳は動作しません。");
        }
    }

    /**
     * 原文を各対象言語へ翻訳する。
     *
     * <p>失敗した言語は結果 Map に含めない（呼び出し側でフォールバックされる）。
     * API キー未設定・全体失敗時は空 Map を返す。</p>
     *
     * @param text        原文
     * @param sourceLang  原文の言語コード（例: "ja"）
     * @param targetLangs 翻訳先言語コードのリスト（例: ["en","zh","ko","es","de"]）
     * @return 言語コード → 訳文 の Map（翻訳できた言語のみ）
     */
    public Map<String, String> translate(String text, String sourceLang, List<String> targetLangs) {
        Map<String, String> result = new LinkedHashMap<>();

        if (text == null || text.isBlank() || targetLangs == null || targetLangs.isEmpty()) {
            return result;
        }
        if (webClient == null) {
            log.warn("Claude API クライアント未初期化のため翻訳をスキップします。targetLangs={}", targetLangs);
            return result;
        }

        try {
            ObjectNode requestBody = buildRequestBody(text, sourceLang, targetLangs);

            String responseJson = webClient.post()
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseResponse(responseJson, targetLangs);

        } catch (Exception e) {
            // 症状を隠さず正直に記録する。翻訳不可の言語は呼び出し側でフォールバックされる。
            log.warn("障害告知バナーの自動翻訳に失敗しました（全言語スキップ）。sourceLang={}, targetLangs={}, error={}",
                    sourceLang, targetLangs, e.getMessage(), e);
            return result;
        }
    }

    /**
     * Messages API リクエストボディを構築する。
     *
     * <p>tool_use（Structured Output）で {@code translations} オブジェクト（lang→訳文）を強制する。</p>
     */
    private ObjectNode buildRequestBody(String text, String sourceLang, List<String> targetLangs) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", MODEL);
        body.put("max_tokens", MAX_TOKENS);

        StringBuilder sys = new StringBuilder();
        sys.append("あなたはサービスの障害告知バナーを翻訳する専門家です。\n");
        sys.append("原文の言語コードは「").append(sourceLang).append("」です。\n");
        sys.append("原文の意味・トーンを保ち、各対象言語に自然に翻訳してください。\n");
        sys.append("固有名詞・絵文字・URL はそのまま残してください。\n");
        sys.append("翻訳結果は record_translations ツールで返してください。");
        body.put("system", sys.toString());

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content",
                "対象言語: " + String.join(", ", targetLangs) + "\n\n原文:\n" + text);

        // tool_use 定義: translations オブジェクト（各 targetLang をキーに持つ）
        ArrayNode tools = body.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", "record_translations");
        tool.put("description", "各対象言語への翻訳文を記録する。");

        ObjectNode inputSchema = tool.putObject("input_schema");
        inputSchema.put("type", "object");
        ObjectNode properties = inputSchema.putObject("properties");
        ObjectNode translationsProp = properties.putObject("translations");
        translationsProp.put("type", "object");
        translationsProp.put("description", "言語コードをキー、翻訳文を値とするオブジェクト");
        ObjectNode langProps = translationsProp.putObject("properties");
        ArrayNode required = translationsProp.putArray("required");
        for (String lang : targetLangs) {
            ObjectNode langProp = langProps.putObject(lang);
            langProp.put("type", "string");
            langProp.put("description", lang + " への翻訳文");
            required.add(lang);
        }
        ArrayNode topRequired = inputSchema.putArray("required");
        topRequired.add("translations");

        ObjectNode toolChoice = body.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", "record_translations");

        return body;
    }

    /**
     * Claude API レスポンスをパースし、lang→訳文 Map を返す。
     *
     * @param targetLangs 期待する対象言語（これ以外のキーは無視する）
     */
    private Map<String, String> parseResponse(String responseJson, List<String> targetLangs) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode response = objectMapper.readTree(responseJson);

        if (response.has("error")) {
            String errorMessage = response.get("error").has("message")
                    ? response.get("error").get("message").asText()
                    : "Unknown API error";
            log.warn("障害告知バナー翻訳の Claude API がエラーを返しました: {}", errorMessage);
            return result;
        }

        JsonNode contentArray = response.get("content");
        if (contentArray == null || !contentArray.isArray()) {
            log.warn("障害告知バナー翻訳のレスポンスに content がありません。");
            return result;
        }

        for (JsonNode block : contentArray) {
            if ("tool_use".equals(block.path("type").asText())
                    && "record_translations".equals(block.path("name").asText())) {
                JsonNode translations = block.path("input").path("translations");
                if (translations.isObject()) {
                    for (String lang : targetLangs) {
                        JsonNode value = translations.get(lang);
                        if (value != null && value.isTextual() && !value.asText().isBlank()) {
                            result.put(lang, value.asText());
                        } else {
                            log.warn("障害告知バナー翻訳: 言語 {} の訳文が得られませんでした（スキップ）。", lang);
                        }
                    }
                }
                return result;
            }
        }

        log.warn("障害告知バナー翻訳: tool_use ブロックが返されませんでした。");
        return result;
    }
}
