package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.dto.CreateEventRequest;
import com.mannschaft.app.event.dto.EventDetailResponse;
import com.mannschaft.app.event.dto.EventResponse;
import com.mannschaft.app.event.dto.EventStatsResponse;
import com.mannschaft.app.event.dto.UpdateEventRequest;
import com.mannschaft.app.event.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * チームイベントコントローラー。チームスコープのイベントCRUD・ステータス管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/events")
@Tag(name = "チームイベント管理", description = "F03.8 チームスコープのイベントCRUD・ステータス管理")
@RequiredArgsConstructor
public class TeamEventController {

    private final EventService eventService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * チームのイベント一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チームイベント一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<EventResponse>> listEvents(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<EventResponse> result = eventService.listEvents(
                EventScopeType.TEAM, teamId, status, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * イベント詳細を取得する。
     */
    @GetMapping("/{eventId}")
    @Operation(summary = "イベント詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<EventDetailResponse>> getEvent(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {
        EventDetailResponse response = eventService.getEvent(eventId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * イベントを作成する。
     */
    @PostMapping
    @Operation(summary = "イベント作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<EventDetailResponse>> createEvent(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateEventRequest request) {
        EventDetailResponse response = eventService.createEvent(
                EventScopeType.TEAM, teamId, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * イベントを更新する。
     */
    @PatchMapping("/{eventId}")
    @Operation(summary = "イベント更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<EventDetailResponse>> updateEvent(
            @PathVariable Long teamId,
            @PathVariable Long eventId,
            @Valid @RequestBody UpdateEventRequest request) {
        EventDetailResponse response = eventService.updateEvent(eventId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * イベントを公開する。
     */
    @PostMapping("/{eventId}/publish")
    @Operation(summary = "イベント公開")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "公開成功")
    public ResponseEntity<ApiResponse<EventDetailResponse>> publishEvent(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {
        EventDetailResponse response = eventService.publishEvent(eventId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 参加登録を開始する。
     */
    @PostMapping("/{eventId}/open-registration")
    @Operation(summary = "参加登録開始")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "開始成功")
    public ResponseEntity<ApiResponse<EventDetailResponse>> openRegistration(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {
        EventDetailResponse response = eventService.openRegistration(eventId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 参加登録を締め切る。
     */
    @PostMapping("/{eventId}/close-registration")
    @Operation(summary = "参加登録締切")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "締切成功")
    public ResponseEntity<ApiResponse<EventDetailResponse>> closeRegistration(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {
        EventDetailResponse response = eventService.closeRegistration(eventId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * イベントをキャンセルする。
     */
    @PostMapping("/{eventId}/cancel")
    @Operation(summary = "イベントキャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<EventDetailResponse>> cancelEvent(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {
        EventDetailResponse response = eventService.cancelEvent(eventId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * イベントを削除する。
     */
    @DeleteMapping("/{eventId}")
    @Operation(summary = "イベント削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteEvent(
            @PathVariable Long teamId,
            @PathVariable Long eventId) {
        eventService.deleteEvent(eventId);
        return ResponseEntity.noContent().build();
    }
}
