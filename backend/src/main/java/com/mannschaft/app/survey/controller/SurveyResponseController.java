package com.mannschaft.app.survey.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.survey.dto.SubmitResponseRequest;
import com.mannschaft.app.survey.dto.SurveyResponseEntry;
import com.mannschaft.app.survey.service.SurveyResponseService;
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

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * アンケート回答コントローラー。回答の送信・取得APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/surveys/{surveyId}/responses")
@Tag(name = "アンケート回答管理", description = "F05.4 アンケート回答の送信・取得")
@RequiredArgsConstructor
public class SurveyResponseController {

    private final SurveyResponseService responseService;


    /**
     * アンケートに回答を送信する。
     */
    @PostMapping
    @Operation(summary = "回答送信")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<List<SurveyResponseEntry>>> submitResponse(
            @PathVariable Long surveyId,
            @Valid @RequestBody SubmitResponseRequest request) {
        List<SurveyResponseEntry> responses = responseService.submitResponse(
                surveyId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(responses));
    }

    /**
     * 自分の回答を取得する。
     */
    @GetMapping("/me")
    @Operation(summary = "自分の回答取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<SurveyResponseEntry>>> getMyResponses(
            @PathVariable Long surveyId) {
        List<SurveyResponseEntry> responses = responseService.getMyResponses(
                surveyId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
