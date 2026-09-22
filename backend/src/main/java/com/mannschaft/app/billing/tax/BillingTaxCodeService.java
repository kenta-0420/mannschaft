package com.mannschaft.app.billing.tax;

import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 税コードマスタ（{@code billing_tax_codes}）の CRUD・税率解決サービス（決定6改訂）。
 *
 * <p>create/update は必ず専用ロック行 {@code __TAX_CODE_LOCK__} を {@code FOR UPDATE} してから
 * 重複・有効期間の重なりを判定する。新規 code の初回登録にはロック対象となる既存行が無いため、
 * 同一 code の行だけを {@code FOR UPDATE} する方式では直列化されない（第3版までの欠陥）。
 * ロック専用行を必ず1本経由させることで、あらゆる新規/既存 code の create/update を直列化する
 * （AC-5/AC-6/AC-10/AC-11）。</p>
 *
 * <p>正本: {@code .claude/campaigns/price-rev-plan-v3.md} 決定6・AC-4〜AC-16。</p>
 */
@Service
@RequiredArgsConstructor
public class BillingTaxCodeService {

    private static final String LOCK_ROW_CODE = "__TAX_CODE_LOCK__";

    /**
     * ロック行（{@code __TAX_CODE_LOCK__}）の {@code valid_from}。V220 migration の投入値
     * {@code 1970-01-01 00:00:00.000000} と完全一致する固定値（根治治療の詳細は
     * {@link BillingTaxCodeRepository#lockTaxCodeLockRowForUpdate} の Javadoc 参照）。
     */
    private static final Instant LOCK_ROW_VALID_FROM = Instant.EPOCH;

    private final BillingTaxCodeRepository repository;

    /** AC-4: ロック行を除く有効な税コード一覧。 */
    @Transactional(readOnly = true)
    public List<BillingTaxCodeEntity> list() {
        return repository.findAllVisible().stream()
                .filter(e -> !LOCK_ROW_CODE.equals(e.getCode()))
                .toList();
    }

    /** AC-5/AC-8/AC-9/AC-10: 新規税コード登録。 */
    @Transactional
    public BillingTaxCodeEntity create(BillingTaxCodeCreateRequest request) {
        repository.lockTaxCodeLockRowForUpdate(LOCK_ROW_VALID_FROM);

        repository.findByCodeAndValidFromAndDeletedAtIsNull(request.code(), request.validFrom())
                .ifPresent(existing -> {
                    throw new BusinessException(PriceRevisionErrorCode.TAX_CODE_DUPLICATE);
                });

        List<BillingTaxCodeEntity> overlapping =
                repository.findOverlapping(request.code(), request.validFrom(), request.validUntil());
        if (!overlapping.isEmpty()) {
            throw new BusinessException(PriceRevisionErrorCode.TAX_CODE_OVERLAP);
        }

        BillingTaxCodeEntity entity = BillingTaxCodeEntity.builder()
                .code(request.code())
                .displayName(request.displayName())
                .rateBasisPoints(request.rateBasisPoints())
                .stripeTaxCode(request.stripeTaxCode())
                .validFrom(request.validFrom())
                .validUntil(request.validUntil())
                .enabled(request.enabled())
                .build();
        return repository.save(entity);
    }

    /** AC-6/AC-10: 表示名・stripeTaxCode・validUntil・enabled のみ更新可能。 */
    @Transactional
    public BillingTaxCodeEntity update(UUID id, BillingTaxCodeUpdateRequest request) {
        repository.lockTaxCodeLockRowForUpdate(LOCK_ROW_VALID_FROM);

        BillingTaxCodeEntity existing = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.TAX_CODE_NOT_FOUND));

        List<BillingTaxCodeEntity> overlapping =
                repository.findOverlapping(existing.getCode(), existing.getValidFrom(), request.validUntil())
                        .stream()
                        .filter(row -> !row.getId().equals(existing.getId()))
                        .toList();
        if (!overlapping.isEmpty()) {
            throw new BusinessException(PriceRevisionErrorCode.TAX_CODE_OVERLAP);
        }

        existing.setDisplayName(request.displayName());
        existing.setStripeTaxCode(request.stripeTaxCode());
        existing.setValidUntil(request.validUntil());
        existing.setEnabled(request.enabled());
        return repository.save(existing);
    }

    /** AC-7: 論理削除。 */
    @Transactional
    public void delete(UUID id) {
        BillingTaxCodeEntity existing = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.TAX_CODE_NOT_FOUND));
        existing.setDeletedAt(Instant.now());
        repository.save(existing);
    }

    /**
     * AC-12/AC-14/AC-15/AC-16: 指定時点で有効な税コードを解決する。
     * 存在しない・無効・有効期間外・ロック行はすべて {@link PriceRevisionErrorCode#TAX_CODE_NOT_FOUND} とする。
     */
    @Transactional(readOnly = true)
    public BillingTaxCodeEntity resolveEffective(String code, Instant at) {
        return repository.findEffectiveAt(code, at)
                .filter(BillingTaxCodeEntity::isEnabled)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.TAX_CODE_NOT_FOUND));
    }
}
