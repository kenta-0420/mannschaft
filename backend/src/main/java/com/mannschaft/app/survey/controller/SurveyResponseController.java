package com.mannschaft.app.survey.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.survey.dto.SubmitResponseRequest;
import com.mannschaft.app.survey.dto.SurveyResponseEntry;
import com.mannschaft.app.survey.dto.UserResponseDetailResponse;
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
     * F05.4 §4.8 指定ユーザーの個別回答取得。
     *
     * <p>非匿名アンケート専用。ADMIN+ / 作成者 / {@code survey_result_viewers} 登録者のみ閲覧可。
     * 匿名アンケートの場合は 403 を返す。</p>
     */
    @GetMapping("/{userId}")
    @Operation(summary = "指定ユーザーの個別回答取得",
            description = "F05.4 §4.8 特定ユーザーの回答詳細を取得（非匿名のみ）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<UserResponseDetailResponse>> getResponseByUser(
            @PathVariable Long surveyId,
            @PathVariable Long userId) {
        UserResponseDetailResponse response = responseService.getResponseByUser(
                surveyId, userId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 自分の回答を取得する。
     *
     * <p><b>認可（{@link AuthorizedInService} 付与の根拠・認可根治戦役 Wave7 監査済）</b>:
     * 本 EP は<b>自己スコープ</b>で閉じている。閲覧対象ユーザーはリクエストから受け取らず、
     * サーバ側で確定した {@link SecurityUtils#getCurrentUserId()} を
     * {@code SurveyResponseService#getMyResponses(Long, Long)} の検索条件
     * （{@code findBySurveyIdAndUserId}）に固定して渡すため、他人の回答行は構造上取得できない。
     * 未ログインは {@code SecurityUtils.getCurrentUserId()} が 401 を投げる。
     * 他ユーザーの回答を引く経路は別 EP（{@link #getResponseByUser}）として分離されており、
     * そちらは ADMIN+ / 作成者 / 結果閲覧者のみに限定されている。
     * データ依存でない構造的な自己スコープ認可のため白名簿クラス呼び出しを持たず、
     * 本マーカーで監査済であることを明示する。</p>
     */
    @GetMapping("/me")
    @Operation(summary = "自分の回答取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @AuthorizedInService
    public ResponseEntity<ApiResponse<List<SurveyResponseEntry>>> getMyResponses(
            @PathVariable Long surveyId) {
        List<SurveyResponseEntry> responses = responseService.getMyResponses(
                surveyId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
