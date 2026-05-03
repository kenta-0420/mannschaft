package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.TransitionAlertListResponse;
import com.mannschaft.app.school.dto.TransitionAlertResolveRequest;
import com.mannschaft.app.school.dto.TransitionAlertResponse;
import com.mannschaft.app.school.service.TransitionAlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** F03.13 学校出欠: 移動検知アラートエンドポイント。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class TransitionAlertController {

    private final TransitionAlertService transitionAlertService;

    /**
     * 指定クラス・日付の移動検知アラート一覧を取得する。
     *
     * @param teamId         クラスチームID
     * @param date           対象日（YYYY-MM-DD）
     * @param unresolvedOnly true の場合は未解決のみ取得（デフォルト: false）
     * @return 移動検知アラート一覧レスポンス
     */
    @GetMapping("/teams/{teamId}/attendance/transition-alerts")
    @Operation(
            summary = "移動検知アラート一覧取得",
            description = "指定日のクラスの移動検知アラート一覧を取得する。unresolvedOnly=true で未解決のみに絞り込み可能。"
    )
    public ApiResponse<TransitionAlertListResponse> getAlerts(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "false") boolean unresolvedOnly) {
        return ApiResponse.of(transitionAlertService.getAlerts(teamId, date, unresolvedOnly));
    }

    /**
     * 移動検知アラートを解決済みにする。
     *
     * @param teamId  クラスチームID
     * @param alertId アラートID
     * @param request 解決リクエスト（解決理由を含む）
     * @return 更新後のアラートレスポンス
     */
    @PostMapping("/teams/{teamId}/attendance/transition-alerts/{alertId}/resolve")
    @Operation(
            summary = "移動検知アラート解決",
            description = "担任・保護者がアラートを解決済みにする。解決理由の入力が必須。"
    )
    public ApiResponse<TransitionAlertResponse> resolveAlert(
            @PathVariable Long teamId,
            @PathVariable Long alertId,
            @Valid @RequestBody TransitionAlertResolveRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(transitionAlertService.resolveAlert(alertId, currentUserId, request.getNote()));
    }
}
