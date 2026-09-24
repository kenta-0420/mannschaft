package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.shift.dto.AvailabilityDefaultResponse;
import com.mannschaft.app.shift.dto.BulkAvailabilityDefaultRequest;
import com.mannschaft.app.shift.dto.CreateHourlyRateRequest;
import com.mannschaft.app.shift.dto.HourlyRateResponse;
import com.mannschaft.app.shift.service.ShiftAvailabilityService;
import com.mannschaft.app.shift.service.ShiftHourlyRateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * シフト勤務可能時間・時給コントローラー。デフォルト勤務可能時間と時給設定APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/shifts")
@Tag(name = "シフト勤務可能時間・時給管理", description = "F03.5 デフォルト勤務可能時間と時給設定")
@RequiredArgsConstructor
public class ShiftAvailabilityController {

    private final ShiftAvailabilityService availabilityService;
    private final ShiftHourlyRateService hourlyRateService;

    /**
     * デフォルト勤務可能時間を取得する。
     *
     * <p><b>認可:</b> {@code teamId} は絞り込みだけでなく所属検証の対象でもある。
     * per-scope 認可は {@code ShiftAvailabilityService} 内で強制する
     * （SYSTEM_ADMIN + 当該チーム ADMIN 以上 + 当該チームの通常メンバー（SUPPORTER 除く）のみ）。違反時は 403。</p>
     */
    @GetMapping("/availability")
    @Operation(summary = "デフォルト勤務可能時間取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AvailabilityDefaultResponse>>> getAvailabilityDefaults(
            @RequestParam Long teamId) {
        List<AvailabilityDefaultResponse> responses = availabilityService
                .getAvailabilityDefaults(SecurityUtils.getCurrentUserId(), teamId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * デフォルト勤務可能時間を一括設定する。
     *
     * <p><b>認可:</b> {@code teamId} は絞り込みだけでなく所属検証の対象でもある。
     * per-scope 認可は {@code ShiftAvailabilityService} 内で強制する
     * （SYSTEM_ADMIN + 当該チーム ADMIN 以上 + 当該チームの通常メンバー（SUPPORTER 除く）のみ）。違反時は 403。</p>
     */
    @PutMapping("/availability")
    @Operation(summary = "デフォルト勤務可能時間一括設定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "設定成功")
    public ResponseEntity<ApiResponse<List<AvailabilityDefaultResponse>>> setAvailabilityDefaults(
            @RequestParam Long teamId,
            @Valid @RequestBody BulkAvailabilityDefaultRequest request) {
        List<AvailabilityDefaultResponse> responses = availabilityService
                .setAvailabilityDefaults(SecurityUtils.getCurrentUserId(), teamId, request);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * デフォルト勤務可能時間を削除する。
     *
     * <p><b>認可:</b> {@code teamId} は絞り込みだけでなく所属検証の対象でもある。
     * per-scope 認可は {@code ShiftAvailabilityService} 内で強制する
     * （SYSTEM_ADMIN + 当該チーム ADMIN 以上 + 当該チームの通常メンバー（SUPPORTER 除く）のみ）。違反時は 403。</p>
     */
    @DeleteMapping("/availability")
    @Operation(summary = "デフォルト勤務可能時間削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteAvailabilityDefaults(
            @RequestParam Long teamId) {
        availabilityService.deleteAvailabilityDefaults(SecurityUtils.getCurrentUserId(), teamId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 時給を設定する。
     *
     * <p><b>認可（認可根治 Wave6 追加戦）:</b> 呼び出し元の身元は
     * {@link SecurityUtils#getCurrentUserId()} から採り、per-scope 認可は
     * {@code ShiftHourlyRateService} 内で強制する（本人 + 当該チーム ADMIN/DEPUTY_ADMIN のみ。
     * 対象ユーザーも当該チームのメンバーであることを要求する）。違反時は 403。</p>
     */
    @PostMapping("/hourly-rate")
    @Operation(summary = "時給設定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "設定成功")
    public ResponseEntity<ApiResponse<HourlyRateResponse>> createHourlyRate(
            @RequestParam Long teamId,
            @Valid @RequestBody CreateHourlyRateRequest request) {
        HourlyRateResponse response = hourlyRateService
                .createHourlyRate(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 時給履歴を取得する。
     *
     * <p><b>認可（認可根治 Wave6 追加戦）:</b> 呼び出し元の身元は
     * {@link SecurityUtils#getCurrentUserId()} から採り、per-scope 認可は
     * {@code ShiftHourlyRateService} 内で強制する（本人 + 当該チーム ADMIN/DEPUTY_ADMIN のみ。
     * F03.5 設計書「他メンバーの時給は非公開」に準拠）。違反時は 403。</p>
     */
    @GetMapping("/hourly-rate")
    @Operation(summary = "時給履歴取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<HourlyRateResponse>>> listHourlyRates(
            @RequestParam Long teamId,
            @RequestParam Long userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (date != null) {
            HourlyRateResponse rate = hourlyRateService.getEffectiveRate(userId, teamId, date, currentUserId);
            return ResponseEntity.ok(ApiResponse.of(rate != null ? List.of(rate) : List.of()));
        }
        List<HourlyRateResponse> responses = hourlyRateService.listHourlyRates(userId, teamId, currentUserId);
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * チーム全員ぶんの、基準日時点で有効な時給を一括取得する（CMP-260912-1525）。
     *
     * <p>時給設定画面が全メンバーの現在時給を並べるための経路。1 人ずつ
     * {@code GET /hourly-rate} を引くと人数ぶんの往復になるため、1 リクエストにまとめる。</p>
     *
     * <p><b>認可:</b> 他メンバーの時給を必ず含むため、当該チームの
     * ADMIN/DEPUTY_ADMIN（または SYSTEM_ADMIN）のみ許可する。per-scope 認可は
     * {@code ShiftHourlyRateService#listEffectiveRatesForTeam} 内で強制する。違反時は 403。</p>
     */
    @GetMapping("/hourly-rates")
    @Operation(summary = "チーム時給一括取得（ADMIN/DEPUTY_ADMIN のみ）",
            description = "基準日時点で有効な時給を、チーム全員ぶん 1 レスポンスで返す。"
                    + "時給が未設定のメンバーは含まれない。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403",
            description = "当該チームの ADMIN/DEPUTY_ADMIN でない")
    public ResponseEntity<ApiResponse<List<HourlyRateResponse>>> listTeamEffectiveHourlyRates(
            @RequestParam Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        List<HourlyRateResponse> responses = hourlyRateService
                .listEffectiveRatesForTeam(teamId, date, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }
}
