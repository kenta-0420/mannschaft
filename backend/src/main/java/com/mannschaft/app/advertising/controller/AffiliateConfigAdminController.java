package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AffiliateConfigResponse;
import com.mannschaft.app.advertising.dto.CreateAffiliateConfigRequest;
import com.mannschaft.app.advertising.dto.UpdateAffiliateConfigRequest;
import com.mannschaft.app.advertising.service.AffiliateConfigService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * アフィリエイト設定管理コントローラー（SYSTEM_ADMIN用）。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 5 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * SecurityConfig.java:419 — requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig
@RestController
@RequestMapping("/api/v1/system-admin/affiliate-configs")
@RequiredArgsConstructor
public class AffiliateConfigAdminController {

    private final AffiliateConfigService affiliateConfigService;

    /**
     * アフィリエイト設定一覧を取得する。
     */
    @GetMapping
    public PagedResponse<AffiliateConfigResponse> list(Pageable pageable) {
        Page<AffiliateConfigResponse> page = affiliateConfigService.findAll(pageable);
        return PagedResponse.of(
                page.getContent(),
                new PagedResponse.PageMeta(
                        page.getTotalElements(),
                        page.getNumber(),
                        page.getSize(),
                        page.getTotalPages()
                )
        );
    }

    /**
     * アフィリエイト設定を作成する。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AffiliateConfigResponse> create(
            @Valid @RequestBody CreateAffiliateConfigRequest request) {
        return ApiResponse.of(affiliateConfigService.create(request));
    }

    /**
     * アフィリエイト設定を更新する。
     */
    @PutMapping("/{id}")
    public ApiResponse<AffiliateConfigResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateAffiliateConfigRequest request) {
        return ApiResponse.of(affiliateConfigService.update(id, request));
    }

    /**
     * 有効/無効を切り替える。
     */
    @PatchMapping("/{id}/toggle")
    public ApiResponse<AffiliateConfigResponse> toggle(@PathVariable Long id) {
        return ApiResponse.of(affiliateConfigService.toggle(id));
    }

    /**
     * アフィリエイト設定を削除する（論理削除）。
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        affiliateConfigService.delete(id);
    }
}
