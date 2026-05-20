package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.AgeGroupSettingsResponse;
import com.mannschaft.app.auth.dto.AgeGroupSettingsUpdateRequest;
import com.mannschaft.app.auth.entity.AgeGroupSettingsEntity;
import com.mannschaft.app.auth.service.AgeGroupSettingsService;
import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F01.9 年齢確認・保護者同意機能: 年齢区分設定管理コントローラー（管理者向け）。
 *
 * <p>SecurityConfig で {@code /api/v1/admin/age-group-settings/**} は
 * SYSTEM_ADMIN ロールに限定されている。</p>
 */
@RestController
@RequestMapping("/api/v1/admin/age-group-settings")
@Tag(name = "年齢区分設定管理（管理者）")
@RequiredArgsConstructor
public class AgeGroupSettingsController {

    private final AgeGroupSettingsService ageGroupSettingsService;

    /**
     * 年齢区分設定を全件取得する。
     */
    @GetMapping
    @Operation(summary = "年齢区分設定一覧取得", description = "全ての年齢区分の設定を取得する（SYSTEM_ADMIN のみ）")
    public ResponseEntity<ApiResponse<List<AgeGroupSettingsResponse>>> getAll() {
        List<AgeGroupSettingsResponse> list = ageGroupSettingsService.getAll()
                .stream()
                .map(AgeGroupSettingsResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(list));
    }

    /**
     * 指定した年齢区分の設定を更新する。
     *
     * @param ageGroup 更新対象の年齢区分識別子（例: CHILD / TEEN / ADULT）
     */
    @PutMapping("/{ageGroup}")
    @Operation(summary = "年齢区分設定更新", description = "指定した年齢区分の機能設定・テーマ設定を更新する（SYSTEM_ADMIN のみ）")
    public ResponseEntity<ApiResponse<AgeGroupSettingsResponse>> update(
            @Parameter(description = "年齢区分識別子（例: CHILD, TEEN, ADULT）")
            @PathVariable String ageGroup,
            @Valid @RequestBody AgeGroupSettingsUpdateRequest req) {
        AgeGroupSettingsEntity updated = ageGroupSettingsService.update(
                ageGroup, req.getFeaturesEnabled(), req.getThemeConfig());
        return ResponseEntity.ok(ApiResponse.of(AgeGroupSettingsResponse.from(updated)));
    }
}
