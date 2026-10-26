package com.mannschaft.app.billing;

import com.mannschaft.app.billing.api.BillingApiIdempotencyEntity;
import com.mannschaft.app.billing.api.BillingCheckoutReconciliationEntity;
import com.mannschaft.app.billing.api.BillingCustomerEntity;
import com.mannschaft.app.billing.api.BillingInvoiceAdjustmentEntity;
import com.mannschaft.app.billing.api.BillingInvoiceEntity;
import com.mannschaft.app.billing.api.BillingInvoiceLineEntity;
import com.mannschaft.app.billing.api.BillingReturnStateNonceEntity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR6b-1 残務①②の棚卸し番人: {@code billing} ドメイン全体で「V196 等の migration には UNIQUE KEY が
 * あるのに、対応する {@link jakarta.persistence.Entity} の {@code @Table(uniqueConstraints=...)} に
 * 宣言されていない」宣言漏れを検出する。
 *
 * <p><b>発端</b>: {@code BillingContractChangeEntity} の {@code uk_bcc_invoice} が長らく Entity 側に
 * 宣言されておらず、結合テストの schema（Hibernate {@code ddl-auto=create} 生成・Flyway 不使用）に
 * だけ UNIQUE 制約が存在しない状態が放置されていた。{@code BillingContractChangeEntityDdlAlignmentTest}
 * は CHECK 制約（enum 値集合）だけを見ており、UNIQUE KEY を一度も比較していなかったためこの穴を
 * 一度も検出できなかった。本テストはその穴を billing ドメイン全体へ一般化して塞ぐ。</p>
 *
 * <p>本テストで新たに判明した同型の宣言漏れ（本 PR で Entity 側に追記して解消済み）:</p>
 * <ul>
 *   <li>{@code active_billing_contract_operation_pointers.uk_abcop_operation}</li>
 *   <li>{@code billing_price_versions.uk_bpv_identity} / {@code uk_bpv_revision_no} /
 *       {@code uk_bpv_catalog_revision}</li>
 *   <li>{@code billing_price_band_versions.uk_bpbv_stripe_price} / {@code uk_bpbv_revision_band}</li>
 *   <li>{@code billing_return_state_nonces.uk_brsn_nonce}</li>
 *   <li>{@code billing_contracts.uk_bc_psp_subscription}</li>
 * </ul>
 *
 * <p><b>対象外</b>: {@code billing_payer_handover_requests.uk_bphr_open_old_contract} は
 * {@code GENERATED ALWAYS AS (...) STORED} な生成列上の UNIQUE であり、通常の
 * {@code @UniqueConstraint} で素朴に再現すると生成列自体を Entity にマッピングする設計変更が
 * 必要になる（今回のスコープ外・別カード）。{@code billing_membership_price_adjustments} /
 * {@code billing_customer_migrations} は現時点で対応する Entity が存在しない（未実装フェーズ）ため対象外。</p>
 */
class BillingUniqueConstraintDdlAlignmentGuardTest {

    private static final Path MIGRATION_DIR = Paths.get("src/main/resources/db/migration");

    /** テーブル名を含むが billing ドメイン対象外のファイルを避けるための除外語。 */
    private static final List<String> EXCLUDE_KEYWORDS = List.of("promotion_billing_records");

    /** テーブル名 -> 対応する Entity クラス。 */
    private static final Map<String, Class<?>> TABLE_TO_ENTITY = Map.ofEntries(
            Map.entry("billing_contracts", BillingContractEntity.class),
            Map.entry("active_contract_pointers", ActiveContractPointerEntity.class),
            Map.entry("billing_customers", BillingCustomerEntity.class),
            Map.entry("billing_price_versions", BillingPriceVersionEntity.class),
            Map.entry("billing_price_band_versions", BillingPriceBandVersionEntity.class),
            Map.entry("billing_contract_operations", BillingContractOperationEntity.class),
            Map.entry("billing_contract_changes", BillingContractChangeEntity.class),
            Map.entry("active_billing_contract_operation_pointers",
                    ActiveBillingContractOperationPointerEntity.class),
            Map.entry("billing_invoices", BillingInvoiceEntity.class),
            Map.entry("billing_invoice_adjustments", BillingInvoiceAdjustmentEntity.class),
            Map.entry("billing_invoice_lines", BillingInvoiceLineEntity.class),
            Map.entry("billing_api_idempotencies", BillingApiIdempotencyEntity.class),
            Map.entry("billing_return_state_nonces", BillingReturnStateNonceEntity.class),
            Map.entry("billing_checkout_reconciliations", BillingCheckoutReconciliationEntity.class)
    );

    private static List<Path> billingMigrationFiles() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(MIGRATION_DIR, "*.sql")) {
            return StreamSupport.stream(stream.spliterator(), false)
                    .filter(p -> p.getFileName().toString().contains("billing")
                            || p.getFileName().toString().contains("payer_handover"))
                    .filter(p -> EXCLUDE_KEYWORDS.stream().noneMatch(p.getFileName().toString()::contains))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("migration ディレクトリが読めない: " + MIGRATION_DIR, e);
        }
    }

    private static Map<String, TreeSet<String>> entityUniqueConstraints(Class<?> entityType) {
        Table table = entityType.getAnnotation(Table.class);
        assertThat(table).as(entityType.getSimpleName() + " に @Table が付与されていること").isNotNull();
        Map<String, TreeSet<String>> result = new LinkedHashMap<>();
        for (UniqueConstraint uc : table.uniqueConstraints()) {
            result.put(uc.name(), Stream.of(uc.columnNames())
                    .collect(Collectors.toCollection(TreeSet::new)));
        }
        return result;
    }

    @TestFactory
    Stream<DynamicTest> uniqueKeysMatchEntityDeclarations() {
        List<Path> migrations = billingMigrationFiles();
        return TABLE_TO_ENTITY.entrySet().stream().map(entry -> {
            String tableName = entry.getKey();
            Class<?> entityType = entry.getValue();
            return DynamicTest.dynamicTest(
                    tableName + " (" + entityType.getSimpleName() + ") の UNIQUE KEY が migration と一致する",
                    () -> {
                        Map<String, TreeSet<String>> ddlKeys =
                                DdlUniqueKeyExtractor.extractUniqueKeys(migrations.stream(), tableName);
                        Map<String, TreeSet<String>> entityKeys = entityUniqueConstraints(entityType);

                        assertThat(entityKeys.keySet())
                                .as(tableName + ": Entity に無いのに migration には在る UNIQUE KEY（宣言漏れ）")
                                .containsExactlyInAnyOrderElementsOf(ddlKeys.keySet());

                        for (String name : ddlKeys.keySet()) {
                            assertThat(entityKeys.get(name))
                                    .as(tableName + "." + name + " の対象カラムが migration と一致すること")
                                    .isEqualTo(ddlKeys.get(name));
                        }
                    });
        });
    }
}
