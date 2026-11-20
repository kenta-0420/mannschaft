package com.mannschaft.app.effortrecord.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.effortrecord.dto.EffortTemplateSettingsResponse;
import com.mannschaft.app.effortrecord.dto.UpdateEffortTemplateSettingsRequest;
import com.mannschaft.app.effortrecord.service.EffortTemplateSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * F06.4.1 チームテンプレートの指標設定 API（API 契約 §7）。
 *
 * <p>Controller は Service を呼ぶだけ。認可（既存 TEAM テンプレート管理権限＝ADMIN・DEPUTY_ADMIN、template の scope 一致）は
 * {@link EffortTemplateSettingsService} が {@code EffortTeamAccessGuard} で行う。親組織は判定に使わない。</p>
 */
@RestController
@RequestMapping(value = "/api/v1/teams/{teamId}/effort-records/template-settings",
        produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "個人の頑張り記録（チーム定義）", description = "F06.4.1 チームテンプレートの指標設定")
@RequiredArgsConstructor
public class EffortTeamTemplateSettingsController {

    private final EffortTemplateSettingsService settingsService;

    @GetMapping("/{templateId}")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームテンプレート編集画面の指標設定。既存のテンプレート管理権限で提供し機能フラグに依存させない")
    @Operation(summary = "テンプレートの指標設定を取得")
    public ResponseEntity<ApiResponse<EffortTemplateSettingsResponse>> getEffortTemplateSettings(
            @PathVariable Long teamId, @PathVariable Long templateId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(settingsService.get(userId, teamId, templateId)));
    }

    @PutMapping("/{templateId}")
    @AlwaysReachable(category = AlwaysReachableCategory.CORE,
            reason = "チームテンプレート編集画面の指標設定。既存のテンプレート管理権限で提供し機能フラグに依存させない")
    @Operation(summary = "テンプレートの指標設定を更新（全置換）")
    public ResponseEntity<ApiResponse<EffortTemplateSettingsResponse>> updateEffortTemplateSettings(
            @PathVariable Long teamId, @PathVariable Long templateId,
            @Valid @RequestBody UpdateEffortTemplateSettingsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(settingsService.update(userId, teamId, templateId, request)));
    }
}
