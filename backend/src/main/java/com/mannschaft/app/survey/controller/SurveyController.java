package com.mannschaft.app.survey.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.DuplicateSurveyRequest;
import com.mannschaft.app.survey.dto.ExtendDeadlineRequest;
import com.mannschaft.app.survey.dto.RespondentResponse;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.dto.SurveyStatsResponse;
import com.mannschaft.app.survey.dto.UpdateSurveyRequest;
import com.mannschaft.app.survey.service.SurveyResultService;
import com.mannschaft.app.survey.service.SurveyService;
import com.mannschaft.app.team.service.TeamService;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import com.mannschaft.app.common.SecurityUtils;

/**
 * アンケートコントローラー。アンケートのCRUD・ライフサイクルAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/surveys")
@Tag(name = "アンケート管理", description = "F05.4 アンケート・投票CRUD・ライフサイクル管理")
@RequiredArgsConstructor
public class SurveyController {

    private final SurveyService surveyService;
    private final SurveyResultService surveyResultService;
    private final OrganizationService organizationService;
    private final TeamService teamService;


    /**
     * アンケート一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "アンケート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SurveyResponse>> listSurveys(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        Page<SurveyResponse> result = surveyService.listSurveys(
                canonicalScopeType, resolvedScopeId, status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * アンケート詳細を取得する。
     */
    @GetMapping("/{surveyId}")
    @Operation(summary = "アンケート詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SurveyDetailResponse>> getSurvey(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable Long surveyId) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        SurveyDetailResponse response = surveyService.getSurveyDetail(canonicalScopeType, resolvedScopeId, surveyId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * アンケートを作成する。
     */
    @PostMapping
    @Operation(summary = "アンケート作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<SurveyDetailResponse>> createSurvey(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @Valid @RequestBody CreateSurveyRequest request) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        SurveyDetailResponse response = surveyService.createSurvey(
                canonicalScopeType, resolvedScopeId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * アンケートを更新する。
     */
    @PatchMapping("/{surveyId}")
    @Operation(summary = "アンケート更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<SurveyResponse>> updateSurvey(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable Long surveyId,
            @Valid @RequestBody UpdateSurveyRequest request) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        SurveyResponse response = surveyService.updateSurvey(canonicalScopeType, resolvedScopeId, surveyId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * アンケートを公開する。
     */
    @PostMapping("/{surveyId}/publish")
    @Operation(summary = "アンケート公開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "公開成功")
    public ResponseEntity<ApiResponse<SurveyResponse>> publishSurvey(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable Long surveyId) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        SurveyResponse response = surveyService.publishSurvey(canonicalScopeType, resolvedScopeId, surveyId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * アンケートを締め切る。
     */
    @PostMapping("/{surveyId}/close")
    @Operation(summary = "アンケート締め切り")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "締め切り成功")
    public ResponseEntity<ApiResponse<SurveyResponse>> closeSurvey(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable Long surveyId) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        SurveyResponse response = surveyService.closeSurvey(canonicalScopeType, resolvedScopeId, surveyId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * アンケートを削除する。
     */
    @DeleteMapping("/{surveyId}")
    @Operation(summary = "アンケート削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteSurvey(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable Long surveyId) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        surveyService.deleteSurvey(canonicalScopeType, resolvedScopeId, surveyId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 回答者一覧（未回答者を含む）を取得する。F05.4 §7.2 未回答者一覧の可視化。
     *
     * <p>認可は {@code unresponded_visibility} に応じて分岐する。詳細は
     * {@link SurveyResultService#getRespondents(Long, Long)} を参照。</p>
     */
    @GetMapping("/{surveyId}/respondents")
    @Operation(summary = "回答者一覧（未回答者含む）",
            description = "F05.4 §7.2 未回答者一覧。ALL_MEMBERS 公開時はメンバーも未回答者のみ閲覧可")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<RespondentResponse>>> getRespondents(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable Long surveyId) {
        List<RespondentResponse> respondents = surveyResultService.getRespondents(
                surveyId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(respondents));
    }

    /**
     * F05.4 §4.5 アンケート結果 CSV エクスポート。
     *
     * <p>ADMIN+ / 作成者 / 結果閲覧者が利用可能。匿名アンケートの場合は回答者列を「匿名」と表示し、
     * 5名未満の場合は集計サマリのみ返す（匿名性保証）。</p>
     */
    @GetMapping(value = "/{surveyId}/results/export", produces = "text/csv; charset=UTF-8")
    @Operation(summary = "アンケート結果 CSV エクスポート",
            description = "F05.4 §4.5 集計結果と全回答の生データを CSV で返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<byte[]> exportResults(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable Long surveyId) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        byte[] csv = surveyResultService.exportResultsCsv(
                canonicalScopeType, resolvedScopeId, surveyId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"survey_" + surveyId + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(csv);
    }

    /**
     * F05.4 §4.6 アンケート複製。
     *
     * <p>DRAFT 状態でアンケートを複製する。設問・選択肢・配信対象・結果閲覧者をコピーし、
     * 回答データ・状態・日時はリセットする。タイトル末尾に「（コピー）」を付与する。</p>
     */
    @PostMapping("/{surveyId}/duplicate")
    @Operation(summary = "アンケート複製",
            description = "F05.4 §4.6 既存アンケートを DRAFT としてコピーする")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<SurveyDetailResponse>> duplicateSurvey(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable Long surveyId,
            @RequestBody(required = false) DuplicateSurveyRequest request) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        SurveyDetailResponse response = surveyService.duplicateSurvey(
                canonicalScopeType, resolvedScopeId, surveyId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * F05.4 §4.7 アンケート締切延長。
     *
     * <p>公開中アンケートの締切を延長する。短縮は不可。延長後は受信者に通知を送信する。</p>
     */
    @PostMapping("/{surveyId}/extend")
    @Operation(summary = "アンケート締切延長",
            description = "F05.4 §4.7 回答期限を延長する。短縮は不可")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "延長成功")
    public ResponseEntity<ApiResponse<SurveyResponse>> extendDeadline(
            @PathVariable String scopeType,
            @PathVariable String scopeId,
            @PathVariable Long surveyId,
            @Valid @RequestBody ExtendDeadlineRequest request) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        SurveyResponse response = surveyService.extendDeadline(
                canonicalScopeType, resolvedScopeId, surveyId, request.getNewDeadline(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * アンケート統計を取得する。
     */
    @GetMapping("/stats")
    @Operation(summary = "アンケート統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SurveyStatsResponse>> getStats(
            @PathVariable String scopeType,
            @PathVariable String scopeId) {
        String canonicalScopeType = resolveScopeType(scopeType);
        Long resolvedScopeId = resolveScopeId(scopeType, scopeId);
        SurveyStatsResponse response = surveyService.getStats(canonicalScopeType, resolvedScopeId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * URLパス語の scopeType を正準 enum 値の文字列に変換する。
     *
     * <p>URL パスでは複数形（"organizations" / "teams"）が使われるが、
     * DB・Service 層では正準 enum 値（"ORGANIZATION" / "TEAM"）を期待する。
     * 本メソッドで変換することで、汚染値が保存されるのを防ぐ。</p>
     *
     * @param scopeType URLパス語（"organizations" または "teams"）
     * @return 正準値（"ORGANIZATION" または "TEAM"）
     * @throws IllegalArgumentException 不明な scopeType の場合
     */
    private String resolveScopeType(String scopeType) {
        if ("organizations".equalsIgnoreCase(scopeType)) {
            return ScopeType.ORGANIZATION.name();
        } else if ("teams".equalsIgnoreCase(scopeType)) {
            return ScopeType.TEAM.name();
        }
        throw new IllegalArgumentException("不明な scopeType: " + scopeType);
    }

    /**
     * scopeType と scopeId（スラッグ文字列）から内部 BIGINT ID を解決する。
     *
     * <p>slug 形式のスコープIDを、scopeType に応じて
     * OrganizationService または TeamService 経由で内部 ID に変換する。</p>
     *
     * @param scopeType "organizations" または "teams"（URLパス語）
     * @param scopeId   スラッグ文字列
     * @return 内部 BIGINT ID
     */
    private Long resolveScopeId(String scopeType, String scopeId) {
        if ("organizations".equalsIgnoreCase(scopeType)) {
            return organizationService.resolveOrgId(scopeId);
        } else if ("teams".equalsIgnoreCase(scopeType)) {
            return teamService.resolveTeamId(scopeId);
        }
        throw new IllegalArgumentException("不明な scopeType: " + scopeType);
    }
}
