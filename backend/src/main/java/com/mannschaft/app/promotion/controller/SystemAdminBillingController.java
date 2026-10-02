package com.mannschaft.app.promotion.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.security.AuthorizedByPathConfig;
import com.mannschaft.app.promotion.dto.BillingRecordResponse;
import com.mannschaft.app.promotion.service.PromotionBillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SYSTEM_ADMIN用プロモーション課金コントローラー。
 *
 * <p><b>認可根拠（{@link AuthorizedByPathConfig} クラス付与・凍結ストア該当 1 EP）</b>:
 * 本 Controller の全 Mapping エンドポイントは、{@code SecurityConfig} のパス単位認可により
 * SYSTEM_ADMIN ロール保持者のみへ宣言的に予約されている。</p>
 *
 * <p><b>根拠</b>:
 * 本クラスはクラスレベル @RequestMapping を持たず、唯一の Mapping メソッドが GET /api/v1/system-admin/promotion-billing
 * を直接宣言する。根拠は SecurityConfig の requestMatchers("/api/v1/system-admin/**").hasRole("SYSTEM_ADMIN")
 * </p>
 *
 * <p>Controller / Service 側に認可コードは存在しないが、フィルタチェーンで強制されるため
 * 無認可ではない。認可根治戦役 Wave5 監査済。パス定義を変更・削除する際は本注釈の根拠が
 * 失効するため、必ず併せて見直すこと。</p>
 */
@AuthorizedByPathConfig("/api/v1/system-admin/**")
@RestController
@Tag(name = "プロモーション課金（SYSTEM_ADMIN）", description = "F09.2 課金状況一覧")
@RequiredArgsConstructor
public class SystemAdminBillingController {

    private final PromotionBillingService billingService;

    @GetMapping("/api/v1/system-admin/promotion-billing")
    @Operation(summary = "課金状況一覧")
    public ResponseEntity<PagedResponse<BillingRecordResponse>> list(
            @RequestParam(required = false) String billingStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<BillingRecordResponse> result = billingService.listBillingRecords(
                billingStatus, PageRequest.of(page, Math.min(size, 50)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }
}
