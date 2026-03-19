package com.mannschaft.app.membership;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.membership.dto.CheckinHistoryResponse;
import com.mannschaft.app.membership.dto.CheckinLocationResponse;
import com.mannschaft.app.membership.dto.CheckinStatsResponse;
import com.mannschaft.app.membership.dto.CreateCheckinLocationRequest;
import com.mannschaft.app.membership.dto.DeleteLocationResponse;
import com.mannschaft.app.membership.dto.LocationQrResponse;
import com.mannschaft.app.membership.dto.UpdateCheckinLocationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * チーム会員証コントローラー。チーム単位の会員証一覧・チェックイン履歴・拠点管理・統計APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}")
@Tag(name = "チームQR会員証", description = "F02.1 チーム会員証管理")
@RequiredArgsConstructor
public class TeamMemberCardController {

    private final MemberCardService memberCardService;
    private final CheckinLocationService locationService;
    private final CheckinStatsService statsService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * チームの会員証一覧を取得する。
     */
    @GetMapping("/member-cards")
    @Operation(summary = "チーム会員証一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<Map<String, Object>> getTeamMemberCards(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(required = false) String q) {
        CardStatus cardStatus = CardStatus.valueOf(status);
        return ResponseEntity.ok(memberCardService.getScopeMemberCards(
                ScopeType.TEAM, teamId, cardStatus, q));
    }

    /**
     * チーム全体のチェックイン履歴を取得する。
     */
    @GetMapping("/checkins")
    @Operation(summary = "チームチェックイン履歴")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CheckinHistoryResponse>>> getTeamCheckins(
            @PathVariable Long teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        return ResponseEntity.ok(memberCardService.getScopeCheckins(
                ScopeType.TEAM, teamId, from, to));
    }

    /**
     * チェックイン統計を取得する。
     */
    @GetMapping("/checkins/stats")
    @Operation(summary = "チェックイン統計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CheckinStatsResponse>> getCheckinStats(
            @PathVariable Long teamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(statsService.getStats(ScopeType.TEAM, teamId, from, to));
    }

    // ========== チェックイン拠点 ==========

    /**
     * セルフチェックイン拠点一覧を取得する。
     */
    @GetMapping("/checkin-locations")
    @Operation(summary = "拠点一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CheckinLocationResponse>>> getLocations(
            @PathVariable Long teamId) {
        return ResponseEntity.ok(locationService.getLocations(ScopeType.TEAM, teamId));
    }

    /**
     * セルフチェックイン拠点を作成する。
     */
    @PostMapping("/checkin-locations")
    @Operation(summary = "拠点作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CheckinLocationResponse>> createLocation(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateCheckinLocationRequest request) {
        ApiResponse<CheckinLocationResponse> response =
                locationService.createLocation(ScopeType.TEAM, teamId, request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * セルフチェックイン拠点を更新する。
     */
    @PutMapping("/checkin-locations/{locationId}")
    @Operation(summary = "拠点更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CheckinLocationResponse>> updateLocation(
            @PathVariable Long teamId,
            @PathVariable Long locationId,
            @Valid @RequestBody UpdateCheckinLocationRequest request) {
        return ResponseEntity.ok(locationService.updateLocation(
                ScopeType.TEAM, teamId, locationId, request));
    }

    /**
     * セルフチェックイン拠点を削除する（論理削除）。
     */
    @DeleteMapping("/checkin-locations/{locationId}")
    @Operation(summary = "拠点削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    public ResponseEntity<ApiResponse<DeleteLocationResponse>> deleteLocation(
            @PathVariable Long teamId,
            @PathVariable Long locationId) {
        return ResponseEntity.ok(locationService.deleteLocation(ScopeType.TEAM, teamId, locationId));
    }

    /**
     * 拠点QRコード（印刷用）を取得する。
     */
    @GetMapping("/checkin-locations/{locationId}/qr")
    @Operation(summary = "拠点QRコード取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<LocationQrResponse>> getLocationQr(
            @PathVariable Long teamId,
            @PathVariable Long locationId) {
        return ResponseEntity.ok(locationService.getLocationQr(ScopeType.TEAM, teamId, locationId));
    }
}
