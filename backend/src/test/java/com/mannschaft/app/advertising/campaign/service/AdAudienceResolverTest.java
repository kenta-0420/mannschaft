package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.dto.EstimatedReachRangeResponse;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.enums.EstimatedReachRange;
import com.mannschaft.app.advertising.campaign.repository.AdAudienceSegmentRepository;
import com.mannschaft.app.advertising.campaign.service.evaluator.AdSegmentEvaluator;
import com.mannschaft.app.advertising.campaign.service.evaluator.UnsupportedSegmentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * F09.17 Phase 11-b α {@link AdAudienceResolver} 単体テスト。
 *
 * <p>レンジ enum 化のしきい値・INCLUDE/EXCLUDE 論理・PII 漏洩防止・OOM 耐性をカバー。</p>
 */
@ExtendWith(MockitoExtension.class)
class AdAudienceResolverTest {

    @Mock
    private AdAudienceSegmentRepository segmentRepository;

    /** evaluator は List で注入する。テストでは inline で組み立てる。 */
    private final List<AdSegmentEvaluator> evaluators = new ArrayList<>();

    private AdAudienceResolver resolver;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        // List<AdSegmentEvaluator> は @Mock の自動注入対象外なので、手動でインスタンス化する
        evaluators.clear();
        resolver = new AdAudienceResolver(segmentRepository, evaluators);
    }

    // ------------------------------------------------------------------
    // estimateReach — レンジ enum 化のしきい値検証
    // ------------------------------------------------------------------

    @Test
    @DisplayName("estimateReach: 70人 → UNDER_100（個別特定リスク回避で非表示）")
    void estimateReach_under100() {
        UUID campaignId = UUID.randomUUID();
        AdAudienceSegment seg = segment(campaignId, AdSegmentType.LOCALE, AdSegmentInclusionMode.INCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(seg));
        evaluators.add(stubEvaluator(AdSegmentType.LOCALE, generateUserIds(70)));

        EstimatedReachRangeResponse res = resolver.estimateReach(campaignId);

        assertThat(res.range()).isEqualTo(EstimatedReachRange.UNDER_100);
        assertThat(res.label()).contains("100人未満");
    }

    @Test
    @DisplayName("estimateReach: 250人 → RANGE_100_500")
    void estimateReach_range100to500() {
        UUID campaignId = UUID.randomUUID();
        AdAudienceSegment seg = segment(campaignId, AdSegmentType.LOCALE, AdSegmentInclusionMode.INCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(seg));
        evaluators.add(stubEvaluator(AdSegmentType.LOCALE, generateUserIds(250)));

        EstimatedReachRangeResponse res = resolver.estimateReach(campaignId);

        assertThat(res.range()).isEqualTo(EstimatedReachRange.RANGE_100_500);
    }

    @Test
    @DisplayName("estimateReach: 1,200人 → RANGE_1K_5K")
    void estimateReach_range1kTo5k() {
        UUID campaignId = UUID.randomUUID();
        AdAudienceSegment seg = segment(campaignId, AdSegmentType.LOCALE, AdSegmentInclusionMode.INCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(seg));
        evaluators.add(stubEvaluator(AdSegmentType.LOCALE, generateUserIds(1_200)));

        EstimatedReachRangeResponse res = resolver.estimateReach(campaignId);

        assertThat(res.range()).isEqualTo(EstimatedReachRange.RANGE_1K_5K);
    }

    @Test
    @DisplayName("estimateReach: 150,000人 → OVER_100K")
    void estimateReach_over100k() {
        UUID campaignId = UUID.randomUUID();
        AdAudienceSegment seg = segment(campaignId, AdSegmentType.LOCALE, AdSegmentInclusionMode.INCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(seg));
        evaluators.add(stubEvaluator(AdSegmentType.LOCALE, generateUserIds(150_000)));

        EstimatedReachRangeResponse res = resolver.estimateReach(campaignId);

        assertThat(res.range()).isEqualTo(EstimatedReachRange.OVER_100K);
    }

    // ------------------------------------------------------------------
    // streamCandidateUserIds — INCLUDE / EXCLUDE 論理演算
    // ------------------------------------------------------------------

    @Test
    @DisplayName("streamCandidateUserIds: INCLUDE のみ（同一 type は OR、異 type は AND）")
    void streamCandidateUserIds_includeOnly() {
        UUID campaignId = UUID.randomUUID();
        // LOCALE INCLUDE: {1,2,3,4,5}
        AdAudienceSegment localeInclude = segment(campaignId, AdSegmentType.LOCALE, AdSegmentInclusionMode.INCLUDE);
        // ORG_TYPE INCLUDE: {3,4,5,6,7}
        AdAudienceSegment orgInclude = segment(campaignId, AdSegmentType.ORG_TYPE, AdSegmentInclusionMode.INCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(localeInclude, orgInclude));

        evaluators.add(stubEvaluator(AdSegmentType.LOCALE, Set.of(1L, 2L, 3L, 4L, 5L)));
        evaluators.add(stubEvaluator(AdSegmentType.ORG_TYPE, Set.of(3L, 4L, 5L, 6L, 7L)));

        Set<Long> result = resolver.streamCandidateUserIds(campaignId).collect(Collectors.toSet());

        // 異 type は AND → 積集合 {3,4,5}
        assertThat(result).containsExactlyInAnyOrder(3L, 4L, 5L);
    }

    @Test
    @DisplayName("streamCandidateUserIds: INCLUDE + EXCLUDE（最後に差し引かれる）")
    void streamCandidateUserIds_includeAndExclude() {
        UUID campaignId = UUID.randomUUID();
        AdAudienceSegment localeInclude = segment(campaignId, AdSegmentType.LOCALE, AdSegmentInclusionMode.INCLUDE);
        AdAudienceSegment orgExclude = segment(campaignId, AdSegmentType.ORG_TYPE, AdSegmentInclusionMode.EXCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(localeInclude, orgExclude));

        evaluators.add(stubEvaluator(AdSegmentType.LOCALE, Set.of(1L, 2L, 3L, 4L, 5L)));
        evaluators.add(stubEvaluator(AdSegmentType.ORG_TYPE, Set.of(3L, 4L)));

        Set<Long> result = resolver.streamCandidateUserIds(campaignId).collect(Collectors.toSet());

        assertThat(result).containsExactlyInAnyOrder(1L, 2L, 5L);
    }

    // ------------------------------------------------------------------
    // countCandidates — 大量件数で OOM しない
    // ------------------------------------------------------------------

    @Test
    @DisplayName("countCandidates: 10万件返却でも OOM しない（Set サイズ確認のみ）")
    void countCandidates_handles100k() {
        UUID campaignId = UUID.randomUUID();
        AdAudienceSegment seg = segment(campaignId, AdSegmentType.LOCALE, AdSegmentInclusionMode.INCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(seg));
        evaluators.add(stubEvaluator(AdSegmentType.LOCALE, generateUserIds(100_000)));

        long count = resolver.countCandidates(campaignId);

        assertThat(count).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("countCandidates: INCLUDE 1件・EXCLUDE無しの高速経路では resolveUserIds を一度も呼ばない")
    void countCandidates_singleInclude_neverResolvesUserIds() {
        UUID campaignId = UUID.randomUUID();
        AdAudienceSegment seg = segment(campaignId, AdSegmentType.LOCALE, AdSegmentInclusionMode.INCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(seg));

        AdSegmentEvaluator evaluator = org.mockito.Mockito.mock(AdSegmentEvaluator.class);
        org.mockito.Mockito.when(evaluator.supports(AdSegmentType.LOCALE)).thenReturn(true);
        org.mockito.Mockito.when(evaluator.countUserIds(seg)).thenReturn(42L);
        evaluators.add(evaluator);

        long count = resolver.countCandidates(campaignId);

        assertThat(count).isEqualTo(42L);
        org.mockito.Mockito.verify(evaluator, org.mockito.Mockito.never()).resolveUserIds(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(evaluator, org.mockito.Mockito.times(1)).countUserIds(seg);
    }

    @Test
    @DisplayName("countCandidates: 複数セグメント（積集合）は従来どおり resolveUserIds 経由で正しい件数を返す")
    void countCandidates_multipleSegments_usesResolveUserIds() {
        UUID campaignId = UUID.randomUUID();
        AdAudienceSegment localeInclude = segment(campaignId, AdSegmentType.LOCALE, AdSegmentInclusionMode.INCLUDE);
        AdAudienceSegment orgInclude = segment(campaignId, AdSegmentType.ORG_TYPE, AdSegmentInclusionMode.INCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(localeInclude, orgInclude));

        AdSegmentEvaluator localeEvaluator = org.mockito.Mockito.mock(AdSegmentEvaluator.class);
        org.mockito.Mockito.when(localeEvaluator.supports(AdSegmentType.LOCALE)).thenReturn(true);
        org.mockito.Mockito.when(localeEvaluator.resolveUserIds(localeInclude)).thenReturn(Set.of(1L, 2L, 3L, 4L, 5L));
        AdSegmentEvaluator orgEvaluator = org.mockito.Mockito.mock(AdSegmentEvaluator.class);
        org.mockito.Mockito.when(orgEvaluator.supports(AdSegmentType.ORG_TYPE)).thenReturn(true);
        org.mockito.Mockito.when(orgEvaluator.resolveUserIds(orgInclude)).thenReturn(Set.of(3L, 4L, 5L, 6L, 7L));
        evaluators.add(localeEvaluator);
        evaluators.add(orgEvaluator);

        long count = resolver.countCandidates(campaignId);

        // 異 type は AND → 積集合 {3,4,5} = 3件
        assertThat(count).isEqualTo(3L);
        org.mockito.Mockito.verify(localeEvaluator, org.mockito.Mockito.never()).countUserIds(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verify(orgEvaluator, org.mockito.Mockito.never()).countUserIds(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("countCandidates: INCLUDE + EXCLUDE がある場合は高速経路を使わず正しい差集合件数を返す")
    void countCandidates_includeAndExclude_usesResolveUserIds() {
        UUID campaignId = UUID.randomUUID();
        AdAudienceSegment localeInclude = segment(campaignId, AdSegmentType.LOCALE, AdSegmentInclusionMode.INCLUDE);
        AdAudienceSegment orgExclude = segment(campaignId, AdSegmentType.ORG_TYPE, AdSegmentInclusionMode.EXCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(localeInclude, orgExclude));

        evaluators.add(stubEvaluator(AdSegmentType.LOCALE, Set.of(1L, 2L, 3L, 4L, 5L)));
        evaluators.add(stubEvaluator(AdSegmentType.ORG_TYPE, Set.of(3L, 4L)));

        long count = resolver.countCandidates(campaignId);

        assertThat(count).isEqualTo(3L);
    }

    // ------------------------------------------------------------------
    // PII 漏洩防止: Service の公開 API 戻り値型に user_id 単体型が無いことを反射で確認
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PII 漏洩防止: estimateReach 戻り値型に Long/user_id 単体が含まれない")
    void estimateReach_returnsNoUserId() throws NoSuchMethodException {
        Method m = AdAudienceResolver.class.getMethod("estimateReach", UUID.class);
        Class<?> returnType = m.getReturnType();

        // 戻り値は EstimatedReachRangeResponse のみ
        assertThat(returnType).isEqualTo(EstimatedReachRangeResponse.class);
        // record の構成要素に Long が無いことを確認（PII 安全）
        for (Field f : returnType.getDeclaredFields()) {
            assertThat(f.getType())
                    .as("EstimatedReachRangeResponse に user_id (Long) フィールドがあってはならない: %s", f.getName())
                    .isNotEqualTo(Long.class)
                    .isNotEqualTo(long.class);
        }
    }

    // ------------------------------------------------------------------
    // 未サポートセグメントは UnsupportedSegmentException（対処療法禁止）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("未サポートセグメントは UnsupportedSegmentException を投げる（空集合化禁止）")
    void unsupportedSegment_throws() {
        UUID campaignId = UUID.randomUUID();
        AdAudienceSegment seg = segment(campaignId, AdSegmentType.AGE_RANGE, AdSegmentInclusionMode.INCLUDE);
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of(seg));
        // AGE_RANGE evaluator は登録しない → サポート無し

        assertThatThrownBy(() -> resolver.streamCandidateUserIds(campaignId).count())
                .isInstanceOf(UnsupportedSegmentException.class);
    }

    @Test
    @DisplayName("セグメント未設定のキャンペーンは空ストリームを返す")
    void noSegment_returnsEmpty() {
        UUID campaignId = UUID.randomUUID();
        given(segmentRepository.findByCampaignId(campaignId)).willReturn(List.of());

        long count = resolver.streamCandidateUserIds(campaignId).count();
        assertThat(count).isZero();
        assertThat(resolver.estimateReach(campaignId).range()).isEqualTo(EstimatedReachRange.UNDER_100);
    }

    // ------------------------------------------------------------------
    // streamCandidateUserIds は Service レイヤー外に PII を流さない（戻り値型は Stream<Long>）
    // → Service の package 上で AdAudienceResolver を使う側で Long ストリームを
    //   Controller に渡してはならない（呼び出しグレップで担保するためテストではドキュメント化）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("streamCandidateUserIds の戻り値ジェネリック型は Long である（Service 内のみで消費前提）")
    void streamCandidateUserIds_returnsLongStream() throws NoSuchMethodException {
        Method m = AdAudienceResolver.class.getMethod("streamCandidateUserIds", UUID.class);
        Type generic = m.getGenericReturnType();
        assertThat(generic).isInstanceOf(ParameterizedType.class);
        ParameterizedType pt = (ParameterizedType) generic;
        assertThat(pt.getRawType()).isEqualTo(Stream.class);
        assertThat(pt.getActualTypeArguments()[0]).isEqualTo(Long.class);
        // 呼び出し側（Controller / DTO）がこれを通すとレビューで弾く運用契約 — 第二陣 AdDispatcher への申し送り。
    }

    // ------------------------------------------------------------------
    // ヘルパー
    // ------------------------------------------------------------------

    private static AdAudienceSegment segment(UUID campaignId, AdSegmentType type, AdSegmentInclusionMode mode) {
        AdAudienceSegment s = AdAudienceSegment.builder()
                .campaignId(campaignId)
                .segmentType(type)
                .segmentValue("{}")
                .inclusionMode(mode)
                .build();
        s.setId(UUID.randomUUID());
        return s;
    }

    private static Set<Long> generateUserIds(int count) {
        return LongStream.rangeClosed(1, count).boxed().collect(Collectors.toCollection(HashSet::new));
    }

    private static AdSegmentEvaluator stubEvaluator(AdSegmentType type, Set<Long> userIds) {
        return new AdSegmentEvaluator() {
            @Override
            public boolean supports(AdSegmentType t) {
                return t == type;
            }

            @Override
            public Set<Long> resolveUserIds(AdAudienceSegment segment) {
                return new HashSet<>(userIds);
            }

            @Override
            public long countUserIds(AdAudienceSegment segment) {
                return userIds.size();
            }
        };
    }
}
