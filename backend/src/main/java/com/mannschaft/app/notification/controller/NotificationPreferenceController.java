package com.mannschaft.app.notification.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.notification.dto.NotificationSettingsResponse;
import com.mannschaft.app.notification.dto.NotificationSettingsUpdateRequest;
import com.mannschaft.app.notification.dto.PreferenceResponse;
import com.mannschaft.app.notification.dto.PreferenceUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceBulkUpdateRequest;
import com.mannschaft.app.notification.dto.TypePreferenceBulkUpdateResponse;
import com.mannschaft.app.notification.dto.TypePreferenceResponse;
import com.mannschaft.app.notification.service.NotificationPreferenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;

/**
 * 通知設定コントローラー。スコープ別・種別別・グローバルの通知設定 API を提供する（F04.3）。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "通知設定", description = "F04.3 通知設定管理")
@RequiredArgsConstructor
public class NotificationPreferenceController {

    private final NotificationPreferenceService preferenceService;

    /**
     * 通知設定一覧を取得する。
     */
    @SelfScopedEndpoint("preferenceRepository.findByUserId の検索条件が SecurityUtils.getCurrentUserId() の"
            + "userId のみで、リクエストは他ユーザーの識別子を受け取らない（NotificationPreferenceService#listPreferences）")
    @GetMapping("/notification-preferences")
    @Operation(summary = "通知設定一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PreferenceResponse>>> listPreferences() {
        List<PreferenceResponse> responses = preferenceService.listPreferences(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 通知設定を更新する。
     */
    // 認可根治戦役 Wave4 ロットD: request.scopeType/scopeId は検索・作成条件だが、
    // preferenceRepository.findByUserIdAndScopeTypeAndScopeId は userId を必須条件に含む複合検索であり、
    // 対象が存在しなければ builder で userId=SecurityUtils.getCurrentUserId() の新規行を作る
    // （NotificationPreferenceService.java:98-113）。したがって scopeId にどんな値を渡しても
    // 更新・作成できるのは常に呼び出しユーザー自身の設定行のみで、他ユーザーの行には userId 不一致のため
    // 到達しない。
    @SelfScopedEndpoint("preferenceRepository.findByUserIdAndScopeTypeAndScopeId が userId を必須条件に含む"
            + "複合キー検索であり、対象行が無ければ userId=SecurityUtils.getCurrentUserId() で新規作成する"
            + "ため、常に呼び出しユーザー自身の設定行しか更新できない（NotificationPreferenceService.java:98-113）")
    @PutMapping("/notification-preferences")
    @Operation(summary = "通知設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<PreferenceResponse>> updatePreference(
            @Valid @RequestBody PreferenceUpdateRequest request) {
        PreferenceResponse response = preferenceService.updatePreference(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 通知種別設定一覧を取得する（カタログ・単一/Dual 含む全種別）。
     */
    @SelfScopedEndpoint("typePreferenceRepository.findByUserId の検索条件が SecurityUtils.getCurrentUserId() の"
            + "userId のみで、リクエストは他ユーザーの識別子を受け取らない（NotificationPreferenceService#listTypePreferences）")
    @GetMapping("/notification-type-preferences")
    @Operation(summary = "通知種別設定一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<TypePreferenceResponse>>> listTypePreferences() {
        List<TypePreferenceResponse> responses =
                preferenceService.listTypePreferences(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 通知種別設定を一括更新する（単一/Dual 含む）。URGENT 種別はスキップ。
     */
    @SelfScopedEndpoint("typePreferenceRepository.findByUserIdAndNotificationType が userId を必須条件に含む"
            + "複合キー検索であり、対象行が無ければ userId=SecurityUtils.getCurrentUserId() で新規作成する"
            + "ため、常に呼び出しユーザー自身の種別設定行しか更新できない（NotificationPreferenceService.java:227-243）")
    @PutMapping("/notification-type-preferences")
    @Operation(summary = "通知種別設定一括更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<TypePreferenceBulkUpdateResponse>> bulkUpdateTypePreferences(
            @Valid @RequestBody TypePreferenceBulkUpdateRequest request) {
        TypePreferenceBulkUpdateResponse response =
                preferenceService.bulkUpdateTypePreferences(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * グローバル通知設定（優先度による自動配信）を取得する。
     */
    @SelfScopedEndpoint("settingsRepository.findByUserId の検索条件が SecurityUtils.getCurrentUserId() の"
            + "userId のみで、リクエストは他ユーザーの識別子を受け取らない（NotificationPreferenceService#getSettings）")
    @GetMapping("/notification-settings")
    @Operation(summary = "グローバル通知設定取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<NotificationSettingsResponse>> getSettings() {
        NotificationSettingsResponse response =
                preferenceService.getSettings(SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * グローバル通知設定を更新する。
     */
    @SelfScopedEndpoint("settingsRepository.findByUserId(userId 一意) が"
            + "SecurityUtils.getCurrentUserId() のみで対象行を解決し、無ければ userId 固定で新規作成する"
            + "ため、常に呼び出しユーザー自身のグローバル設定行しか更新できない"
            + "（NotificationPreferenceService.java:305-314）")
    @PutMapping("/notification-settings")
    @Operation(summary = "グローバル通知設定更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<NotificationSettingsResponse>> updateSettings(
            @Valid @RequestBody NotificationSettingsUpdateRequest request) {
        NotificationSettingsResponse response =
                preferenceService.updateSettings(SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
