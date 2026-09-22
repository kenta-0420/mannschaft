package com.mannschaft.app.billing.tax;

import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 価格改定戦役 第1隊: {@link BillingTaxCodeService}（税コードマスタ CRUD・決定6改訂）の試練（試練・A群）。
 *
 * <p>{@link BillingTaxCodeService} / {@link BillingTaxCodeRepository} / {@link BillingTaxCodeEntity} は
 * 本試練の時点で未実装であり、コンパイルエラーとして red になることを是とする
 * （`.claude/campaigns/price-rev-plan-v3.md` 決定6・AC-4〜AC-16）。</p>
 *
 * <p>ロック行 {@code __TAX_CODE_LOCK__} を介した直列化（AC-5/AC-6）は、Service が
 * create/update のたびに {@code BillingTaxCodeRepository#lockTaxCodeLockRowForUpdate(Instant)} を
 * 呼び出していることをモック検証で担保する（実際の DB 直列化効果は
 * {@link BillingTaxCodeLockConcurrencyIT} が IT で検証する＝AC-11）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingTaxCodeService 試練（AC-4〜AC-10・AC-12〜AC-16）")
class BillingTaxCodeServiceTest {

    @Mock
    private BillingTaxCodeRepository repository;

    private BillingTaxCodeService service;

    private static final UUID LOCK_ROW_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new BillingTaxCodeService(repository);
        BillingTaxCodeEntity lockRow = BillingTaxCodeEntity.builder()
                .code("__TAX_CODE_LOCK__")
                .enabled(false)
                .validFrom(Instant.EPOCH)
                .rateBasisPoints(0)
                .displayName("lock")
                .build();
        lenient().when(repository.lockTaxCodeLockRowForUpdate(any())).thenReturn(lockRow);
    }

    private BillingTaxCodeEntity taxCode(String code, Instant from, Instant until, boolean enabled) {
        return BillingTaxCodeEntity.builder()
                .id(UUID.randomUUID())
                .code(code)
                .displayName(code)
                .rateBasisPoints(1000)
                .validFrom(from)
                .validUntil(until)
                .enabled(enabled)
                .build();
    }

    @Test
    @DisplayName("AC-4: 有効な税コード一覧を返し、__TAX_CODE_LOCK__ 行は含まれない")
    void ac4_listExcludesLockRow() {
        given(repository.findAllVisible()).willReturn(List.of(
                taxCode("JP_STANDARD_10", Instant.EPOCH, null, true),
                taxCode("JP_REDUCED_8", Instant.EPOCH, null, true)));

        List<BillingTaxCodeEntity> result = service.list();

        assertThat(result).extracting(BillingTaxCodeEntity::getCode)
                .doesNotContain("__TAX_CODE_LOCK__")
                .containsExactlyInAnyOrder("JP_STANDARD_10", "JP_REDUCED_8");
        verify(repository, never()).lockTaxCodeLockRowForUpdate(any());
    }

    @Test
    @DisplayName("AC-5: 新規税コード登録は 201 相当で成立し、登録前に __TAX_CODE_LOCK__ 行を FOR UPDATE ロックする")
    void ac5_createLocksLockRowBeforeInsert() {
        given(repository.findByCodeAndValidFromAndDeletedAtIsNull(anyString(), any()))
                .willReturn(Optional.empty());
        given(repository.findOverlapping(anyString(), any(), any())).willReturn(List.of());
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        BillingTaxCodeEntity created = service.create(new BillingTaxCodeCreateRequest(
                "JP_STANDARD_10", "標準税率", 1000, null, Instant.EPOCH, null, true));

        assertThat(created.getCode()).isEqualTo("JP_STANDARD_10");
        verify(repository, times(1)).lockTaxCodeLockRowForUpdate(any());
    }

    @Test
    @DisplayName("AC-6: PUT は表示名・stripeTaxCode・validUntil・enabled のみ更新でき、rateBasisPoints/code/validFrom は変更不可（400）")
    void ac6_updateRejectsImmutableFields() {
        UUID id = UUID.randomUUID();
        BillingTaxCodeEntity existing = taxCode("JP_STANDARD_10", Instant.EPOCH, null, true);
        existing.setId(id);
        given(repository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.of(existing));
        given(repository.findOverlapping(anyString(), any(), any())).willReturn(List.of());
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        BillingTaxCodeEntity updated = service.update(id, new BillingTaxCodeUpdateRequest(
                "標準税率(改)", "txcd_new", Instant.parse("2027-01-01T00:00:00Z"), false));

        assertThat(updated.getDisplayName()).isEqualTo("標準税率(改)");
        assertThat(updated.getStripeTaxCode()).isEqualTo("txcd_new");
        assertThat(updated.isEnabled()).isFalse();
        verify(repository, times(1)).lockTaxCodeLockRowForUpdate(any());

        // rateBasisPoints/code/validFrom はリクエスト DTO に存在しない＝コンパイル時点で変更不可を強制する
        // 設計であること自体を、DTO が rateBasisPoints/code/validFrom の setter 相当を持たないことで確認する。
        for (var field : BillingTaxCodeUpdateRequest.class.getDeclaredFields()) {
            assertThat(field.getName())
                    .as("BillingTaxCodeUpdateRequest は rateBasisPoints/code/validFrom を持ってはならない")
                    .isNotIn("rateBasisPoints", "code", "validFrom");
        }
    }

    @Test
    @DisplayName("AC-7: DELETE は論理削除（deletedAt が設定される）")
    void ac7_deleteIsSoftDelete() {
        UUID id = UUID.randomUUID();
        BillingTaxCodeEntity existing = taxCode("JP_STANDARD_10", Instant.EPOCH, null, true);
        existing.setId(id);
        given(repository.findByIdAndDeletedAtIsNull(id)).willReturn(Optional.of(existing));
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service.delete(id);

        assertThat(existing.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("AC-8: 同一 code で validFrom の異なる行は複数登録できる")
    void ac8_sameCodeDifferentValidFromAllowed() {
        given(repository.findByCodeAndValidFromAndDeletedAtIsNull(anyString(), any()))
                .willReturn(Optional.empty());
        given(repository.findOverlapping(anyString(), any(), any())).willReturn(List.of());
        given(repository.save(any())).willAnswer(inv -> inv.getArgument(0));

        BillingTaxCodeEntity revised = service.create(new BillingTaxCodeCreateRequest(
                "JP_STANDARD_10", "標準税率(改定)", 1100, null,
                Instant.parse("2027-04-01T00:00:00Z"), null, true));

        assertThat(revised.getCode()).isEqualTo("JP_STANDARD_10");
        assertThat(revised.getRateBasisPoints()).isEqualTo(1100);
    }

    @Test
    @DisplayName("AC-9: 同一 code かつ同一 validFrom の重複登録は 409（uk_btc_code_from）")
    void ac9_sameCodeSameValidFrom_conflict409() {
        given(repository.findByCodeAndValidFromAndDeletedAtIsNull("JP_STANDARD_10", Instant.EPOCH))
                .willReturn(Optional.of(taxCode("JP_STANDARD_10", Instant.EPOCH, null, true)));

        assertThatThrownBy(() -> service.create(new BillingTaxCodeCreateRequest(
                "JP_STANDARD_10", "重複", 1000, null, Instant.EPOCH, null, true)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.TAX_CODE_DUPLICATE);
    }

    @Test
    @DisplayName("AC-10: 同一 code の有効期間が重なる登録・更新は 409（ロック行判定）")
    void ac10_overlappingPeriod_conflict409() {
        given(repository.findByCodeAndValidFromAndDeletedAtIsNull(anyString(), any()))
                .willReturn(Optional.empty());
        given(repository.findOverlapping("JP_STANDARD_10", Instant.parse("2027-01-01T00:00:00Z"), null))
                .willReturn(List.of(taxCode("JP_STANDARD_10", Instant.EPOCH, null, true)));

        assertThatThrownBy(() -> service.create(new BillingTaxCodeCreateRequest(
                "JP_STANDARD_10", "重複期間", 1200, null,
                Instant.parse("2027-01-01T00:00:00Z"), null, true)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.TAX_CODE_OVERLAP);
    }

    @Test
    @DisplayName("AC-12: 税率解決は band の effectiveFrom 時点で有効な行を選ぶ（境界は半開区間）")
    void ac12_resolveEffective_halfOpenBoundary() {
        Instant from = Instant.parse("2027-01-01T00:00:00Z");
        Instant until = Instant.parse("2028-01-01T00:00:00Z");
        BillingTaxCodeEntity row = taxCode("JP_STANDARD_10", from, until, true);
        given(repository.findEffectiveAt("JP_STANDARD_10", from)).willReturn(Optional.of(row));
        given(repository.findEffectiveAt("JP_STANDARD_10", until)).willReturn(Optional.empty());

        assertThat(service.resolveEffective("JP_STANDARD_10", from)).isEqualTo(row);
        assertThatThrownBy(() -> service.resolveEffective("JP_STANDARD_10", until))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("AC-14: 存在しない taxCode の解決は 400")
    void ac14_unknownTaxCode_400() {
        given(repository.findEffectiveAt("NOT_EXIST", Instant.EPOCH)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveEffective("NOT_EXIST", Instant.EPOCH))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.TAX_CODE_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-15: enabled=false または有効期間外の taxCode 指定は 400")
    void ac15_disabledOrOutOfRange_400() {
        Instant at = Instant.parse("2027-06-01T00:00:00Z");
        given(repository.findEffectiveAt("JP_STANDARD_10", at)).willReturn(
                Optional.of(taxCode("JP_STANDARD_10", Instant.EPOCH, null, false)));

        assertThatThrownBy(() -> service.resolveEffective("JP_STANDARD_10", at))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.TAX_CODE_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-16: 税コードが0件（ロック行以外なし）で価格改定を作成すると 400（500にしない）")
    void ac16_noTaxCodes_400NotServerError() {
        given(repository.findEffectiveAt(anyString(), any())).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveEffective("JP_STANDARD_10", Instant.now()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.TAX_CODE_NOT_FOUND);
    }

    @Test
    @DisplayName("決定6: __TAX_CODE_LOCK__ 自体は一覧・解決 API の対象から常に除外される")
    void lockRowNeverResolvable() {
        BillingTaxCodeEntity lockRow = repository.lockTaxCodeLockRowForUpdate(Instant.EPOCH);
        Instant at = Instant.now();
        given(repository.findEffectiveAt("__TAX_CODE_LOCK__", at)).willReturn(Optional.of(lockRow));

        assertThatThrownBy(() -> service.resolveEffective("__TAX_CODE_LOCK__", at))
                .as("ロック行は enabled=false なので通常解決からは常に拒否される")
                .isInstanceOf(BusinessException.class);
    }
}
