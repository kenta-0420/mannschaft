package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.dto.AgeGroupSettingsResponse;
import com.mannschaft.app.auth.dto.AgeGroupSettingsUpdateRequest;
import com.mannschaft.app.auth.entity.AgeGroupSettingsEntity;
import com.mannschaft.app.auth.service.AgeGroupSettingsService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
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
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 2 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig の requestMatchers("/api/v1/admin/age-group-settings/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig("/api/v1/admin/age-group-settings/**")
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
