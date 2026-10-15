package com.mannschaft.app.errorreport.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.errorreport.ErrorReportActivityType;
import com.mannschaft.app.errorreport.ErrorReportErrorCode;
import com.mannschaft.app.errorreport.ErrorReportProperties;
import com.mannschaft.app.errorreport.ErrorReportStatus;
import com.mannschaft.app.errorreport.entity.ErrorReportAiAnalysisEntity;
import com.mannschaft.app.errorreport.entity.ErrorReportEntity;
import com.mannschaft.app.errorreport.repository.ErrorReportAiAnalysisRepository;
import com.mannschaft.app.errorreport.repository.ErrorReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F12.5 Phase 2-D — エラーレポートを GitHub Issue に転記するサービス。
 *
 * <p>仕様: docs/features/F12.5_frontend_error_tracking.md §13 (Phase 2-D)。</p>
 *
 * <ul>
 *   <li>{@code mannschaft.error-report.github.enabled = true} かつ環境変数
 *       {@code GH_TOKEN} / {@code GH_OWNER} / {@code GH_REPO} がすべて揃っているときのみ有効</li>
 *   <li>同一エラーレポートからの Issue 二重作成は Valkey の SETNX ロック + DB 値チェックで防止</li>
 *   <li>本文に PII（メール/IP/トークン等）を絶対に含めない（{@link ErrorReportSanitizer} 経由）</li>
 *   <li>AI 分析結果が含む外部 URL（自ドメイン以外）は {@code [text]} に縮約</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GitHubIssueService {

    /** GitHub REST API ベース URL。 */
    private static final String GITHUB_API_BASE = "https://api.github.com";

    /** タイトル中エラーメッセージの最大長。 */
    private static final int TITLE_MESSAGE_MAX = 100;

    /** 重複作成防止ロックの TTL（秒）。 */
    private static final long LOCK_TTL_SECONDS = 60;

    /** Markdown リンク {@code [text](url)} のパターン。 */
    private static final Pattern MARKDOWN_LINK =
            Pattern.compile("\\[([^\\]]+)\\]\\(([^)]+)\\)");

    private final ObjectMapper objectMapper;
    private final ErrorReportSanitizer sanitizer;
    private final ErrorReportProperties props;
    private final ErrorReportRepository errorReportRepository;
    private final ErrorReportAiAnalysisRepository aiAnalysisRepository;
    private final ErrorReportActivityService activityService;
    private final StringRedisTemplate redisTemplate;

    @Value("${GH_TOKEN:}")
    private String token;

    @Value("${GH_OWNER:}")
    private String owner;

    @Value("${GH_REPO:}")
    private String repo;

    @Value("${app.base-url}")
    private String appBaseUrl;

    private final RestClient restClient = RestClient.create();

    /**
     * GitHub 連携が利用可能か。
     * フロントエンドの {@code /config} エンドポイントから参照される。
     */
    public boolean isAvailable() {
        return props.getGithub().isEnabled()
                && token != null && !token.isBlank()
                && owner != null && !owner.isBlank()
                && repo != null && !repo.isBlank();
    }

    /**
     * GitHub Issue を作成し、エラーレポートに URL を保存する。
     *
     * @param errorReportId エラーレポート ID
     * @param actorId       操作者ユーザー ID
     * @return 作成された Issue の HTML URL
     */
    @Transactional
    public String createIssue(Long errorReportId, Long actorId) {
        if (!isAvailable()) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_010);
        }

        ErrorReportEntity report = errorReportRepository.findById(errorReportId)
                .orElseThrow(() -> new BusinessException(ErrorReportErrorCode.ERROR_REPORT_NOT_FOUND));

        if (report.getGithubIssueUrl() != null && !report.getGithubIssueUrl().isBlank()) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_012);
        }

        // Valkey で重複作成防止（SETNX）
        String lockKey = "error-report:github-creating:" + errorReportId;
        Boolean lockAcquired = redisTemplate.opsForValue()
                .setIfAbsent(lockKey, "locked", Duration.ofSeconds(LOCK_TTL_SECONDS));
        if (Boolean.FALSE.equals(lockAcquired)) {
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_009);
        }

        try {
            ErrorReportAiAnalysisEntity latestAi = aiAnalysisRepository
                    .findFirstByErrorReportIdAndStatusOrderByCreatedAtDesc(errorReportId, "SUCCESS")
                    .orElse(null);

            String title = buildTitle(report);
            String body = buildBody(report, latestAi);

            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("title", title);
            payload.put("body", body);
            ArrayNode labels = payload.putArray("labels");
            labels.add("frontend-error");
            labels.add("severity:" + report.getSeverity().name().toLowerCase());
            if (report.getStatus() == ErrorReportStatus.REOPENED) {
                labels.add("regression");
            }

            String response = restClient.post()
                    .uri(GITHUB_API_BASE + "/repos/" + owner + "/" + repo + "/issues")
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload.toString())
                    .retrieve()
                    .body(String.class);

            JsonNode parsed = objectMapper.readTree(response);
            String htmlUrl = parsed.path("html_url").asText();
            int issueNumber = parsed.path("number").asInt();

            if (htmlUrl == null || htmlUrl.isBlank()) {
                log.error("GitHub Issue 作成: html_url が応答に含まれない errorReportId={}", errorReportId);
                throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_011);
            }

            report.setGithubIssueUrl(htmlUrl);

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("issueUrl", htmlUrl);
            metadata.put("issueNumber", issueNumber);
            activityService.record(errorReportId, actorId,
                    ErrorReportActivityType.GITHUB_ISSUE_CREATED, null, metadata);

            log.info("GitHub Issue 作成成功: errorReportId={}, issueNumber={}", errorReportId, issueNumber);
            return htmlUrl;
        } catch (BusinessException e) {
            throw e;
        } catch (HttpClientErrorException.Unauthorized | HttpClientErrorException.Forbidden e) {
            log.error("GitHub Issue 作成: 認可失敗 errorReportId={} status={}",
                    errorReportId, e.getStatusCode());
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_011);
        } catch (Exception e) {
            // 例外メッセージのみログ。token は @Value で注入済みだが念のためメッセージから除去。
            log.error("GitHub Issue 作成失敗: errorReportId={} message={}",
                    errorReportId, redactToken(e.getMessage()), e);
            throw new BusinessException(ErrorReportErrorCode.ERROR_REPORT_011);
        } finally {
            try {
                redisTemplate.delete(lockKey);
            } catch (Exception ignore) {
                log.warn("GitHub Issue ロック解除失敗: lockKey={}", lockKey);
            }
        }
    }

    /**
     * Issue タイトルを構築する。
     * 形式: {@code [SEVERITY] errorMessage（先頭100文字）}
     */
    String buildTitle(ErrorReportEntity r) {
        String msg = sanitizer.sanitize(r.getErrorMessage());
        if (msg == null) msg = "";
        String trimmed = msg.length() > TITLE_MESSAGE_MAX
                ? msg.substring(0, TITLE_MESSAGE_MAX) + "..."
                : msg;
        return String.format("[%s] %s", r.getSeverity(), trimmed);
    }

    /**
     * Issue 本文を構築する（PII 除去 + 外部 URL 縮約済み）。
     */
    String buildBody(ErrorReportEntity r, ErrorReportAiAnalysisEntity ai) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 概要\n");
        sb.append("- Severity: `").append(r.getSeverity()).append("`\n");
        sb.append("- Status: `").append(r.getStatus()).append("`\n");
        sb.append("- 累計発生: ").append(safeInt(r.getOccurrenceCount())).append("回\n");
        sb.append("- 影響ユーザー: ").append(safeInt(r.getAffectedUserCount())).append("名\n");
        sb.append("- 初回: ").append(r.getFirstOccurredAt()).append("\n");
        sb.append("- 最終: ").append(r.getLastOccurredAt()).append("\n\n");

        sb.append("## エラーメッセージ\n```\n")
                .append(sanitizer.sanitize(r.getErrorMessage())).append("\n```\n\n");

        sb.append("## 発生ページ\n`")
                .append(sanitizer.sanitizePagePath(r.getPageUrl())).append("`\n\n");

        if (r.getStackTrace() != null && !r.getStackTrace().isBlank()) {
            sb.append("## スタックトレース\n```\n")
                    .append(sanitizer.sanitize(r.getStackTrace())).append("\n```\n\n");
        }

        if (ai != null && "SUCCESS".equals(ai.getStatus())) {
            sb.append("## AI 分析（参考）\n");
            sb.append("**推定原因**: ").append(safeNonNull(stripExternalUrls(ai.getEstimatedCause()))).append("\n\n");
            sb.append("**修正案**: ").append(safeNonNull(stripExternalUrls(ai.getFixProposal()))).append("\n\n");
            sb.append("**影響評価**: ").append(safeNonNull(stripExternalUrls(ai.getImpactAssessment()))).append("\n\n");
            if (ai.getSuggestedFiles() != null && !ai.getSuggestedFiles().isBlank()) {
                sb.append("**関連ファイル候補**: ").append(ai.getSuggestedFiles()).append("\n\n");
            }
        }

        sb.append("## 内部管理画面\n");
        sb.append(appBaseUrl).append("/system-admin/error-reports/").append(r.getId()).append("\n");
        sb.append("\n---\n*この Issue は F12.5 エラーレポート機能から自動生成されました（PII 除去済み）。*");
        return sb.toString();
    }

    /**
     * Markdown リンク {@code [text](url)} のうち、自ドメイン（{@code app.base-url}）
     * 以外のリンクを {@code [text]} に縮約する。
     *
     * <p>NULL は NULL を返す。</p>
     */
    String stripExternalUrls(String text) {
        if (text == null) return null;

        String allowedHost = null;
        try {
            allowedHost = URI.create(appBaseUrl).getHost();
        } catch (Exception e) {
            log.warn("app-base-url のパースに失敗: {}", appBaseUrl);
        }

        Matcher m = MARKDOWN_LINK.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String linkText = m.group(1);
            String url = m.group(2);
            String replacement;
            try {
                String host = URI.create(url).getHost();
                if (host != null && allowedHost != null && host.equalsIgnoreCase(allowedHost)) {
                    // 自ドメインは保持
                    replacement = m.group(0);
                } else {
                    // 外部 URL は除去
                    replacement = "[" + linkText + "]";
                }
            } catch (Exception e) {
                replacement = "[" + linkText + "]";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 例外メッセージから token 値を念のため除去する（多重防御）。
     */
    private String redactToken(String message) {
        if (message == null || token == null || token.isBlank()) return message;
        return message.replace(token, "[REDACTED-GH-TOKEN]");
    }

    private String safeNonNull(String s) {
        return s != null ? s : "";
    }

    private int safeInt(Integer v) {
        return v != null ? v : 0;
    }
}
