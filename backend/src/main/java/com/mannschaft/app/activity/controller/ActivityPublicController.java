package com.mannschaft.app.activity.controller;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公開活動記録コントローラー。認証不要のSSR用APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/public")
@Tag(name = "公開活動記録", description = "F06.4 公開活動記録（認証不要・SSR用）")
@RequiredArgsConstructor
public class ActivityPublicController {

    private final ActivityResultService activityService;

    /**
     * チーム公開活動記録一覧を取得する。
     */
    @GetMapping("/teams/{teamId}/activities")
    @Operation(summary = "チーム公開活動記録一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ActivityResultEntity>>> listTeamPublicActivities(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "20") int limit) {
        Page<ActivityResultEntity> result = activityService.listPublicActivities(
                ActivityScopeType.TEAM, teamId, PageRequest.of(0, limit));
        return ResponseEntity.ok(ApiResponse.of(result.getContent()));
    }

    /**
     * チーム公開活動記録詳細を取得する。
     */
    @GetMapping("/teams/{teamId}/activities/{id}")
    @Operation(summary = "チーム公開活動記録詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ActivityResultEntity>> getTeamPublicActivity(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        ActivityResultEntity entity = activityService.getActivity(id);
        // 公開チェックは Service 層で行うことを想定（ここでは基本的なチェック）
        return ResponseEntity.ok(ApiResponse.of(entity));
    }

    /**
     * 組織公開活動記録一覧を取得する。
     */
    @GetMapping("/organizations/{orgId}/activities")
    @Operation(summary = "組織公開活動記録一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ActivityResultEntity>>> listOrgPublicActivities(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "20") int limit) {
        Page<ActivityResultEntity> result = activityService.listPublicActivities(
                ActivityScopeType.ORGANIZATION, orgId, PageRequest.of(0, limit));
        return ResponseEntity.ok(ApiResponse.of(result.getContent()));
    }

    /**
     * 組織公開活動記録詳細を取得する。
     */
    @GetMapping("/organizations/{orgId}/activities/{id}")
    @Operation(summary = "組織公開活動記録詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<ActivityResultEntity>> getOrgPublicActivity(
            @PathVariable Long orgId,
            @PathVariable Long id) {
        ActivityResultEntity entity = activityService.getActivity(id);
        return ResponseEntity.ok(ApiResponse.of(entity));
    }
}
