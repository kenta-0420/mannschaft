package com.mannschaft.app.advertising.controller;

import com.mannschaft.app.advertising.dto.AffiliateConfigResponse;
import com.mannschaft.app.advertising.dto.CreateAffiliateConfigRequest;
import com.mannschaft.app.advertising.dto.UpdateAffiliateConfigRequest;
import com.mannschaft.app.advertising.service.AffiliateConfigService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
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
 */
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
