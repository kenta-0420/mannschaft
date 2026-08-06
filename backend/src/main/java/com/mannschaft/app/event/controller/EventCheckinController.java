package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.event.dto.CheckinRequest;
import com.mannschaft.app.event.dto.CheckinResponse;
import com.mannschaft.app.event.dto.SelfCheckinRequest;
import com.mannschaft.app.event.service.EventCheckinService;
import com.mannschaft.app.event.service.EventScopeAccessGuard;
import com.mannschaft.app.common.security.AuthorizedInService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * イベントチェックインコントローラー。QRスキャン・セルフチェックインAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "イベントチェックイン", description = "F03.8 チェックイン管理")
@RequiredArgsConstructor
public class EventCheckinController {

    private final EventCheckinService checkinService;
    private final EventScopeAccessGuard eventScopeAccessGuard;

    /**
     * スタッフスキャンによるチェックインを実行する（ADMIN/DEPUTY_ADMIN専用）。
     *
     * <p>認可: URL に eventId を含まないため、{@code EventCheckinService} 側で QR トークンから
     * 解決したチケットの {@code eventId} を用いて admin 判定する（{@code requireAdminByEventId}）。</p>
     */
    @PostMapping("/checkin")
    @Operation(summary = "スタッフチェックイン")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "チェックイン成功")
    public ResponseEntity<ApiResponse<CheckinResponse>> staffCheckin(
            @Valid @RequestBody CheckinRequest request) {
        CheckinResponse response = checkinService.staffCheckin(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * セルフチェックインを実行する（本人専用）。
     *
     * <p>認可: {@code EventCheckinService} 側で QR トークンから解決したチケットの参加登録者本人
     * であることを検証する。</p>
     *
     * <p><b>認可方式（{@link AuthorizedInService} メソッド付与）</b>:
     * {@code EventCheckinService#selfCheckin} が {@code requireTicketOwner} でチケット所有者と
     * 操作者を比較する。認可根治戦役 Wave6 監査済。</p>
     */
    @AuthorizedInService
    @PostMapping("/checkin/self")
    @Operation(summary = "セルフチェックイン")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "チェックイン成功")
    public ResponseEntity<ApiResponse<CheckinResponse>> selfCheckin(
            @Valid @RequestBody SelfCheckinRequest request) {
        CheckinResponse response = checkinService.selfCheckin(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * イベントのチェックイン一覧を取得する。
     */
    @GetMapping("/{eventId}/checkins")
    @Operation(summary = "チェックイン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<CheckinResponse>> listCheckins(
            @PathVariable Long eventId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        eventScopeAccessGuard.requireMemberByEventId(SecurityUtils.getCurrentUserId(), eventId);
        Page<CheckinResponse> result = checkinService.listCheckins(eventId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * イベントのチェックイン数を取得する。
     */
    @GetMapping("/{eventId}/checkins/count")
    @Operation(summary = "チェックイン数")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<Long>> getCheckinCount(
            @PathVariable Long eventId) {
        eventScopeAccessGuard.requireMemberByEventId(SecurityUtils.getCurrentUserId(), eventId);
        long count = checkinService.getCheckinCount(eventId);
        return ResponseEntity.ok(ApiResponse.of(count));
    }
}
