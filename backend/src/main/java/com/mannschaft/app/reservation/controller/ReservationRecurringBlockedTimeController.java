package com.mannschaft.app.reservation.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.ReservationDayOfWeek;
import com.mannschaft.app.reservation.dto.CreateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeImpactResponse;
import com.mannschaft.app.reservation.dto.RecurringBlockedTimeResponse;
import com.mannschaft.app.reservation.dto.UpdateRecurringBlockedTimeRequest;
import com.mannschaft.app.reservation.service.ReservationRecurringBlockedTimeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * 定期予約不可枠コントローラー（F03.4.5 §4 W2-2）。
 *
 * <p>全 5 エンドポイントとも管理者（ADMIN / DEPUTY_ADMIN・role ベース）専用の self-gate
 * （{@code @PreAuthorize("@accessGuard.isScopeAdmin(...)")}・親 §6 方針・機能B/週間テンプレートと同一）。
 * IDOR: {@code ruleId} は {@code findByIdAndTeamId} 解決・他チームは 404（RESERVATION_051）で秘匿する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/reservation-recurring-blocked-times")
@Tag(name = "予約管理", description = "F03.4.5 定期予約不可枠（週次繰り返し）")
@RequiredArgsConstructor
public class ReservationRecurringBlockedTimeController {

    private final ReservationRecurringBlockedTimeService ruleService;

    /**
     * ルール一覧を取得する（曜日→開始時刻順）。
     */
    @GetMapping
    @Operation(summary = "定期予約不可枠一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<List<RecurringBlockedTimeResponse>>> listRules(
            @PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(ruleService.listRules(teamId)));
    }

    /**
     * ルールを作成する（上限50・409 ガード）。
     */
    @PostMapping
    @Operation(summary = "定期予約不可枠作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<RecurringBlockedTimeResponse>> createRule(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateRecurringBlockedTimeRequest request) {
        RecurringBlockedTimeResponse response =
                ruleService.createRule(teamId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ルールを部分更新する（null=据え置き・clearLineId・isActive 切替・409 ガード再検証）。
     */
    @PatchMapping("/{ruleId}")
    @Operation(summary = "定期予約不可枠更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<RecurringBlockedTimeResponse>> updateRule(
            @PathVariable Long teamId,
            @PathVariable UUID ruleId,
            @Valid @RequestBody UpdateRecurringBlockedTimeRequest request) {
        RecurringBlockedTimeResponse response =
                ruleService.updateRule(teamId, ruleId, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ルールを物理削除する（履歴価値なし。一時停止は isActive=FALSE で行う）。
     */
    @DeleteMapping("/{ruleId}")
    @Operation(summary = "定期予約不可枠削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<Void> deleteRule(
            @PathVariable Long teamId,
            @PathVariable UUID ruleId) {
        ruleService.deleteRule(teamId, ruleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    /**
     * 登録前の影響プレビュー（90日horizonのoverlap active予約・副作用ゼロ）。
     */
    @GetMapping("/impact")
    @Operation(summary = "定期予約不可枠 登録前の影響プレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #teamId, 'TEAM')")
    public ResponseEntity<ApiResponse<RecurringBlockedTimeImpactResponse>> getImpact(
            @PathVariable Long teamId,
            @RequestParam ReservationDayOfWeek dayOfWeek,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime endTime,
            @RequestParam(required = false) Long lineId) {
        RecurringBlockedTimeImpactResponse response =
                ruleService.getImpact(teamId, dayOfWeek, startTime, endTime, lineId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
