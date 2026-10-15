package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.village.dto.CalendarEventCreateRequest;
import com.mannschaft.app.village.dto.CalendarEventListResponse;
import com.mannschaft.app.village.dto.CalendarEventLogCreateRequest;
import com.mannschaft.app.village.dto.CalendarEventLogResponse;
import com.mannschaft.app.village.dto.CalendarEventResponse;
import com.mannschaft.app.village.dto.CalendarEventUpdateRequest;
import com.mannschaft.app.village.service.VillageCalendarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

import java.time.LocalDate;
import java.util.List;
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
     *
     * <p>認可は {@link VillageCalendarService#createEvent} 内の {@code requireModerator} が実施し、
     * 正準述語 {@code findActiveByVillageIdAndSubject} で現役（退村・BAN 済みでない）の
     * HEADMAN / ELDER であることを検証する。</p>
     */
    @AuthorizedInService
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
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "指定月の歳時記イベント一覧を取得する（村人のみ）")
    public ApiResponse<CalendarEventListResponse> listByMonth(
            @PathVariable("villageId") UUID villageId,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "month", required = false) Integer month) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        LocalDate today = LocalDate.now();
        int resolvedYear = (year != null) ? year : today.getYear();
        int resolvedMonth = (month != null) ? month : today.getMonthValue();
        CalendarEventListResponse response =
                calendarService.listEventsByMonth(villageId, resolvedYear, resolvedMonth, actorUserId);
        return ApiResponse.of(response);
    }

    /**
     * 歳時記イベントの詳細を取得する（村人のみ）。
     */
    @GetMapping("/{eventId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "歳時記イベントの詳細を取得する（村人のみ）")
    public ApiResponse<CalendarEventResponse> get(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("eventId") UUID eventId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(calendarService.getEvent(villageId, eventId, actorUserId));
    }

    /**
     * 歳時記イベントを部分更新する（HEADMAN / ELDER のみ）。
     *
     * <p>認可は {@link VillageCalendarService#updateEvent} 内の {@code requireModerator} が実施する。
     * 対象イベントは実体を取得して村スコープの一致を照合し、不一致は 404 で存在を秘匿する。</p>
     */
    @AuthorizedInService
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
     *
     * <p>認可は {@link VillageCalendarService#deleteEvent} 内の {@code requireModerator} が実施する。
     * 対象イベントは実体を取得して村スコープの一致を照合し、不一致は 404 で存在を秘匿する。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/{eventId}")
    @Operation(summary = "歳時記イベントを論理削除する（HEADMAN / ELDER のみ）")
    public ResponseEntity<Void> delete(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("eventId") UUID eventId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        calendarService.deleteEvent(villageId, eventId, actorUserId);
        return ResponseEntity.noContent().build();
    }

    // ====================================================================
    // F17.2 Wave1 ④歳時記×村史の年輪（去年の様子）
    // ====================================================================

    /** 一覧の既定ページサイズ（設計書 §13.5）。 */
    private static final int DEFAULT_LOG_PAGE_SIZE = 20;

    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{eventId}/logs")
    @Operation(summary = "年輪（その年の様子）一覧を取得する（村人・year 降順・?year= 絞り込み可）")
    public ApiResponse<List<CalendarEventLogResponse>> listLogs(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("eventId") UUID eventId,
            @RequestParam(name = "year", required = false) Integer year,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "" + DEFAULT_LOG_PAGE_SIZE) int size) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        Pageable pageable = PageRequest.of(Math.max(page, 0), size <= 0 ? DEFAULT_LOG_PAGE_SIZE : size);
        return ApiResponse.of(calendarService.listLogs(villageId, eventId, year, actorUserId, pageable));
    }

    @PreAuthorize("isAuthenticated()")
    @PostMapping("/{eventId}/logs")
    @Operation(summary = "年輪を追加する（村人・同一 year 複数件可）")
    public ResponseEntity<ApiResponse<CalendarEventLogResponse>> addLog(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("eventId") UUID eventId,
            @Valid @RequestBody CalendarEventLogCreateRequest request) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        CalendarEventLogResponse response = calendarService.addLog(villageId, eventId, request, actorUserId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @PreAuthorize("isAuthenticated()")
    @DeleteMapping("/{eventId}/logs/{logId}")
    @Operation(summary = "年輪を論理削除する（投稿者本人＋村長/長老のみ）")
    public ResponseEntity<Void> deleteLog(
            @PathVariable("villageId") UUID villageId,
            @PathVariable("eventId") UUID eventId,
            @PathVariable("logId") UUID logId) {
        Long actorUserId = SecurityUtils.getCurrentUserId();
        calendarService.deleteLog(villageId, eventId, logId, actorUserId);
        return ResponseEntity.noContent().build();
    }
}
