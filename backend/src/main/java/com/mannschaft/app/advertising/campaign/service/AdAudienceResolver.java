package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.dto.EstimatedReachRangeResponse;
import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.enums.EstimatedReachRange;
import com.mannschaft.app.advertising.campaign.repository.AdAudienceSegmentRepository;
import com.mannschaft.app.advertising.campaign.service.evaluator.AdSegmentEvaluator;
import com.mannschaft.app.advertising.campaign.service.evaluator.UnsupportedSegmentException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * F09.17 配信ターゲット解決サービス。
 *
 * <p>「キャンペーンに紐づくセグメント条件」から「配信対象 user_id 集合」を算出する。
 * 設計書「§9 解決済み事項 #4」に基づき、推定リーチは個別 user_id を返さずレンジ enum で返す。</p>
 *
 * <h2>F09.2 偵察結果 — 案 (B) 採用</h2>
 * <p>偵察の結果、{@code com.mannschaft.app.promotion} ドメインには {@code SegmentEvaluator}
 * クラスが存在しなかった（{@code PromotionSegmentEntity} はあるが評価ロジックは未実装）。
 * したがって F09.2 ドメイン本体への侵入を避けつつ、F09.17 ドメイン内に
 * {@link AdSegmentEvaluator} 戦略パターンを最小実装した。F09.2 が将来 SegmentEvaluator を
 * 公開した時点で、{@link AdSegmentEvaluator} 実装を「F09.2 委譲アダプタ」に差し替えるだけで
 * 切替可能。</p>
 *
 * <h2>論理演算</h2>
 * <ul>
 *   <li>同一 {@link AdSegmentType} の複数 INCLUDE は OR（和集合）</li>
 *   <li>異なる type 間の INCLUDE は AND（積集合）</li>
 *   <li>EXCLUDE は最後に差し引く（type 内 OR でまとめてから差集合）</li>
 *   <li>INCLUDE が一つも無い場合は「全 ACTIVE ユーザー」を仮想的に対象とせず、空集合扱い
 *       （対処療法回避：明示的なターゲティング無しの配信はビジネス上禁止）</li>
 * </ul>
 *
 * <h2>PII 漏洩防止</h2>
 * <ul>
 *   <li>{@link #estimateReach(UUID)} は {@link EstimatedReachRangeResponse} のみを返し、
 *       user_id を一切含まない</li>
 *   <li>{@link #streamCandidateUserIds(UUID)} は Service レイヤー限定で消費される前提。
 *       Controller / DTO へ直接返してはならない。</li>
 *   <li>{@link #countCandidates(UUID)} は内部 / 集計用</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdAudienceResolver {

    private final AdAudienceSegmentRepository segmentRepository;
    private final List<AdSegmentEvaluator> evaluators;

    /**
     * 推定リーチを PII 安全なレンジで返す。
     *
     * <p>100 人未満は {@link EstimatedReachRange#UNDER_100} を返し、フロントは「条件に合致するユーザーが
     * 少なすぎます」と表示する想定（個別特定リスク回避）。</p>
     *
     * @param campaignId キャンペーン ID
     * @return レンジ表示レスポンス
     */
    public EstimatedReachRangeResponse estimateReach(UUID campaignId) {
        long count = countCandidates(campaignId);
        return EstimatedReachRangeResponse.of(EstimatedReachRange.fromCount(count));
    }

    /**
     * 配信ターゲットの user_id を Stream で返す。
     *
     * <p>戻り値は Service レイヤー内でのみ消費し、Controller 経由で外に出さない。
     * 呼び出し側は try-with-resources で確実にクローズすること
     * （現状はメモリ集合からの stream なので close は no-op だが、将来 chunked-load に
     * 切り替える際の前提を守るため）。</p>
     *
     * @param campaignId キャンペーン ID
     * @return user_id ストリーム
     */
    public Stream<Long> streamCandidateUserIds(UUID campaignId) {
        Set<Long> resolved = resolve(campaignId);
        return resolved.stream();
    }

    /**
     * 配信ターゲット数を返す。{@link #estimateReach(UUID)} の内部利用 + バッチでの効率取得用。
     *
     * <p>セグメントが INCLUDE 1件のみ・EXCLUDE 0件の場合に限り、{@link AdSegmentEvaluator#countUserIds}
     * の COUNT クエリ結果をそのまま返し、user_id 集合のメモリ展開（{@link #resolve(UUID)}）を回避する。
     * それ以外（複数セグメントの積集合・差集合が必要な場合）は従来どおり {@link #resolve(UUID)} の
     * size() を返す（振る舞いは完全に維持する）。</p>
     */
    public long countCandidates(UUID campaignId) {
        List<AdAudienceSegment> segments = segmentRepository.findByCampaignId(campaignId);
        if (segments.size() == 1 && segments.get(0).getInclusionMode() == AdSegmentInclusionMode.INCLUDE) {
            AdAudienceSegment onlySegment = segments.get(0);
            long count = countUserIds(onlySegment);
            log.info("countCandidates 高速経路（INCLUDE 1件のみ）: campaignId={}, segmentType={}, count={}",
                    campaignId, onlySegment.getSegmentType(), count);
            return count;
        }
        return resolve(campaignId).size();
    }

    // ----------------------------------------------------------------------
    // 内部ロジック
    // ----------------------------------------------------------------------

    /**
     * キャンペーンのセグメント集合を評価し、配信ターゲット集合を返す。
     */
    private Set<Long> resolve(UUID campaignId) {
        List<AdAudienceSegment> segments = segmentRepository.findByCampaignId(campaignId);
        if (segments.isEmpty()) {
            log.debug("キャンペーン {} にセグメントが設定されていません。空集合を返します。", campaignId);
            return Set.of();
        }

        Map<AdSegmentType, Set<Long>> includeByType = new EnumMap<>(AdSegmentType.class);
        Set<Long> excludeUnion = new HashSet<>();

        for (AdAudienceSegment seg : segments) {
            Set<Long> matched = evaluate(seg);
            log.info("resolve セグメント評価完了: campaignId={}, segmentType={}, inclusionMode={}, matchedCount={}",
                    campaignId, seg.getSegmentType(), seg.getInclusionMode(), matched.size());
            if (seg.getInclusionMode() == AdSegmentInclusionMode.INCLUDE) {
                // 同一 type は OR で和集合化
                includeByType.merge(seg.getSegmentType(), matched, (a, b) -> {
                    a.addAll(b);
                    return a;
                });
            } else {
                excludeUnion.addAll(matched);
            }
        }

        if (includeByType.isEmpty()) {
            // INCLUDE が無い場合は配信対象を空とする（明示的指定無しの全体配信を禁止する設計）
            return Set.of();
        }

        // type 間は AND（積集合）
        Set<Long> result = null;
        for (Set<Long> ids : includeByType.values()) {
            if (result == null) {
                result = new HashSet<>(ids);
            } else {
                result.retainAll(ids);
                if (result.isEmpty()) {
                    break;
                }
            }
        }
        if (result == null) {
            return Set.of();
        }

        // EXCLUDE を差し引く
        if (!excludeUnion.isEmpty()) {
            result.removeAll(excludeUnion);
        }
        log.info("resolve 最終結果: campaignId={}, resultCount={}", campaignId, result.size());
        return result;
    }

    /**
     * 単一セグメントを評価する。
     *
     * <p>例外の意味合い:</p>
     * <ul>
     *   <li>{@link UnsupportedSegmentException} — 該当 type の Evaluator が DI に登録されていない（戦略パターン未配備）</li>
     *   <li>{@code SegmentDataSourceNotAvailableException} — Evaluator は配備されているがデータソース（カラム / 表）が未整備
     *       （Evaluator 内部から投げられる）</li>
     * </ul>
     */
    private Set<Long> evaluate(AdAudienceSegment segment) {
        for (AdSegmentEvaluator evaluator : evaluators) {
            if (evaluator.supports(segment.getSegmentType())) {
                Set<Long> matched = evaluator.resolveUserIds(segment);
                return matched != null ? matched : Set.of();
            }
        }
        throw new UnsupportedSegmentException(segment.getSegmentType());
    }

    /**
     * 単一セグメントの該当件数のみを評価する（{@link #evaluate(AdAudienceSegment)} の件数版）。
     * {@link #countCandidates(UUID)} の高速経路（INCLUDE 1件のみ）専用。
     */
    private long countUserIds(AdAudienceSegment segment) {
        for (AdSegmentEvaluator evaluator : evaluators) {
            if (evaluator.supports(segment.getSegmentType())) {
                return evaluator.countUserIds(segment);
            }
        }
        throw new UnsupportedSegmentException(segment.getSegmentType());
    }
}
