package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.village.dto.CalendarEventCreateRequest;
import com.mannschaft.app.village.dto.CalendarEventListResponse;
import com.mannschaft.app.village.dto.CalendarEventResponse;
import com.mannschaft.app.village.dto.CalendarEventUpdateRequest;
import com.mannschaft.app.village.service.VillageCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.time.LocalDate;
import java.util.UUID;

/**
 * F17.1 Phase 2 U8 — 村歳時記カレンダー Controller（設計書 §2.2）。
 *
 * <p>桃の節句・七夕・年越し等の年中行事および単発イベントを村単位で管理する。
 * モデレータ（HEADMAN / ELDER）のみ CRUD 可、村人は閲覧のみ。
 * 認可・整合性検証はすべて {@link VillageCalendarService} 側で完結する。</p>
 *
 * <p>担当 API:</p>
 * <ul>
 *   <li>{@code POST   /api/v1/villages/{villageId}/calendar-events} — 作成</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/calendar-events?year=&month=} — 月別取得</li>
 *   <li>{@code GET    /api/v1/villages/{villageId}/calendar-events/{eventId}} — 詳細</li>
 *   <li>{@code PATCH  /api/v1/villages/{villageId}/calendar-events/{eventId}} — 更新</li>
 *   <li>{@code DELETE /api/v1/villages/{villageId}/calendar-events/{eventId}} — 論理削除</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/villages/{villageId}/calendar-events")
@Tag(name = "村歳時記カレンダー (F17.1)",
     description = "Phase 2 U4/U8: 桃の節句・七夕・年越し等の年中行事 CRUD")
@RequiredArgsConstructor
public class VillageCalendarController {

    private final VillageCalendarService calendarService;

    /**
     * 歳時記イベントを作成する（HEADMAN / ELDER のみ）。
     */
    @PostMapping
    @Operation(summary = "歳時記イベントを作成する（HEADMAN / ELDER のみ）")
    public ResponseEntity<ApiResponse<CalendarEventResponse>> create(
            @PathVariable("villageId") UUID villageId,
            @Valid @RequestBody CalendarEventCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        CalendarEventResponse response = calendarService.createEvent(villageId, request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 指定月の歳時記イベント一覧を取得する。
     * デフォルトは現在の年月。{@code year} / {@code month} が未指定なら現時点を使う。
     */
    @GetMapping
    @Operation(summary = "指定月の歳時記イベント一覧を取得する")
    public ApiResponse<CalendarEventListResponse> listByMonth(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        LocalDate today = LocalDate.now();
        int resolvedYear = (year != null) ? year : today.getYear();
        int resolvedMonth = (month != null) ? month : today.getMonthValue();
        CalendarEventListResponse response =
                calendarService.listEventsByMonth(villageId, resolvedYear, resolvedMonth);
        return ApiResponse.of(response);
    }

    /**
     * 歳時記イベントの詳細を取得する。
     */
    @GetMapping("/{eventId}")
    @Operation(summary = "歳時記イベントの詳細を取得する")
    public ApiResponse<CalendarEventResponse> get(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("eventId") UUID eventId) {
        return ApiResponse.of(calendarService.getEvent(villageId, eventId));
    }

    /**
     * 歳時記イベントを部分更新する（HEADMAN / ELDER のみ）。
     */
    @PatchMapping("/{eventId}")
    @Operation(summary = "歳時記イベントを部分更新する（HEADMAN / ELDER のみ）")
    public ApiResponse<CalendarEventResponse> update(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("eventId") UUID eventId,
            @Valid @RequestBody CalendarEventUpdateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        CalendarEventResponse response = calendarService.updateEvent(villageId, eventId, request, actorUserId);
        return ApiResponse.of(response);
    }

    /**
     * 歳時記イベントを論理削除する（HEADMAN / ELDER のみ）。
     */
    @DeleteMapping("/{eventId}")
    @Operation(summary = "歳時記イベントを論理削除する（HEADMAN / ELDER のみ）")
    public ResponseEntity<Void> delete(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("eventId") UUID eventId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        calendarService.deleteEvent(villageId, eventId, actorUserId);
        return ResponseEntity.noContent().build();
    }
}
