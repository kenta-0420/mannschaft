package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.AttendancePermissionsResponse;
import com.mannschaft.app.school.service.SchoolAttendanceAccessPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** F03.13 学校出欠: 権限判定結果エンドポイント（FE の入口・ボタン出し分け用）。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class AttendancePermissionController {

    private final SchoolAttendanceAccessPolicy accessPolicy;

    /**
     * 自分の学校出欠に関する権限判定結果を返す。
     *
     * <p>認証済みなら常に 200。権限なし・非所属・存在しないチームでも全項目 false で返し、
     * チームの存在有無を応答差にしない。未認証は 401（フィルタ層）。</p>
     */
    @GetMapping("/teams/{teamId}/attendance/permissions")
    @Operation(
            summary = "学校出欠の権限判定結果取得",
            description = "自分が当該クラスの出欠を閲覧・日次登録・時限登録できるかを返す。権限なしでも 200 で全項目 false。"
    )
    public ApiResponse<AttendancePermissionsResponse> getPermissions(@PathVariable Long teamId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(accessPolicy.resolvePermissions(currentUserId, teamId));
    }
}
