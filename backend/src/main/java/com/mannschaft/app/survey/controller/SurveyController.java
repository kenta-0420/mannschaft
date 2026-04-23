package com.mannschaft.app.survey.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.survey.dto.CreateSurveyRequest;
import com.mannschaft.app.survey.dto.RespondentResponse;
import com.mannschaft.app.survey.dto.SurveyDetailResponse;
import com.mannschaft.app.survey.dto.SurveyResponse;
import com.mannschaft.app.survey.dto.SurveyStatsResponse;
import com.mannschaft.app.survey.dto.UpdateSurveyRequest;
import com.mannschaft.app.survey.service.SurveyResultService;
import com.mannschaft.app.survey.service.SurveyService;

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


    /**
     * アンケート一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "アンケート一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<SurveyResponse>> listSurveys(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SurveyResponse> result = surveyService.listSurveys(
                scopeType, scopeId, status, PageRequest.of(page, size));
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
            @PathVariable Long scopeId,
            @PathVariable Long surveyId) {
        SurveyDetailResponse response = surveyService.getSurveyDetail(scopeType, scopeId, surveyId);
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
            @PathVariable Long scopeId,
            @Valid @RequestBody CreateSurveyRequest request) {
        SurveyDetailResponse response = surveyService.createSurvey(
                scopeType, scopeId, SecurityUtils.getCurrentUserId(), request);
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
            @PathVariable Long scopeId,
            @PathVariable Long surveyId,
            @Valid @RequestBody UpdateSurveyRequest request) {
        SurveyResponse response = surveyService.updateSurvey(scopeType, scopeId, surveyId, request);
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
            @PathVariable Long scopeId,
            @PathVariable Long surveyId) {
        SurveyResponse response = surveyService.publishSurvey(scopeType, scopeId, surveyId);
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
            @PathVariable Long scopeId,
            @PathVariable Long surveyId) {
        SurveyResponse response = surveyService.closeSurvey(scopeType, scopeId, surveyId);
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
            @PathVariable Long scopeId,
            @PathVariable Long surveyId) {
        surveyService.deleteSurvey(scopeType, scopeId, surveyId);
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
            @PathVariable Long scopeId,
            @PathVariable Long surveyId) {
        List<RespondentResponse> respondents = surveyResultService.getRespondents(
                surveyId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(respondents));
    }

    /**
     * アンケート統計を取得する。
     */
    @GetMapping("/stats")
    @Operation(summary = "アンケート統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SurveyStatsResponse>> getStats(
            @PathVariable String scopeType,
            @PathVariable Long scopeId) {
        SurveyStatsResponse response = surveyService.getStats(scopeType, scopeId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
