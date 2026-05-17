package com.mannschaft.app.advertising.campaign.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.advertising.campaign.dto.BlockCampaignRequest;
import com.mannschaft.app.advertising.campaign.dto.ReviewQueueItemResponse;
import com.mannschaft.app.advertising.campaign.entity.AdCampaignModerationLog;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaignChannel;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationAction;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.exception.AdCampaignErrorCode;
import com.mannschaft.app.advertising.campaign.repository.AdCampaignModerationLogRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignChannelRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.advertising.campaign.service.moderation.DetectedNgWord;
import com.mannschaft.app.advertising.campaign.service.moderation.ModerationCheckResult;
import com.mannschaft.app.advertising.campaign.service.moderation.SuggestedModerationAction;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F09.17 Phase 11-a メッセージ型キャンペーン モデレーションサービス。
 *
 * <p>SYSTEM_ADMIN が手動で行う審査キュー取得・承認・ブロック操作を担う。
 * 自動 NG 検知や通報 3 件で自動 SUSPEND は Phase 11-b スコープ外。</p>
 */
@Service
@RequiredArgsConstructor
public class AdCampaignModerationService {

    /** SYSTEM_ADMIN が確認すべき審査対象状態。 */
    private static final Set<AdModerationStatus> REVIEW_QUEUE_STATUSES =
            Set.of(AdModerationStatus.PENDING, AdModerationStatus.AUTO_FLAGGED);

    /** approve 可能なキャンペーン状態 (DRAFT / REVIEW のみ)。 */
    private static final Set<AdCampaignStatus> APPROVE_ALLOWED_STATUSES =
            Set.of(AdCampaignStatus.DRAFT, AdCampaignStatus.REVIEW);

    /** {@code ng_words_detected} JSON シリアライズ用 (Spring Boot 既定 ObjectMapper を利用)。 */
    private static final ObjectMapper NG_WORDS_JSON_MAPPER = new ObjectMapper();

    private final AdMessagingCampaignRepository campaignRepository;
    private final AdCampaignModerationLogRepository moderationLogRepository;
    private final AdMessagingCampaignChannelRepository campaignChannelRepository;
    private final AdContentModerator contentModerator;

    /**
     * SYSTEM_ADMIN 審査キューを取得する。
     *
     * <p>{@code moderation_status IN (PENDING, AUTO_FLAGGED)} のキャンペーンを
     * {@code created_at ASC} (古い順) で返す。</p>
     */
    @Transactional(readOnly = true)
    public Page<ReviewQueueItemResponse> getReviewQueue(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<AdMessagingCampaign> campaigns =
                campaignRepository.findByModerationStatusInAndDeletedAtIsNull(
                        REVIEW_QUEUE_STATUSES, pageable);
        return campaigns.map(ReviewQueueItemResponse::from);
    }

    /**
     * キャンペーンを承認する。
     *
     * <p>条件:
     * <ul>
     *   <li>{@code moderation_status} が {@code PENDING} または {@code AUTO_FLAGGED}</li>
     *   <li>{@code status} が {@code DRAFT} または {@code REVIEW}</li>
     * </ul>
     * いずれかを満たさない場合は {@link AdCampaignErrorCode#NOT_REVIEWABLE} を投げる。</p>
     *
     * <p>{@code moderation_status=APPROVED}, {@code status=APPROVED} に更新し、
     * {@code ad_campaign_moderation_logs} へ {@code action=APPROVED} の行を 1 件作成する。</p>
     */
    @Transactional
    public void approve(UUID campaignId, Long moderatorUserId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId);

        if (!REVIEW_QUEUE_STATUSES.contains(campaign.getModerationStatus())
                || !APPROVE_ALLOWED_STATUSES.contains(campaign.getStatus())) {
            throw new BusinessException(AdCampaignErrorCode.NOT_REVIEWABLE);
        }

        campaign.setModerationStatus(AdModerationStatus.APPROVED);
        campaign.setStatus(AdCampaignStatus.APPROVED);
        campaignRepository.save(campaign);

