package com.mannschaft.app.survey.controller;

import com.mannschaft.app.bulletin.dto.ThreadResponse;
import com.mannschaft.app.bulletin.service.SurveyBulletinThreadService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * アンケート専用掲示板スレッドコントローラー。
 *
 * <p>アンケートに紐付いた専用掲示板スレッドを取得するAPIを提供する。
 * スレッドはアンケート作成時に自動生成される（{@link com.mannschaft.app.survey.listener.SurveyBulletinThreadListener}）。</p>
 */
@RestController
@RequestMapping("/api/v1/surveys")
@Tag(name = "アンケートスレッド", description = "F05.4 アンケート専用掲示板スレッド取得")
@RequiredArgsConstructor
public class SurveyThreadController {

    private final SurveyBulletinThreadService surveyBulletinThreadService;

    /**
     * アンケートに紐付いた掲示板スレッドを取得する。
     *
     * <p>アンケート作成時に自動生成されたスレッドを返す。
     * スレッドが存在しない場合は 404 を返す。</p>
     *
     * @param surveyId アンケートID
     * @return スレッドレスポンス
     */
    @GetMapping("/{surveyId}/thread")
    @Operation(summary = "アンケート専用スレッド取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "スレッド未存在")
    public ResponseEntity<ApiResponse<ThreadResponse>> getSurveyThread(
            @PathVariable Long surveyId) {
        return surveyBulletinThreadService
                .findThreadResponseBySurveyId(surveyId, SecurityUtils.getCurrentUserId())
                .map(thread -> ResponseEntity.ok(ApiResponse.of(thread)))
                .orElse(ResponseEntity.notFound().build());
    }
}
