package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.event.dto.EventRsvpRequest;
import com.mannschaft.app.event.dto.EventRsvpResponseDto;
import com.mannschaft.app.event.dto.EventRsvpSummaryResponse;
import com.mannschaft.app.event.service.EventRsvpService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * RSVP出欠回答コントローラー。
 * 組織・チームスコープのイベントRSVP機能を提供する。
 */
@RestController
@Tag(name = "RSVP出欠確認", description = "F03.8 Phase2 イベントRSVP出欠確認API")
@RequiredArgsConstructor
public class EventRsvpController {

    private final EventRsvpService rsvpService;

    // ================================================================
    // 組織スコープ
    // ================================================================

    /**
     * 組織イベントのRSVP回答一覧を取得する（管理者向け）。
     */
    @GetMapping("/api/v1/organizations/{orgId}/events/{eventId}/rsvp-responses")
    @Operation(summary = "組織イベントRSVP一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<EventRsvpResponseDto>>> listOrgRsvp(
            @PathVariable Long orgId,
            @PathVariable Long eventId) {
        List<EventRsvpResponseDto> list = rsvpService.getRsvpList(eventId);
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    /**
     * 組織イベントへRSVPを送信する（初回）。
     */
    @PostMapping("/api/v1/organizations/{orgId}/events/{eventId}/rsvp-responses")
    @Operation(summary = "組織イベントRSVP送信（初回）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<EventRsvpResponseDto>> submitOrgRsvp(
            @PathVariable Long orgId,
            @PathVariable Long eventId,
            @Valid @RequestBody EventRsvpRequest request) {
        EventRsvpResponseDto dto = rsvpService.submitRsvp(
                eventId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * 組織イベントのRSVPを更新する。
     */
    @PutMapping("/api/v1/organizations/{orgId}/events/{eventId}/rsvp-responses/me")
    @Operation(summary = "組織イベントRSVP更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<EventRsvpResponseDto>> updateOrgRsvp(
            @PathVariable Long orgId,
            @PathVariable Long eventId,
            @Valid @RequestBody EventRsvpRequest request) {
        EventRsvpResponseDto dto = rsvpService.updateRsvp(
                eventId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    /**
     * 組織イベントのRSVP集計を取得する。
     */
    @GetMapping("/api/v1/organizations/{orgId}/events/{eventId}/rsvp-responses/summary")
    @Operation(summary = "組織イベントRSVP集計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<EventRsvpSummaryResponse>> getOrgRsvpSummary(
            @PathVariable Long orgId,
            @PathVariable Long eventId) {
        EventRsvpSummaryResponse summary = rsvpService.getRsvpSummary(eventId);
        return ResponseEntity.ok(ApiResponse.of(summary));
    }

    // ================================================================
    // チームスコープ
    // ================================================================

    /**
     * チームイベントのRSVP回答一覧を取得する（管理者向け）。
     */
    @GetMapping("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses")
    @Operation(summary = "チームイベントRSVP一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<EventRsvpResponseDto>>> listTeamRsvp(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {
        List<EventRsvpResponseDto> list = rsvpService.getRsvpList(eventId);
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    /**
     * チームイベントへRSVPを送信する（初回）。
     */
    @PostMapping("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses")
    @Operation(summary = "チームイベントRSVP送信（初回）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "送信成功")
    public ResponseEntity<ApiResponse<EventRsvpResponseDto>> submitTeamRsvp(
            @PathVariable Long teamId,
            @PathVariable Long eventId,
            @Valid @RequestBody EventRsvpRequest request) {
        EventRsvpResponseDto dto = rsvpService.submitRsvp(
                eventId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(dto));
    }

    /**
     * チームイベントのRSVPを更新する。
     */
    @PutMapping("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/me")
    @Operation(summary = "チームイベントRSVP更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<EventRsvpResponseDto>> updateTeamRsvp(
            @PathVariable Long teamId,
            @PathVariable Long eventId,
            @Valid @RequestBody EventRsvpRequest request) {
        EventRsvpResponseDto dto = rsvpService.updateRsvp(
                eventId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(dto));
    }

    /**
     * チームイベントのRSVP集計を取得する。
     */
    @GetMapping("/api/v1/teams/{teamId}/events/{eventId}/rsvp-responses/summary")
    @Operation(summary = "チームイベントRSVP集計")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<EventRsvpSummaryResponse>> getTeamRsvpSummary(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {
        EventRsvpSummaryResponse summary = rsvpService.getRsvpSummary(eventId);
        return ResponseEntity.ok(ApiResponse.of(summary));
    }
}
