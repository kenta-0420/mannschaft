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

    /** Stripe の Product tax code 形式（例 txcd_99999999）。 */
    private static final java.util.regex.Pattern STRIPE_TAX_CODE_PATTERN =
            java.util.regex.Pattern.compile("^txcd_\\d{8}$");

    private static final String LOCK_ROW_CODE = "__TAX_CODE_LOCK__";

    /**
     * ロック行（{@code __TAX_CODE_LOCK__}）の {@code valid_from}。V222 migration の投入値
     * {@code 1970-01-01 00:00:00.000000} と完全一致する固定値（根治治療の詳細は
     * {@link BillingTaxCodeRepository#lockTaxCodeLockRowForUpdate} の Javadoc 参照）。
     */
    private static final Instant LOCK_ROW_VALID_FROM = Instant.EPOCH;

    private final BillingTaxCodeRepository repository;

    /** AC-4: ロック行を除く有効な税コード一覧。 */
    @Transactional(readOnly = true)
    public List<BillingTaxCodeView> list() {
        return repository.findAllVisible().stream()
                .filter(e -> !LOCK_ROW_CODE.equals(e.getCode()))
                .map(BillingTaxCodeView::from)
                .toList();
    }

    /**
     * AC-5/AC-8/AC-9/AC-10: 新規税コード登録。
     *
     * <p><b>実装は分離レベル変更なしで正しい（出陣隊第4陣・実測で確定）:</b>
     * 当初 {@code BillingTaxCodeLockConcurrencyIT} AC-11（異なる2つの新規codeの同時POST）が
     * InnoDBデッドロック（{@code uk_btc_code_from} 索引の supremum 疑似レコードに対する
     * insert intention ロックの交差）で失敗していたが、{@code SHOW ENGINE INNODB STATUS}
     * の実測で「{@code __TAX_CODE_LOCK__} 行への {@code FOR UPDATE} がスタックトレースに
     * 一切登場しない」ことが判明し、真因を辿ったところ<b>ロック行自体がテストDBに
     * 実在しなかった</b>（{@code application-test.yml} の {@code flyway.enabled=false}・
     * {@code ddl-auto=create} により、V222 migration の seed INSERT がテストに一切適用されない
     * ため）。{@code lockTaxCodeLockRowForUpdate()} は0件を返し、{@code FOR UPDATE} は
     * 何も掴まず完全に空振りしていた＝排他は最初から機能していなかった。
     * ギャップロックの交差はその<b>二次症状</b>に過ぎなかった。
     * IT側でロック行を用意すれば（{@code PriceRevisionOverlapConcurrencyIT} が
     * plans 行を自前で用意しているのと同じ既存の流儀）本番同様に直列化され、
     * 分離レベルを変えずに green化することを実測確認済み。本番コード側の実装は無罪であり、
     * 変更していない。</p>
     */
    @Transactional
    public BillingTaxCodeView create(BillingTaxCodeCreateRequest request) {
        normalizeStripeTaxCode(request.stripeTaxCode());
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
                .stripeTaxCode(normalizeStripeTaxCode(request.stripeTaxCode()))
                .validFrom(request.validFrom())
                .validUntil(request.validUntil())
                .enabled(request.enabled())
                .build();
        return BillingTaxCodeView.from(repository.save(entity));
    }

    /** AC-6/AC-10: 表示名・stripeTaxCode・validUntil・enabled のみ更新可能。 */
    @Transactional
    public BillingTaxCodeView update(UUID id, BillingTaxCodeUpdateRequest request) {
        normalizeStripeTaxCode(request.stripeTaxCode());
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
        existing.setStripeTaxCode(normalizeStripeTaxCode(request.stripeTaxCode()));
        existing.setValidUntil(request.validUntil());
        existing.setEnabled(request.enabled());
        return BillingTaxCodeView.from(repository.save(existing));
    }

    /**
     * Stripe 側税コードの形式検証（{@code ^txcd_\d{8}$}）。null・空白は「未設定」として null を返す
     * （AC-85: Product に tax_code を付けない）。形式違反は400 {@code INVALID_STRIPE_TAX_CODE}。
     */
    static String normalizeStripeTaxCode(String stripeTaxCode) {
        if (stripeTaxCode == null || stripeTaxCode.isBlank()) {
            return null;
        }
        if (!STRIPE_TAX_CODE_PATTERN.matcher(stripeTaxCode).matches()) {
            throw new BusinessException(PriceRevisionErrorCode.INVALID_STRIPE_TAX_CODE);
        }
        return stripeTaxCode;
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
    public BillingTaxCodeView resolveEffective(String code, Instant at) {
        return repository.findEffectiveAt(code, at)
                .filter(BillingTaxCodeEntity::isEnabled)
                .map(BillingTaxCodeView::from)
                .orElseThrow(() -> new BusinessException(PriceRevisionErrorCode.TAX_CODE_NOT_FOUND));
    }
}
