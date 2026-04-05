package com.mannschaft.app.digest.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.digest.DigestProperties;
import com.mannschaft.app.digest.DigestStyle;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Claude API を使用したダイジェスト生成プロバイダー。
 * Messages API の tool_use（Structured Output）でタイトル・本文・抜粋を生成する。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ClaudeDigestAiProvider implements DigestAiProvider {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final DigestProperties digestProperties;
    private final ObjectMapper objectMapper;

    @Value("${mannschaft.claude.api-key:}")
    private String apiKey;

    private WebClient webClient;

    /** XSS 対策: script タグ等を除去 */
    private static final Pattern SCRIPT_TAG = Pattern.compile(
            "<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DANGEROUS_HTML = Pattern.compile(
            "<(?!(?:br|p|h[1-6]|ul|ol|li|em|strong|a|blockquote)\\b)[^>]+>", Pattern.CASE_INSENSITIVE);

    /** プロンプトインジェクション対策: 禁止ワード */
    private static final List<String> FORBIDDEN_WORDS = List.of(
            "ignore previous instructions", "ignore all instructions",
            "system prompt", "disregard", "override instructions"
    );

    @PostConstruct
    void init() {
        if (apiKey != null && !apiKey.isBlank()) {
            webClient = WebClient.builder()
                    .baseUrl(CLAUDE_API_URL)
                    .defaultHeader("x-api-key", apiKey)
                    .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                    .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(2 * 1024 * 1024))
                    .build();
            log.info("Claude API クライアント初期化完了");
        } else {
            log.warn("Claude API キーが未設定です。ダイジェスト AI 生成は動作しません。");
        }
    }

    @Override
    public AiDigestResult generate(
            List<Map<String, Object>> posts,
            DigestStyle style,
            String language,
            String customPrompt,
            String previousBody,
            boolean includeReactions,
            boolean includePolls) {

        log.info("Claude API ダイジェスト生成: style={}, posts={}, model={}",
                style, posts.size(), digestProperties.getAiModel());

        if (webClient == null) {
            log.warn("Claude API クライアント未初期化。プレースホルダーを返します。");
            return fallbackResult(posts.size(), style);
        }

        if (posts.isEmpty()) {
            return new AiDigestResult(
                    "ダイジェスト", "対象期間に投稿がありません。", "投稿なし",
                    digestProperties.getAiModel(), 0, 0);
        }

        // リクエストボディ構築
        String systemPrompt = buildSystemPrompt(style, language, previousBody);
        String userPrompt = buildUserPrompt(posts, customPrompt, includeReactions, includePolls);

        try {
            ObjectNode requestBody = buildRequestBody(systemPrompt, userPrompt);

            String responseJson = webClient.post()
                    .bodyValue(requestBody.toString())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            return parseResponse(responseJson);

        } catch (Exception e) {
            log.error("Claude API 呼び出しエラー", e);
            throw new RuntimeException("Claude API ダイジェスト生成に失敗しました: " + e.getMessage(), e);
        }
    }

    /**
     * Messages API リクエストボディを構築する。
     */
    private ObjectNode buildRequestBody(String systemPrompt, String userPrompt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", digestProperties.getAiModel());
        body.put("max_tokens", digestProperties.getAiMaxTokens());
        body.put("temperature", digestProperties.getAiTemperature());
        body.put("system", systemPrompt);

        // messages
        ArrayNode messages = body.putArray("messages");
        ObjectNode userMessage = messages.addObject();
        userMessage.put("role", "user");
        userMessage.put("content", userPrompt);

        // tool_use 定義
        ArrayNode tools = body.putArray("tools");
        ObjectNode digestTool = tools.addObject();
        digestTool.put("name", "generate_digest");
        digestTool.put("description", "タイムライン投稿からダイジェストを生成する。title, body, excerpt を返す。");

        ObjectNode inputSchema = digestTool.putObject("input_schema");
        inputSchema.put("type", "object");

        ObjectNode properties = inputSchema.putObject("properties");

        ObjectNode titleProp = properties.putObject("title");
        titleProp.put("type", "string");
        titleProp.put("description", "ダイジェストのタイトル（最大200文字）");

        ObjectNode bodyProp = properties.putObject("body");
        bodyProp.put("type", "string");
        bodyProp.put("description", "ダイジェストの本文（Markdown形式）");

        ObjectNode excerptProp = properties.putObject("excerpt");
        excerptProp.put("type", "string");
        excerptProp.put("description", "ダイジェストの抜粋（最大500文字、プレーンテキスト）");

        ArrayNode required = inputSchema.putArray("required");
        required.add("title");
        required.add("body");
        required.add("excerpt");

        // tool_choice: generate_digest を強制
        ObjectNode toolChoice = body.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", "generate_digest");

        return body;
    }

    /**
     * Claude API レスポンスをパースして AiDigestResult に変換する。
     */
    private AiDigestResult parseResponse(String responseJson) throws Exception {
        JsonNode response = objectMapper.readTree(responseJson);

        // エラーチェック
        if (response.has("error")) {
            String errorMessage = response.get("error").has("message")
                    ? response.get("error").get("message").asText()
                    : "Unknown API error";
            throw new RuntimeException("Claude API エラー: " + errorMessage);
        }

        // usage 取得
        int inputTokens = 0;
        int outputTokens = 0;
        if (response.has("usage")) {
            inputTokens = response.get("usage").path("input_tokens").asInt(0);
            outputTokens = response.get("usage").path("output_tokens").asInt(0);
        }

        // content ブロックから tool_use を検索
        JsonNode contentArray = response.get("content");
        if (contentArray != null && contentArray.isArray()) {
            for (JsonNode block : contentArray) {
                if ("tool_use".equals(block.path("type").asText())
                        && "generate_digest".equals(block.path("name").asText())) {

                    JsonNode input = block.get("input");
                    String title = sanitize(input.path("title").asText("ダイジェスト"));
                    String body = sanitize(input.path("body").asText(""));
                    String excerpt = sanitize(input.path("excerpt").asText(""));

                    // 長さ制限
                    if (title.length() > 200) {
                        title = title.substring(0, 197) + "...";
                    }
                    if (excerpt.length() > 500) {
                        excerpt = excerpt.substring(0, 497) + "...";
                    }

                    log.info("Claude API 生成完了: inputTokens={}, outputTokens={}", inputTokens, outputTokens);

                    return new AiDigestResult(title, body, excerpt,
                            digestProperties.getAiModel(), inputTokens, outputTokens);
                }
            }

            // tool_use が返らなかった場合: テキストブロックからフォールバック
            for (JsonNode block : contentArray) {
                if ("text".equals(block.path("type").asText())) {
                    String text = sanitize(block.path("text").asText(""));
                    log.warn("tool_use が返されませんでした。テキストブロックからフォールバックします。");
                    return new AiDigestResult("ダイジェスト", text, "AI 生成ダイジェスト",
                            digestProperties.getAiModel(), inputTokens, outputTokens);
                }
            }
        }

        throw new RuntimeException("Claude API レスポンスの解析に失敗しました");
    }

    /**
     * スタイル別のシステムプロンプトを構築する。
     */
    private String buildSystemPrompt(DigestStyle style, String language, String previousBody) {
        String lang = "ja".equals(language) ? "日本語" : "English";

        String styleInstruction = switch (style) {
            case SUMMARY -> "投稿内容を簡潔に要約してください。各トピックを箇条書きでまとめ、全体の傾向を冒頭で述べてください。";
            case NARRATIVE -> "投稿内容をストーリー風の読みやすい文章にまとめてください。時系列に沿って自然な流れで記述してください。";
            case HIGHLIGHTS -> "特に注目すべき投稿やトピックをハイライトしてください。エンゲージメント（リアクション・返信数）の高い投稿を優先してください。";
            case TEMPLATE -> "テンプレート形式で整理してください。";
        };

        StringBuilder sb = new StringBuilder();
        sb.append("あなたはチーム・組織のタイムライン投稿をダイジェスト記事にまとめるアシスタントです。\n\n");
        sb.append("## 出力言語\n").append(lang).append("\n\n");
        sb.append("## 生成スタイル\n").append(styleInstruction).append("\n\n");
        sb.append("## 出力フォーマット\n");
        sb.append("- title: 記事タイトル（最大200文字）\n");
        sb.append("- body: 本文（Markdown形式、見出し・箇条書き・引用を活用）\n");
        sb.append("- excerpt: 抜粋（最大500文字、プレーンテキスト）\n\n");
        sb.append("## 制約\n");
        sb.append("- HTMLタグを使用しないこと\n");
        sb.append("- 外部URLリンクを生成しないこと\n");
        sb.append("- 投稿者名はそのまま使用すること\n");
        sb.append("- 個人を特定できる情報（電話番号、住所等）を含めないこと\n");

        if (previousBody != null && !previousBody.isBlank()) {
            sb.append("\n## 差分ハイライト\n");
            sb.append("前回のダイジェスト本文を参考に、今回新たに追加された内容や変化を強調してください。\n\n");
            sb.append("前回のダイジェスト:\n```\n");
            String truncated = previousBody.length() > 2000
                    ? previousBody.substring(0, 2000) + "\n...(省略)"
                    : previousBody;
            sb.append(truncated).append("\n```\n");
        }

        return sb.toString();
    }

    /**
     * 投稿データからユーザープロンプトを構築する。
     */
    private String buildUserPrompt(List<Map<String, Object>> posts, String customPrompt,
                                    boolean includeReactions, boolean includePolls) {
        int maxChars = digestProperties.getDefaults().getContentMaxChars();
        int maxPosts = digestProperties.getDefaults().getMaxPostsPerDigest();

        StringBuilder sb = new StringBuilder();
        sb.append("以下のタイムライン投稿からダイジェストを生成してください。\n\n");

        if (customPrompt != null && !customPrompt.isBlank()) {
            String safePrompt = customPrompt;
            for (String forbidden : FORBIDDEN_WORDS) {
                safePrompt = safePrompt.replaceAll("(?i)" + Pattern.quote(forbidden), "[FILTERED]");
            }
            sb.append("追加指示: ").append(safePrompt).append("\n\n");
        }

        sb.append("## 投稿一覧\n\n");

        List<Map<String, Object>> targetPosts = posts.size() > maxPosts
                ? posts.subList(0, maxPosts) : posts;

        for (int i = 0; i < targetPosts.size(); i++) {
            Map<String, Object> post = targetPosts.get(i);
            sb.append("### 投稿 ").append(i + 1).append("\n");

            Object authorName = post.get("authorName");
            if (authorName != null) {
                sb.append("投稿者: ").append(authorName).append("\n");
            }

            Object createdAt = post.get("createdAt");
            if (createdAt != null) {
                sb.append("日時: ").append(createdAt).append("\n");
            }

            Object content = post.get("content");
            if (content != null) {
                String text = content.toString();
                if (text.length() > maxChars) {
                    text = text.substring(0, maxChars) + "...(省略)";
                }
                sb.append("内容: ").append(text).append("\n");
            }

            if (includeReactions) {
                Object reactionCount = post.get("reactionCount");
                if (reactionCount != null) {
                    sb.append("リアクション数: ").append(reactionCount).append("\n");
                }
                Object replyCount = post.get("replyCount");
                if (replyCount != null) {
                    sb.append("返信数: ").append(replyCount).append("\n");
                }
            }

            sb.append("\n");
        }

        if (posts.size() > maxPosts) {
            sb.append("（他 ").append(posts.size() - maxPosts).append(" 件省略）\n");
        }

        return sb.toString();
    }

    /**
     * AI 出力をサニタイズする（XSS 対策）。
     */
    private String sanitize(String text) {
        if (text == null) return "";
        String sanitized = SCRIPT_TAG.matcher(text).replaceAll("");
        sanitized = DANGEROUS_HTML.matcher(sanitized).replaceAll("");
        sanitized = sanitized.replaceAll("(?i)javascript:", "");
        sanitized = sanitized.replaceAll("(?i)data:", "");
        return sanitized;
    }

    /**
     * API キー未設定時のフォールバック結果。
     */
    private AiDigestResult fallbackResult(int postCount, DigestStyle style) {
        String titlePrefix = switch (style) {
            case SUMMARY -> "要約: ";
            case NARRATIVE -> "";
            case HIGHLIGHTS -> "ハイライト: ";
            default -> "";
        };

        return new AiDigestResult(
                titlePrefix + "ダイジェスト（プレースホルダー）",
                "## AI 生成本文\n\nClaude API キーが未設定のため、プレースホルダーを返しています。\n\n対象投稿数: " + postCount,
                "プレースホルダー抜粋（" + postCount + "件の投稿から生成）",
                digestProperties.getAiModel(),
                0,
                0
        );
    }
}
