package com.mannschaft.app.committee.controller;

import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.committee.dto.MinutesConfirmResponse;
import com.mannschaft.app.committee.service.CommitteeMinutesService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F04.10 議事録確定コントローラー。
 * 委員会の議事録（活動記録）を CONFIRMED ステータスに遷移させるエンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/committees/{committeeId}/activity-records")
@Tag(name = "組織委員会 — 議事録", description = "F04.10 委員会議事録の確定操作")
@RequiredArgsConstructor
public class CommitteeMinutesController {

    private final CommitteeMinutesService committeeMinutesService;

    /**
     * 議事録を確定する。
     *
     * <p>認可: CHAIR / VICE_CHAIR のみ実行可能。
     * 確定後: fieldValues の _meta に status=CONFIRMED、confirmed_at、confirmed_by がセットされる。
     *
     * @param committeeId 委員会ID
     * @param recordId    活動記録ID
     * @return 更新後の活動記録ID と fieldValues
     */
    @PatchMapping("/{recordId}/confirm")
    @Operation(summary = "議事録確定", description = "委員会の議事録を CONFIRMED 状態に確定する。CHAIR / VICE_CHAIR のみ実行可能。")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "確定成功")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "委員会スコープでない活動記録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "権限不足（CHAIR/VICE_CHAIR 以外）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "委員会または活動記録が見つからない")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "既に確定済み")
    public ResponseEntity<ApiResponse<MinutesConfirmResponse>> confirmMinutes(
            @PathVariable Long committeeId,
            @PathVariable Long recordId) {

        Long currentUserId = SecurityUtils.getCurrentUserId();
        ActivityResultEntity record = committeeMinutesService.confirmMinutes(committeeId, recordId, currentUserId);

        MinutesConfirmResponse response = MinutesConfirmResponse.builder()
                .activityRecordId(record.getId())
                .fieldValues(record.getFieldValues())
                .build();

        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
