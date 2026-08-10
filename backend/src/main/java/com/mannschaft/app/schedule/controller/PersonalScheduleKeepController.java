package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.schedule.authz.ScheduleKeepScope;
import com.mannschaft.app.schedule.dto.ConvertScheduleKeepRequest;
import com.mannschaft.app.schedule.dto.ConvertScheduleKeepResponse;
import com.mannschaft.app.schedule.dto.CreateScheduleKeepRequest;
import com.mannschaft.app.schedule.dto.ReorderScheduleKeepsRequest;
import com.mannschaft.app.schedule.dto.ScheduleKeepResponse;
import com.mannschaft.app.schedule.service.ScheduleKeepService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import java.util.Map;
import java.util.UUID;

/**
 * 個人スコープのキープ（日付未定の予定）コントローラー（F03.17 §4.1・第三陣）。
 *
 * <p>スコープ ID は常に認証主体自身の {@code userId}
 * （{@code ScheduleKeepScope.personal(SecurityUtils.getCurrentUserId())}）であり、
 * リクエストは他人のスコープを指定できない。認可は {@code ScheduleKeepService} 経由で
 * {@code ScheduleKeepAccessGuard} を必ず通す。</p>
 */
@RestController
@RequestMapping("/api/v1/me/schedule-keeps")
@Tag(name = "個人キープ管理", description = "F03.17 個人スコープのキープ（日付未定の予定）CRUD")
@RequiredArgsConstructor
public class PersonalScheduleKeepController {

    private final ScheduleKeepService scheduleKeepService;

    @PostMapping
    @Operation(summary = "個人キープ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> create(
            @RequestBody CreateScheduleKeepRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScheduleKeepResponse response = scheduleKeepService.create(
                ScheduleKeepScope.personal(userId), request, userId);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @GetMapping
    @Operation(summary = "個人キープ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ScheduleKeepResponse>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ScheduleKeepResponse> response = scheduleKeepService.list(
                ScheduleKeepScope.personal(userId), status, page, size, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/{keepId}")
    @Operation(summary = "個人キープ詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> get(@PathVariable UUID keepId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScheduleKeepResponse response = scheduleKeepService.get(
                ScheduleKeepScope.personal(userId), keepId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PatchMapping("/{keepId}")
    @Operation(summary = "個人キープ更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> update(
            @PathVariable UUID keepId,
            @RequestBody Map<String, Object> body) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScheduleKeepResponse response = scheduleKeepService.update(
                ScheduleKeepScope.personal(userId), keepId, body, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @DeleteMapping("/{keepId}")
    @Operation(summary = "個人キープ削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    public ResponseEntity<Void> delete(@PathVariable UUID keepId) {
        Long userId = SecurityUtils.getCurrentUserId();
        scheduleKeepService.delete(ScheduleKeepScope.personal(userId), keepId, userId);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{keepId}/convert")
    @Operation(summary = "個人キープを予定へ変換")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "変換成功")
    public ResponseEntity<ApiResponse<ConvertScheduleKeepResponse>> convert(
            @PathVariable UUID keepId,
            @RequestBody ConvertScheduleKeepRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        ConvertScheduleKeepResponse response = scheduleKeepService.convert(
                ScheduleKeepScope.personal(userId), keepId, request, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/reorder")
    @Operation(summary = "個人キープの並び替え")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "並び替え成功")
    public ResponseEntity<Void> reorder(@RequestBody ReorderScheduleKeepsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        scheduleKeepService.reorder(ScheduleKeepScope.personal(userId), request.getOrderedIds(), userId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/by-schedule/{scheduleId}")
    @Operation(summary = "予定から由来キープを逆引き")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> getByConvertedSchedule(
            @PathVariable Long scheduleId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScheduleKeepResponse response = scheduleKeepService.getByConvertedSchedule(
                ScheduleKeepScope.personal(userId), scheduleId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{keepId}/archive")
    @Operation(summary = "個人キープをアーカイブ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "アーカイブ成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> archive(@PathVariable UUID keepId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScheduleKeepResponse response = scheduleKeepService.archive(
                ScheduleKeepScope.personal(userId), keepId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{keepId}/restore")
    @Operation(summary = "個人キープのアーカイブ解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "復帰成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> restore(@PathVariable UUID keepId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScheduleKeepResponse response = scheduleKeepService.restore(
                ScheduleKeepScope.personal(userId), keepId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping("/{keepId}/revert")
    @Operation(summary = "個人キープの変換取消")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取消成功")
    public ResponseEntity<ApiResponse<ScheduleKeepResponse>> revert(@PathVariable UUID keepId) {
        Long userId = SecurityUtils.getCurrentUserId();
        ScheduleKeepResponse response = scheduleKeepService.revert(
                ScheduleKeepScope.personal(userId), keepId, userId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
