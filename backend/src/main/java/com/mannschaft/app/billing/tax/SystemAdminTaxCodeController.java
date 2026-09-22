package com.mannschaft.app.billing.tax;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 価格改定戦役（price-revisions）決定6: 税コードマスタ CRUD（{@code /api/v1/system-admin/billing/tax-codes}）。
 *
 * <p>全 EP {@code SYSTEM_ADMIN} 限定（AC-13）。{@code @PreAuthorize} はメソッド単位で明示付与する
 * （{@code PriceRevisionAuthorizationAnnotationTest} がリフレクションで各メソッドの直接付与を照合するため、
 * クラスレベル注釈のみでは満たせない）。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定6・AC-4〜AC-16・AC-13。</p>
 */
@RestController("priceRevisionSystemAdminTaxCodeController")
@RequestMapping("/api/v1/system-admin/billing/tax-codes")
@Tag(name = "システム管理 - 税コード", description = "価格改定: 税コードマスタ CRUD（SYSTEM_ADMIN専用）")
@RequiredArgsConstructor
public class SystemAdminTaxCodeController {

    private final BillingTaxCodeService service;

    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "税コード一覧")
    public ResponseEntity<List<BillingTaxCodeEntity>> list() {
        return ResponseEntity.ok(service.list());
    }

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "税コード新規登録")
    public ResponseEntity<BillingTaxCodeEntity> create(@Valid @RequestBody BillingTaxCodeCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "税コード更新（表示名・stripeTaxCode・validUntil・enabledのみ）")
    public ResponseEntity<BillingTaxCodeEntity> update(
            @PathVariable UUID id, @Valid @RequestBody BillingTaxCodeUpdateRequest request) {
        return ResponseEntity.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Operation(summary = "税コード論理削除")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
