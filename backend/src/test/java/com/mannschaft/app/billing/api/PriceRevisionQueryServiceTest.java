package com.mannschaft.app.billing.api;

import com.mannschaft.app.billing.BillingPriceBandVersionRepository;
import com.mannschaft.app.billing.BillingPriceCreationSource;
import com.mannschaft.app.billing.BillingPriceVersionEntity;
import com.mannschaft.app.billing.BillingPriceVersionRepository;
import com.mannschaft.app.billing.BillingPriceVersionStatus;
import com.mannschaft.app.billing.BillingProductKind;
import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.billing.PriceRevisionErrorCode;
import com.mannschaft.app.billing.api.dto.PriceRevisionBandResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionListQuery;
import com.mannschaft.app.billing.api.dto.PriceRevisionPageResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionResponse;
import com.mannschaft.app.billing.api.dto.PriceRevisionSummaryResponse;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 試練隊（第2陣）D群: 価格改定の取得・一覧（{@code GET /price-revisions/{id}} / {@code GET /price-revisions}）。
 *
 * <p>陣立て書 {@code .claude/campaigns/price-rev-plan-v3.md} D群（AC-54〜AC-65）の red 試練。
 * 対象は未実装の {@link PriceRevisionQueryService}（本テストが発注書）。試練隊（第1陣）の
 * {@code PriceRevisionCreateService}/{@code PriceRevisionController} と同じパッケージ・DTO
 * （{@code com.mannschaft.app.billing.api.dto.PriceRevisionResponse}）を再利用する。</p>
 *
 * <p>一覧は {@code effectiveFrom DESC, id DESC} の複合ソートを既定とし（AC-62/AC-62a）、
 * band 明細を含めない要約のみを返す（AC-65）。N+1 回避（AC-57）は
 * {@link BillingPriceBandVersionRepository#findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc}
 * の呼び出し回数で観測する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("試練D群: 価格改定の取得・一覧")
class PriceRevisionQueryServiceTest {

    @Mock private BillingPriceVersionRepository versionRepository;
    @Mock private BillingPriceBandVersionRepository bandRepository;

    private PriceRevisionQueryService service() {
        return new PriceRevisionQueryService(versionRepository, bandRepository);
    }

    @Test
    @DisplayName("AC-54: revision と band 一覧（status/provisionErrorCode/provisionAttempts/stripePriceRef 含む）を返す")
    void getReturnsRevisionWithBandDetails() {
        BillingPriceVersionEntity revision = version(BillingPriceVersionStatus.READY);
        var band = band(revision, BillingPriceVersionStatus.READY);
        band.setStripePriceRef("price_test_1");
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revision.getId()))
                .willReturn(List.of(band));

        PriceRevisionResponse response = service().getById(revision.getId());

        assertThat(response.getId()).isEqualTo(revision.getId());
        assertThat(response.getBands()).hasSize(1);
        PriceRevisionBandResponse bandResponse = response.getBands().get(0);
        assertThat(bandResponse.getStatus()).isEqualTo(BillingPriceVersionStatus.READY);
        assertThat(bandResponse.getStripePriceRef()).isEqualTo("price_test_1");
    }

    @Test
    @DisplayName("AC-55: 存在しない id は404（PriceRevisionErrorCode.REVISION_NOT_FOUND）")
    void getUnknownIdThrowsNotFound() {
        UUID missing = UUID.randomUUID();
        given(versionRepository.findByIdAndDeletedAtIsNull(missing)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().getById(missing))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PriceRevisionErrorCode.REVISION_NOT_FOUND);
    }

    @Test
    @DisplayName("AC-56: 論理削除済みは404相当（findByIdAndDeletedAtIsNull が空を返す経路のみを使う）")
    void getSoftDeletedThrowsNotFound() {
        UUID deletedId = UUID.randomUUID();
        given(versionRepository.findByIdAndDeletedAtIsNull(deletedId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().getById(deletedId)).isInstanceOf(BusinessException.class);
        // deletedAt 条件を持たない findById 系メソッドは絶対に呼ばない（論理削除の回避経路を塞ぐ）。
        verify(versionRepository, never()).findById(deletedId);
    }

    @Test
    @DisplayName("AC-57: band が N 件でも取得は親1回+子1回のクエリで完結する（N+1にならない）")
    void getDoesNotCauseNPlusOneQueries() {
        BillingPriceVersionEntity revision = version(BillingPriceVersionStatus.READY);
        List<com.mannschaft.app.billing.BillingPriceBandVersionEntity> bands = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            bands.add(band(revision, BillingPriceVersionStatus.READY));
        }
        given(versionRepository.findByIdAndDeletedAtIsNull(revision.getId())).willReturn(Optional.of(revision));
        given(bandRepository.findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revision.getId()))
                .willReturn(bands);

        service().getById(revision.getId());

        verify(versionRepository).findByIdAndDeletedAtIsNull(revision.getId());
        verify(bandRepository).findByPriceVersionIdAndDeletedAtIsNullOrderByBandNoAsc(revision.getId());
    }

    @Test
    @DisplayName("AC-58: PriceRevisionBandResponse は秘密・raw Stripe payload を持たない（型契約として固定）")
    void responseDoesNotExposeSecrets() {
        List<String> fieldNames = new ArrayList<>();
        Class<?> current = PriceRevisionBandResponse.class;
        while (current != null && current != Object.class) {
            for (var field : current.getDeclaredFields()) {
                fieldNames.add(field.getName().toLowerCase(Locale.ROOT));
            }
            current = current.getSuperclass();
        }
        assertThat(fieldNames)
                .as("PriceRevisionBandResponse は秘密・raw payload を持たない")
                .noneMatch(name -> name.contains("secret") || name.contains("rawstripe") || name.contains("payload"));
    }

    @Test
    @DisplayName("AC-60/AC-63: 検索条件を指定しない一覧は productKind/productKey 横断で全件を返す")
    void listWithoutFiltersReturnsAcrossProducts() {
        PriceRevisionListQuery query = new PriceRevisionListQuery(null, null, null, null, PageRequest.of(0, 20));
        BillingPriceVersionEntity planRevision = version(BillingPriceVersionStatus.ACTIVE);
        BillingPriceVersionEntity addonRevision = version(BillingPriceVersionStatus.DRAFT);
        addonRevision.setProductKind(BillingProductKind.ADDON);
        addonRevision.setProductKey("chat.basic");
        given(versionRepository.searchSummaries(query)).willReturn(List.of(planRevision, addonRevision));

        PriceRevisionPageResponse page = service().list(query);

        assertThat(page.items()).extracting(PriceRevisionSummaryResponse::productKind)
                .contains(BillingProductKind.PLAN, BillingProductKind.ADDON);
    }

    @Test
    @DisplayName("AC-61: 一覧はページングを持ち既定20件・上限100件（超過は作成できない）")
    void listHasPagingWithDefaultAndUpperBound() {
        assertThatThrownBy(() -> new PriceRevisionListQuery(null, null, null, null, PageRequest.of(0, 101)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AC-62: 一覧の既定ソートは effectiveFrom DESC, id DESC の複合キー")
    void listDefaultSortIsCompositeKey() {
        PriceRevisionListQuery query = new PriceRevisionListQuery(null, null, null, null, PageRequest.of(0, 20));

        assertThat(query.pageRequest().getSort().stream().toList())
                .extracting(Sort.Order::getProperty, o -> o.getDirection().name())
                .containsExactly(tuple("effectiveFrom", "DESC"), tuple("id", "DESC"));
    }

    @Test
    @DisplayName("AC-62a: 同一effectiveFromが3件以上でもページを跨いで重複・欠落しない")
    void listPagingAcrossSameEffectiveFromIsStableAndComplete() {
        Instant sameFrom = Instant.parse("2026-10-01T00:00:00Z");
        BillingPriceVersionEntity r1 = version(BillingPriceVersionStatus.SCHEDULED);
        BillingPriceVersionEntity r2 = version(BillingPriceVersionStatus.SCHEDULED);
        BillingPriceVersionEntity r3 = version(BillingPriceVersionStatus.SCHEDULED);
        for (BillingPriceVersionEntity r : List.of(r1, r2, r3)) {
            r.setEffectiveFrom(sameFrom);
        }
        List<BillingPriceVersionEntity> sortedById = List.of(r1, r2, r3).stream()
                .sorted((a, b) -> b.getId().compareTo(a.getId()))
                .toList();
        PriceRevisionListQuery page1Query = new PriceRevisionListQuery(null, null, null, null, PageRequest.of(0, 2));
        PriceRevisionListQuery page2Query = new PriceRevisionListQuery(null, null, null, null, PageRequest.of(1, 2));
        given(versionRepository.searchSummaries(page1Query)).willReturn(sortedById.subList(0, 2));
        given(versionRepository.searchSummaries(page2Query)).willReturn(sortedById.subList(2, 3));

        List<UUID> page1Ids = service().list(page1Query).items().stream()
                .map(PriceRevisionSummaryResponse::id).toList();
        List<UUID> page2Ids = service().list(page2Query).items().stream()
                .map(PriceRevisionSummaryResponse::id).toList();

        List<UUID> combined = new ArrayList<>(page1Ids);
        combined.addAll(page2Ids);
        assertThat(combined).hasSize(3).doesNotHaveDuplicates()
                .containsExactlyElementsOf(sortedById.stream().map(BillingPriceVersionEntity::getId).toList());
    }

    @Test
    @DisplayName("AC-65: 一覧応答要素は要約のみでband明細を含まない")
    void listItemsDoNotIncludeBandDetails() {
        List<String> summaryComponentNames = Arrays.stream(PriceRevisionSummaryResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(summaryComponentNames)
                .as("一覧要約は id/status/revisionNo/effectiveFrom/effectiveUntil/productKind/productKey/scopeKind のみ")
                .doesNotContain("bands");
    }

    private static BillingPriceVersionEntity version(BillingPriceVersionStatus status) {
        BillingPriceVersionEntity entity = BillingPriceVersionEntity.builder()
                .productKind(BillingProductKind.PLAN)
                .productKey("FULL")
                .scopeKind(EntitlementScopeKind.USER)
                .catalogRevision("rev-" + UUID.randomUUID())
                .revisionNo(1L)
                .status(status)
                .effectiveFrom(Instant.parse("2026-10-01T00:00:00Z"))
                .creationSource(BillingPriceCreationSource.SYSTEM_BACKFILL)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }

    private static com.mannschaft.app.billing.BillingPriceBandVersionEntity band(
            BillingPriceVersionEntity version, BillingPriceVersionStatus status) {
        var entity = com.mannschaft.app.billing.BillingPriceBandVersionEntity.builder()
                .productKind(version.getProductKind())
                .productKey(version.getProductKey())
                .scopeKind(version.getScopeKind())
                .priceVersionId(version.getId())
                .bandNo(1)
                .minMembers(1)
                .effectiveFrom(version.getEffectiveFrom())
                .status(status)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }
}
