package com.mannschaft.app.event.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.event.dto.CreateTicketTypeRequest;
import com.mannschaft.app.event.dto.TicketTypeResponse;
import com.mannschaft.app.event.dto.UpdateTicketTypeRequest;
import com.mannschaft.app.event.service.EventTicketTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * イベントチケット種別コントローラー。チケット種別のCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/ticket-types")
@Tag(name = "イベントチケット種別", description = "F03.8 チケット種別CRUD")
@RequiredArgsConstructor
public class EventTicketTypeController {

    private final EventTicketTypeService ticketTypeService;

    /**
     * チケット種別一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "チケット種別一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TicketTypeResponse>>> listTicketTypes(
            @PathVariable Long eventId) {
        List<TicketTypeResponse> response = ticketTypeService.listTicketTypes(eventId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チケット種別詳細を取得する。
     */
    @GetMapping("/{ticketTypeId}")
    @Operation(summary = "チケット種別詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<TicketTypeResponse>> getTicketType(
            @PathVariable Long eventId,
            @PathVariable Long ticketTypeId) {
        TicketTypeResponse response = ticketTypeService.getTicketType(ticketTypeId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * チケット種別を作成する。
     */
    @PostMapping
    @Operation(summary = "チケット種別作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<TicketTypeResponse>> createTicketType(
            @PathVariable Long eventId,
            @Valid @RequestBody CreateTicketTypeRequest request) {
        TicketTypeResponse response = ticketTypeService.createTicketType(eventId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * チケット種別を更新する。
     */
    @PatchMapping("/{ticketTypeId}")
    @Operation(summary = "チケット種別更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TicketTypeResponse>> updateTicketType(
            @PathVariable Long eventId,
            @PathVariable Long ticketTypeId,
            @Valid @RequestBody UpdateTicketTypeRequest request) {
        TicketTypeResponse response = ticketTypeService.updateTicketType(ticketTypeId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
