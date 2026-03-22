package com.mannschaft.app.activity.controller;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.dto.ActivityParticipantResponse;
import com.mannschaft.app.activity.dto.AddParticipantsRequest;
import com.mannschaft.app.activity.dto.CreateActivityRequest;
import com.mannschaft.app.activity.dto.DuplicateActivityRequest;
import com.mannschaft.app.activity.dto.RemoveParticipantsRequest;
import com.mannschaft.app.activity.dto.UpdateActivityRequest;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.common.ApiResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 活動記録コントローラー。活動記録のCRUD・参加者管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/activities")
@Tag(name = "活動記録", description = "F06.4 活動記録CRUD・参加者管理")
@RequiredArgsConstructor
public class ActivityController {

    private final ActivityResultService activityService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 活動記録一覧を取得する（Cursor-based ページネーション）。
     */
    @GetMapping
    @Operation(summary = "活動記録一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ActivityResultEntity>>> listActivities(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId,
            @RequestParam(value = "template_id", required = false) Long templateId,
            @RequestParam(defaultValue = "20") int limit) {
        Page<ActivityResultEntity> result = activityService.listActivities(
                ActivityScopeType.valueOf(scopeType), scopeId, templateId, PageRequest.of(0, limit));
        return ResponseEntity.ok(ApiResponse.of(result.getContent()));
    }

    /**
     * 活動記録詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "活動記録詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ActivityResultEntity>> getActivity(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(activityService.getActivity(id)));
    }

    /**
     * 活動記録を作成する。
     */
    @PostMapping
    @Operation(summary = "活動記録作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ActivityResultEntity>> createActivity(
            @RequestParam("scope_type") String scopeType,
            @RequestParam("scope_id") Long scopeId,
            @Valid @RequestBody CreateActivityRequest request) {
        ActivityResultEntity response = activityService.createActivity(
                getCurrentUserId(), ActivityScopeType.valueOf(scopeType), scopeId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 活動記録を更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "活動記録更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ActivityResultEntity>> updateActivity(
            @PathVariable Long id,
            @Valid @RequestBody UpdateActivityRequest request) {
        return ResponseEntity.ok(ApiResponse.of(activityService.updateActivity(id, request)));
    }

    /**
     * 活動記録を削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "活動記録削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteActivity(@PathVariable Long id) {
        activityService.deleteActivity(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 活動記録を複製する。
     */
    @PostMapping("/{id}/duplicate")
    @Operation(summary = "活動記録複製")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "複製成功")
    public ResponseEntity<ApiResponse<ActivityResultEntity>> duplicateActivity(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) DuplicateActivityRequest request) {
        ActivityResultEntity response = activityService.duplicateActivity(id, getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 参加者を追加する。
     */
    @PostMapping("/{id}/participants")
    @Operation(summary = "参加者追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "追加成功")
    public ResponseEntity<ApiResponse<List<ActivityParticipantResponse>>> addParticipants(
            @PathVariable Long id,
            @Valid @RequestBody AddParticipantsRequest request) {
        return ResponseEntity.ok(ApiResponse.of(activityService.addParticipants(id, request)));
    }

    /**
     * 参加者を削除する。
     */
    @DeleteMapping("/{id}/participants")
    @Operation(summary = "参加者削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "削除成功")
    public ResponseEntity<ApiResponse<List<ActivityParticipantResponse>>> removeParticipants(
            @PathVariable Long id,
            @Valid @RequestBody RemoveParticipantsRequest request) {
        return ResponseEntity.ok(ApiResponse.of(activityService.removeParticipants(id, request)));
    }
}
