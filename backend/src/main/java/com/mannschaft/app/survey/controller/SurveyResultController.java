package com.mannschaft.app.survey.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.survey.dto.AddResultViewersRequest;
import com.mannschaft.app.survey.dto.AddTargetsRequest;
import com.mannschaft.app.survey.dto.RemindResponse;
import com.mannschaft.app.survey.dto.SurveyResultResponse;
import com.mannschaft.app.survey.service.SurveyResultService;
import com.mannschaft.app.survey.service.SurveyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * アンケート結果・配信管理コントローラー。結果閲覧・配信対象・閲覧者管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/surveys/{surveyId}")
@Tag(name = "アンケート結果・配信管理", description = "F05.4 結果集計・配信対象・閲覧者管理")
@RequiredArgsConstructor
public class SurveyResultController {

    private final SurveyResultService resultService;
    private final SurveyService surveyService;


    /**
     * アンケート結果を取得する。
     */
    @GetMapping("/results")
    @Operation(summary = "アンケート結果取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<SurveyResultResponse>> getResults(
            @PathVariable Long surveyId) {
        SurveyResultResponse response = resultService.getResults(surveyId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 配信対象を追加する。
     */
    @PostMapping("/targets")
    @Operation(summary = "配信対象追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<Void> addTargets(
            @PathVariable Long surveyId,
            @Valid @RequestBody AddTargetsRequest request) {
        surveyService.addTargets(surveyId, request.getUserIds());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * 結果閲覧者を追加する。
     */
    @PostMapping("/result-viewers")
    @Operation(summary = "結果閲覧者追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<Void> addResultViewers(
            @PathVariable Long surveyId,
            @Valid @RequestBody AddResultViewersRequest request) {
        surveyService.addResultViewers(surveyId, request.getUserIds());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /**
     * F05.4 督促 API。未回答者へリマインド通知を送信する。
     *
     * <p>認可は作成者または ADMIN+。状態は PUBLISHED のみ受付。
     * 24 時間クールダウンと最大 3 回の上限制約あり。詳細は
     * {@link SurveyService#remind(Long, Long)} を参照。</p>
     *
     * <p>例外（403 / 400 / 404）の HTTP マッピングは GlobalExceptionHandler に委譲する。</p>
     */
    @PostMapping("/remind")
    @Operation(summary = "アンケート督促送信",
            description = "F05.4 §289-292 未回答者へリマインド通知を一括送信する（最大3回・24h クールダウン）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "送信成功")
    public ResponseEntity<ApiResponse<RemindResponse>> remind(
            @PathVariable Long surveyId) {
        RemindResponse response = surveyService.remind(surveyId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
