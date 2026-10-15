package com.mannschaft.app.admin.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.admin.config.GitHubProperties;
import com.mannschaft.app.admin.dto.SecurityScanStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;

/**
 * GitHub Actions OWASP Dependency-Check スキャン状態取得サービス。
 *
 * <p>GitHub Actions API（{@code /repos/{owner}/{repo}/actions/workflows/security-scan.yml/runs}）
 * をプロキシし、最新の実行状態を返す。API 失敗・タイムアウト時は conclusion="UNKNOWN" を返す。</p>
 */
@Slf4j
@Service
public class SecurityScanStatusService {

    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final String WORKFLOW_FILE = "security-scan.yml";
    private static final String CONCLUSION_UNKNOWN = "UNKNOWN";
    private static final String CONCLUSION_IN_PROGRESS = "IN_PROGRESS";

    private final GitHubProperties gitHubProperties;
    private final RestTemplate restTemplate;

    public SecurityScanStatusService(
            GitHubProperties gitHubProperties,
            @Qualifier("gitHubRestTemplate") RestTemplate restTemplate) {
        this.gitHubProperties = gitHubProperties;
        this.restTemplate = restTemplate;
    }

    /**
     * OWASP Dependency-Check スキャンの最新実行状態を取得する。
     *
     * <p>GitHub API が失敗した場合はエラーログを出した上で conclusion="UNKNOWN" を返す。
     * 例外は外部に伝播させない（UNKNOWN による安全なフォールバック）。</p>
     *
     * @return セキュリティスキャン状態
     */
    public SecurityScanStatusResponse getStatus() {
        try {
            String url = buildRunsUrl();
            HttpEntity<Void> entity = buildRequestEntity();

            ResponseEntity<WorkflowRunsResponse> response =
                    restTemplate.exchange(url, HttpMethod.GET, entity, WorkflowRunsResponse.class);

            WorkflowRunsResponse body = response.getBody();
            if (body == null || body.workflowRuns() == null || body.workflowRuns().isEmpty()) {
                log.info("SecurityScanStatusService: No workflow runs found for {}", WORKFLOW_FILE);
                return new SecurityScanStatusResponse(CONCLUSION_UNKNOWN, null, null);
            }

            WorkflowRun latestRun = body.workflowRuns().get(0);
            String conclusion = resolveConclusion(latestRun);
            String runUrl = latestRun.htmlUrl();
            Instant runAt = latestRun.createdAt() != null ? Instant.parse(latestRun.createdAt()) : null;

            return new SecurityScanStatusResponse(conclusion, runUrl, runAt);

        } catch (RestClientException e) {
            log.error("SecurityScanStatusService: GitHub API call failed. owner={}, repo={}, error={}",
                    gitHubProperties.owner(), gitHubProperties.repo(), e.getMessage());
            return new SecurityScanStatusResponse(CONCLUSION_UNKNOWN, null, null);
        } catch (Exception e) {
            log.error("SecurityScanStatusService: Unexpected error fetching scan status", e);
            return new SecurityScanStatusResponse(CONCLUSION_UNKNOWN, null, null);
        }
    }

    private String buildRunsUrl() {
        return String.format("%s/repos/%s/%s/actions/workflows/%s/runs?per_page=1",
                GITHUB_API_BASE,
                gitHubProperties.owner(),
                gitHubProperties.repo(),
                WORKFLOW_FILE);
    }

    private HttpEntity<Void> buildRequestEntity() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
        if (StringUtils.hasText(gitHubProperties.token())) {
            headers.set("Authorization", "Bearer " + gitHubProperties.token());
        }
        return new HttpEntity<>(headers);
    }

    /**
     * ワークフロー実行の結論を正規化する。
     *
     * <p>実行中（conclusion=null かつ status=in_progress/queued/waiting）は "IN_PROGRESS" を返す。</p>
     */
    private String resolveConclusion(WorkflowRun run) {
        if (run.conclusion() == null) {
            // 実行中または待機中
            return CONCLUSION_IN_PROGRESS;
        }
        return switch (run.conclusion().toLowerCase()) {
            case "success" -> "SUCCESS";
            case "failure" -> "FAILURE";
            case "cancelled", "timed_out", "action_required", "skipped", "stale", "neutral" -> "FAILURE";
            default -> CONCLUSION_UNKNOWN;
        };
    }

    // ---- GitHub API レスポンスのデシリアライゼーション用 inner record ----

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkflowRunsResponse(
            @JsonProperty("workflow_runs") List<WorkflowRun> workflowRuns
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record WorkflowRun(
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("conclusion") String conclusion,
            @JsonProperty("status") String status,
            @JsonProperty("created_at") String createdAt
    ) {}
}
