package com.mannschaft.app.survey.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.survey.dto.CreateQuestionRequest;
import com.mannschaft.app.survey.dto.QuestionResponse;
import com.mannschaft.app.survey.service.SurveyAccessGuard;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * アンケート設問コントローラー。設問の追加・削除APIを提供する。
 *
 * <p><b>認可</b>: 設問の追加・削除は {@link SurveyAccessGuard} で作成者または ADMIN+ に限定する。
 * 認可スコープはアンケート実体（{@code surveys.scope_type} / {@code scope_id}）由来で確定し、
 * パス変数のスコープと実体が一致しない場合は 404（存在秘匿）。</p>
 */
@RestController
@RequestMapping("/api/v1/{scopeType}/{scopeId}/surveys/{surveyId}/questions")
@Tag(name = "アンケート設問管理", description = "F05.4 アンケート設問の追加・削除")
@RequiredArgsConstructor
public class SurveyQuestionController {

    private final SurveyService surveyService;
    private final SurveyAccessGuard surveyAccessGuard;

    /**
     * 設問を追加する。
     *
     * <p>認可: 作成者または ADMIN+。</p>
     */
    @PostMapping
    @Operation(summary = "設問追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<QuestionResponse>> addQuestion(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long surveyId,
            @Valid @RequestBody CreateQuestionRequest request) {
        String canonicalScopeType = resolveScopeType(scopeType);
        surveyAccessGuard.checkCanManage(
                SecurityUtils.getCurrentUserId(), canonicalScopeType, scopeId, surveyId);
        QuestionResponse response = surveyService.addQuestion(canonicalScopeType, scopeId, surveyId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 設問を削除する。
     *
     * <p>認可: 作成者または ADMIN+。</p>
     */
    @DeleteMapping("/{questionId}")
    @Operation(summary = "設問削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteQuestion(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @PathVariable Long surveyId,
            @PathVariable Long questionId) {
        String canonicalScopeType = resolveScopeType(scopeType);
        surveyAccessGuard.checkCanManage(
                SecurityUtils.getCurrentUserId(), canonicalScopeType, scopeId, surveyId);
        surveyService.deleteQuestion(canonicalScopeType, scopeId, surveyId, questionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * URLパス語の scopeType を正準 enum 値の文字列に変換する。
     *
     * <p>URL パスでは複数形（"organizations" / "teams"）が使われるが、DB・Service 層は
     * 正準 enum 値（"ORGANIZATION" / "TEAM"）を期待する。兄弟の
     * {@code SurveyController#resolveScopeType} と同一の変換規則を用いることで、
     * アンケート実体の照合（{@code surveys.scope_type}）が成立する。</p>
     *
     * @param scopeType URLパス語（"organizations" または "teams"）
     * @return 正準値（"ORGANIZATION" または "TEAM"）
     * @throws ResponseStatusException 不明な scopeType の場合（HTTP 400）
     */
    private String resolveScopeType(String scopeType) {
        if ("organizations".equalsIgnoreCase(scopeType)) {
            return ScopeType.ORGANIZATION.name();
        } else if ("teams".equalsIgnoreCase(scopeType)) {
            return ScopeType.TEAM.name();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不明な scopeType: " + scopeType);
    }
}