        moderationLogRepository.save(AdCampaignModerationLog.builder()
                .campaignId(campaignId)
                .moderatorUserId(moderatorUserId)
                .action(AdModerationAction.APPROVED)
                .build());
    }

    /**
     * キャンペーンをブロックする。
     *
     * <p>任意の {@code moderation_status} から {@code BLOCKED} へ遷移可能だが、
     * 既に {@code BLOCKED} のキャンペーンへの重複ブロックは
     * {@link AdCampaignErrorCode#ALREADY_BLOCKED} で 409 Conflict を返す。</p>
     *
     * <p>{@code moderation_status=BLOCKED}, {@code status=BLOCKED},
     * {@code blocked_reason=reason} に更新し、
     * {@code ad_campaign_moderation_logs} へ {@code action=BLOCKED} + reason の行を 1 件作成する。</p>
     */
    @Transactional
    public void block(UUID campaignId, Long moderatorUserId, BlockCampaignRequest request) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId);

        if (campaign.getModerationStatus() == AdModerationStatus.BLOCKED) {
            throw new BusinessException(AdCampaignErrorCode.ALREADY_BLOCKED);
        }

        String reason = request.reason();
        campaign.setModerationStatus(AdModerationStatus.BLOCKED);
        campaign.setStatus(AdCampaignStatus.BLOCKED);
        campaign.setBlockedReason(reason);
        campaignRepository.save(campaign);

        moderationLogRepository.save(AdCampaignModerationLog.builder()
                .campaignId(campaignId)
                .moderatorUserId(moderatorUserId)
                .action(AdModerationAction.BLOCKED)
                .reason(reason)
                .build());
    }

    /**
     * F09.17 Phase 11-b: submit 状態遷移時に呼ばれる自動 NG 検知エントリポイント。
     *
     * <p>第二陣 ζ のキャンペーン状態遷移処理から呼び出される。
     * 1 キャンペーンに紐づく全 {@code AdMessagingCampaignChannel} の {@code bodyMarkdown} を
     * {@link AdContentModerator#check(String)} に渡し、結果を集約してキャンペーンの
     * {@code moderation_status} を更新する。</p>
     *
     * <p>集約ロジック:
     * <ul>
     *   <li>いずれかチャネルで {@code AUTO_BLOCK} 検出 → キャンペーン全体を {@code BLOCKED}
     *       (+ {@code blockedReason} に NG 語サマリ)。</li>
     *   <li>{@code AUTO_BLOCK} なし + いずれかチャネルで {@code AUTO_FLAG} 検出
     *       → {@code AUTO_FLAGGED} (SYSTEM_ADMIN 手動レビュー待ち)。</li>
     *   <li>すべて {@code AUTO_PASS} → {@code AUTO_PASSED}。</li>
     * </ul>
     * </p>
     *
     * <p>{@code ad_campaign_moderation_logs} には集約結果 1 行を残す。
     * {@code ng_words_detected} 列には全チャネルの検出 NG ワード配列を JSON で保存する。</p>
     *
     * @param campaignId 対象キャンペーン ID (存在しなければ {@link AdCampaignErrorCode#AD_CAMPAIGN_NOT_FOUND})
     */
    @Transactional
    public void autoFlagOnSubmit(UUID campaignId) {
        AdMessagingCampaign campaign = findCampaignOrThrow(campaignId);

        List<AdMessagingCampaignChannel> channels = campaignChannelRepository.findByCampaignId(campaignId);

        boolean anyBlock = false;
        boolean anyWarn = false;
        // 重複 word を排除しつつ挿入順を保持するため LinkedHashMap を使う
        Map<String, DetectedNgWord> detectedAggregate = new LinkedHashMap<>();

        for (AdMessagingCampaignChannel channel : channels) {
            ModerationCheckResult result = contentModerator.check(channel.getBodyMarkdown());
            for (DetectedNgWord d : result.detectedWords()) {
                detectedAggregate.putIfAbsent(d.word(), d);
            }
            if (result.suggestedAction() == SuggestedModerationAction.AUTO_BLOCK) {
                anyBlock = true;
            } else if (result.suggestedAction() == SuggestedModerationAction.AUTO_FLAG) {
                anyWarn = true;
            }
        }

        List<DetectedNgWord> allDetected = new ArrayList<>(detectedAggregate.values());

        AdModerationAction logAction;
        if (anyBlock) {
            campaign.setModerationStatus(AdModerationStatus.BLOCKED);
            campaign.setStatus(AdCampaignStatus.BLOCKED);
            campaign.setBlockedReason(buildBlockedReason(allDetected));
            logAction = AdModerationAction.BLOCKED;
        } else if (anyWarn) {
            campaign.setModerationStatus(AdModerationStatus.AUTO_FLAGGED);
            logAction = AdModerationAction.AUTO_FLAGGED;
        } else {
            campaign.setModerationStatus(AdModerationStatus.AUTO_PASSED);
            logAction = AdModerationAction.AUTO_PASSED;
        }
        campaignRepository.save(campaign);

        AdCampaignModerationLog.AdCampaignModerationLogBuilder logBuilder = AdCampaignModerationLog.builder()
                .campaignId(campaignId)
                .moderatorUserId(null) // 自動検知のため NULL
                .action(logAction);

        if (!allDetected.isEmpty()) {
            logBuilder.ngWordsDetected(serializeDetectedNgWords(allDetected));
        }
        if (logAction == AdModerationAction.BLOCKED) {
            logBuilder.reason(buildBlockedReason(allDetected));
        }
        moderationLogRepository.save(logBuilder.build());
    }

    /** BLOCK 検出時の {@code blocked_reason} を生成する (NG 語をカンマ区切り)。 */
    private String buildBlockedReason(List<DetectedNgWord> detected) {
        List<String> blockWords = detected.stream()
                .filter(d -> d.severity() == com.mannschaft.app.advertising.campaign.enums.AdNgWordSeverity.BLOCK)
                .map(DetectedNgWord::word)
                .toList();
        String summary = blockWords.isEmpty()
                ? detected.stream().map(DetectedNgWord::word).collect(Collectors.joining(", "))
                : String.join(", ", blockWords);
        return "自動 NG 検知によりブロック: " + summary;
    }

    /** {@code DetectedNgWord} のリストを JSON 文字列にシリアライズする。失敗時は最小限の fallback。 */
    private String serializeDetectedNgWords(List<DetectedNgWord> detected) {
        try {
            return NG_WORDS_JSON_MAPPER.writeValueAsString(detected);
        } catch (JsonProcessingException e) {
            // 万一の失敗時も moderation_logs 行は残せるよう Java の toString fallback
            return "[" + detected.stream()
                    .map(d -> "{\"word\":\"" + d.word() + "\",\"category\":\"" + d.category()
                            + "\",\"severity\":\"" + d.severity() + "\"}")
                    .collect(Collectors.joining(",")) + "]";
        }
    }

    /**
     * キャンペーンを ID で検索し、存在しなければ {@link AdCampaignErrorCode#AD_CAMPAIGN_NOT_FOUND} を投げる。
     *
     * <p>本メソッドは {@code organization_id} 絞り込みを行わない (SYSTEM_ADMIN 越テナント前提)。</p>
     */
    private AdMessagingCampaign findCampaignOrThrow(UUID campaignId) {
        return campaignRepository.findById(campaignId)
                .orElseThrow(() -> new BusinessException(AdCampaignErrorCode.AD_CAMPAIGN_NOT_FOUND));
    }

    /** ハッシュコレクション化のためのユーティリティ (テスト容易化用に List 経由公開)。 */
    static List<AdModerationStatus> reviewQueueStatusesForTest() {
        return List.copyOf(REVIEW_QUEUE_STATUSES);
    }
}
