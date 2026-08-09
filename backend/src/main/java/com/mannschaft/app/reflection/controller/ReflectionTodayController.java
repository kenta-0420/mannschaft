package com.mannschaft.app.reflection.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.reflection.dto.ReflectionTodayResponse;
import com.mannschaft.app.reflection.service.ReflectionTodayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * F06.5 今日の振り返りビューコントローラー（§4.3 / §7 #12）。
 */
@RestController
@RequestMapping("/api/v1/me/reflections")
@Tag(name = "今日の振り返り", description = "F06.5 アクティブリコール学習機能 — 今日の振り返りビュー")
@RequiredArgsConstructor
public class ReflectionTodayController {

    private final ReflectionTodayService reflectionTodayService;

    /**
     * 今日の振り返りビュー（§7 #12・全コラム縦並び）。
     *
     * @param date 対象日（省略時はサーバがユーザー TZ の今日を採用・§4.3）
     */
    @SelfScopedEndpoint("対象は SecurityUtils.getCurrentUserId() で確定した認証主体固定"
            + "（ReflectionTodayService#getToday）")
    @GetMapping("/today")
    @Operation(summary = "今日の振り返りビュー取得")
    public ResponseEntity<ApiResponse<ReflectionTodayResponse>> getToday(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ReflectionTodayResponse result =
                reflectionTodayService.getToday(SecurityUtils.getCurrentUserId(), date);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
