package com.mannschaft.app.survey.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.survey.dto.CreateQuestionRequest;
import com.mannschaft.app.survey.dto.QuestionResponse;
import com.mannschaft.app.survey.service.SurveyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * アンケート設問コントローラー。設問の追加・削除APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/surveys/{surveyId}/questions")
@Tag(name = "アンケート設問管理", description = "F05.4 アンケート設問の追加・削除")
@RequiredArgsConstructor
public class SurveyQuestionController {

    private final SurveyService surveyService;

    /**
     * 設問を追加する。
     */
    @PostMapping
    @Operation(summary = "設問追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<QuestionResponse>> addQuestion(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long surveyId,
            @Valid @RequestBody CreateQuestionRequest request) {
        QuestionResponse response = surveyService.addQuestion(scopeType, scopeId, surveyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 設問を削除する。
     */
    @DeleteMapping("/{questionId}")
    @Operation(summary = "設問削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteQuestion(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long surveyId,
            @PathVariable Long questionId) {
        surveyService.deleteQuestion(scopeType, scopeId, surveyId, questionId);
        return ResponseEntity.noContent().build();
    }
}
