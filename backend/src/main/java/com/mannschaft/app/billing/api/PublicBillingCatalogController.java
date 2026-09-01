package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.api.dto.PublicBillingCatalogResponse;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.security.IntentionallyPublic;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 公開価格カタログAPI。
 *
 * <p>未認証で参照できるのはこのGETだけである。SecurityConfig の exact GET matcher と
 * {@link IntentionallyPublic} を番人が照合し、PublicApiRateLimitFilter がIPごと60回/分に制限する。
 * 購入可否・Stripe参照・個別scope情報は返さない。</p>
 */
@IntentionallyPublic("/api/v1/public/billing/plans")
@RestController
@RequestMapping("/api/v1/public/billing")
@Tag(name = "公開課金価格", description = "未認証で参照できる公開価格カタログ")
@RequiredArgsConstructor
public class PublicBillingCatalogController {

    private final BillingPublicCatalogQueryService catalogQueryService;

    @GetMapping("/plans")
    @Operation(
            operationId = "getPublicBillingPlans",
            summary = "公開プラン・アドオン価格",
            description = "scopeKindごとの公開価格を返す")
    public ResponseEntity<ApiResponse<PublicBillingCatalogResponse>> plans(
            @RequestParam("scopeKind") String scopeKind) {
        EntitlementScopeKind parsedScopeKind = BillingApiSupport.parseScopeKind(scopeKind);
        return ResponseEntity.ok(ApiResponse.of(catalogQueryService.getPublicCatalog(parsedScopeKind)));
    }
}
