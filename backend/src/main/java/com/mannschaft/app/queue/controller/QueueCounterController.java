package com.mannschaft.app.queue.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.queue.dto.CounterResponse;
import com.mannschaft.app.queue.dto.CreateCounterRequest;
import com.mannschaft.app.queue.dto.UpdateCounterRequest;
import com.mannschaft.app.queue.service.QueueCounterService;
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

import java.util.List;

/**
 * 順番待ちカウンターコントローラー。カウンターのCRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/queue/counters")
@Tag(name = "順番待ちカウンター管理", description = "F03.7 順番待ちカウンターのCRUD")
@RequiredArgsConstructor
public class QueueCounterController {

    private final QueueCounterService counterService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * カテゴリ配下のカウンター一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "カウンター一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CounterResponse>>> listCounters(
            @PathVariable Long teamId,
            @RequestParam Long categoryId) {
        List<CounterResponse> counters = counterService.listCounters(categoryId);
        return ResponseEntity.ok(ApiResponse.of(counters));
    }

    /**
     * カウンター詳細を取得する。
     */
    @GetMapping("/{counterId}")
    @Operation(summary = "カウンター詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CounterResponse>> getCounter(
            @PathVariable Long teamId,
            @PathVariable Long counterId) {
        CounterResponse counter = counterService.getCounter(counterId);
        return ResponseEntity.ok(ApiResponse.of(counter));
    }

    /**
     * カウンターを作成する。
     */
    @PostMapping
    @Operation(summary = "カウンター作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<CounterResponse>> createCounter(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateCounterRequest request) {
        CounterResponse counter = counterService.createCounter(request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(counter));
    }

    /**
     * カウンターを更新する。
     */
    @PatchMapping("/{counterId}")
    @Operation(summary = "カウンター更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CounterResponse>> updateCounter(
            @PathVariable Long teamId,
            @PathVariable Long counterId,
            @Valid @RequestBody UpdateCounterRequest request) {
        CounterResponse counter = counterService.updateCounter(counterId, request);
        return ResponseEntity.ok(ApiResponse.of(counter));
    }
}
