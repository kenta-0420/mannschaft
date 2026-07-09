package com.mannschaft.app.advertising.service;

import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.advertising.AdPlacement;
import com.mannschaft.app.advertising.AdvertisingErrorCode;
import com.mannschaft.app.advertising.dto.SpotlightAffiliateItem;
import com.mannschaft.app.advertising.dto.SpotlightContentResponse;
import com.mannschaft.app.advertising.dto.SpotlightHouseItem;
import com.mannschaft.app.advertising.dto.SpotlightItem;
import com.mannschaft.app.advertising.dto.SpotlightViewRequest;
import com.mannschaft.app.advertising.dto.SpotlightViewResponse;
import com.mannschaft.app.advertising.dto.SpotlightVisitRequest;
import com.mannschaft.app.advertising.dto.SpotlightVisitResponse;
import com.mannschaft.app.advertising.entity.AdEntity;
import com.mannschaft.app.advertising.repository.AdEntityRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.service.MembershipService;
import com.mannschaft.app.payment.service.TeamPlanService;
import com.mannschaft.app.role.service.RoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * F09.19.2 サービング・計測サービス（正本 §6.2〜6.4・§7.1〜7.5・§11）。
 *
 * <p>割当ロジックの純粋関数部は {@link SpotlightAllocationSelector} に委譲し、本サービスは
 * DB/Valkey I/O・有料プランゲート（越境読み取り）・serve 証跡/serve-cap・dedupe・クールダウン・
 * IP レート制限を担う。記録実体は既存 {@link AdImpressionService} / {@link AdClickService} へ委譲する。</p>
 */
@Service
@RequiredArgsConstructor
public class SpotlightServingService {

    private static final Logger log = LoggerFactory.getLogger(SpotlightServingService.class);

    private static final String FEATURE_FLAG_KEY = "FEATURE_V9_ENABLED";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    // Valkey キー接頭辞（正本 §6.2〜6.4・§7.3。すべて中立命名 mannschaft:ad:*）。
    private static final String K_SERVE_TOKEN = "mannschaft:ad:serve-token:"; // {userId}:{creativeId}
    private static final String K_SERVE_CAP = "mannschaft:ad:serve-cap:";     // {userId}:{campaignId}
    private static final String K_IMP_DEDUPE = "mannschaft:ad:imp-dedupe:";   // {userId}:{creativeId}
    private static final String K_VISIT_CD = "mannschaft:ad:visit-cd:";       // {userId}:{creativeId}
    private static final String K_CLICK_RL = "mannschaft:ad:click-rl:";       // {ipHash}
    private static final String K_IMPS = "mannschaft:ad:imps:";               // {campaignId}:{yyyyMMdd}
    private static final String K_CLICKS = "mannschaft:ad:clicks:";           // {campaignId}:{yyyyMMdd}
    private static final String K_RR = "mannschaft:ad:rr:";                   // {campaignId}:{placement}

    private static final Duration SERVE_TOKEN_TTL = Duration.ofSeconds(600);
    private static final Duration SERVE_CAP_TTL = Duration.ofSeconds(3600);
    private static final Duration DEDUPE_TTL = Duration.ofSeconds(600);
    private static final Duration VISIT_CD_TTL = Duration.ofSeconds(60);
    private static final Duration CLICK_RL_TTL = Duration.ofSeconds(60);
    private static final Duration BUDGET_TTL = Duration.ofSeconds(172800);
    private static final Duration RR_TTL = Duration.ofSeconds(172800);
    private static final int CLICK_RL_LIMIT = 10;
    private static final String DEDUPE_PENDING = "PENDING";

    @PersistenceContext
    private EntityManager em;

    private final FeatureFlagService featureFlagService;
    private final TeamPlanService teamPlanService;
    private final RoleService roleService;
    private final MembershipService membershipService;
    private final AdEntityRepository adEntityRepository;
    private final AdImpressionService adImpressionService;
    private final AdClickService adClickService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SpotlightAllocationSelector selector = SpotlightAllocationSelector.create();

    /** IP ハッシュのソルト（生 IP は保存・ログ出力しない。§6.4）。 */
    @Value("${app.advertising.ip-hash-salt:mannschaft-ad-ip-salt}")
    private String ipHashSalt;

    // ═══════════════════════════════════════════════════════════════════════
    // §6.2 サービング
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 掲載面に表示する広告候補を取得する（正本 §6.2・§7.1〜7.5）。
     *
     * <p>有料プランゲート（§7.5）は payment / role ドメインを越境するため、候補読み取りの
     * {@code @Transactional(readOnly)} の判定材料として Service メソッド経由で参照する
     * （DB 原則 §5 の「やむを得ずまたぐ場合はコメント明記」。書き込みは無く読み取り専用）。</p>
     */
    @Transactional(readOnly = true)
    public SpotlightContentResponse serveContent(
            Long userId, String placement, Integer count, String scopeType, Long scopeId,
            String template, String prefecture, String locale) {

        AdPlacement resolvedPlacement = parsePlacement(placement);
        int resolvedCount = resolveCount(count);
        String resolvedScopeType = (scopeType == null || scopeType.isBlank()) ? "PERSONAL" : scopeType;
        validateScope(resolvedScopeType, scopeId);
        String resolvedLocale = (locale == null || locale.isBlank()) ? "ja" : locale;

        // STEP -1: 有料プランゲート（該当・FEATURE_V9 無効はいずれも items:[]）。
        if (isHidden(userId, resolvedScopeType, scopeId)) {
            return new SpotlightContentResponse(List.of());
        }

        // STEP 0: 受信設定（accept_banner_ads=false なら HOUSE を全スキップ）。
        UserPrefs prefs = loadUserPrefs(userId);

        List<SpotlightAllocationSelector.Candidate> ordered = new ArrayList<>();
        IdentityHashMap<SpotlightAllocationSelector.Candidate, SpotlightItem> itemByCandidate =
                new IdentityHashMap<>();

        if (prefs.acceptBannerAds()) {
            // STEP 1: F09.17 予約バナー（最優先）。
            addReservationCandidates(userId, resolvedPlacement, resolvedLocale, prefs, ordered, itemByCandidate);
            // STEP 2: F09.7 運用型（CPM/CPC）。
            addOperationalCandidates(userId, resolvedPlacement, prefs, ordered, itemByCandidate);
        }
        // STEP 3: アフィリエイト fallback。
        addAffiliateCandidates(resolvedPlacement, template, prefecture, resolvedLocale, ordered, itemByCandidate);

        List<SpotlightAllocationSelector.Candidate> selected = selector.selectWithCount(ordered, resolvedCount);

        List<SpotlightItem> items = new ArrayList<>(selected.size());
        for (SpotlightAllocationSelector.Candidate c : selected) {
            items.add(itemByCandidate.get(c));
            // serve 証跡: HOUSE を応答に含めた時点で serve-token を書く（view/visit の前提）。
            if ("HOUSE".equals(c.source()) && c.creativeId() != null) {
                redisTemplate.opsForValue().set(
                        K_SERVE_TOKEN + userId + ":" + c.creativeId(), "1", SERVE_TOKEN_TTL);
            }
            // serve-cap: 運用型を応答に含めた時点で 1 時間 1 回の露出機会を消費（SETNX EX 単一コマンド）。
            if ("HOUSE".equals(c.source()) && c.campaignId() != null) {
                redisTemplate.opsForValue().setIfAbsent(
                        K_SERVE_CAP + userId + ":" + c.campaignId(), "1", SERVE_CAP_TTL);
            }
        }
        return new SpotlightContentResponse(items);
    }

    // ── STEP -1 有料プランゲート ─────────────────────────────────────────────

    private boolean isHidden(Long userId, String scopeType, Long scopeId) {
        if (!featureFlagService.isEnabled(FEATURE_FLAG_KEY)) {
            return true;
        }
        switch (scopeType) {
            case "TEAM":
                return teamPlanService.hasPaidPlan(scopeId);
            case "ORGANIZATION":
                List<Long> orgTeamIds = roleService.getTeamIdsByOrganizationId(scopeId);
                return teamPlanService.hasActiveOrganizationPlan(orgTeamIds);
            case "PERSONAL":
            default:
                List<Long> myTeamIds = membershipService.getActiveTeamIdsByUser(userId);
                return teamPlanService.hasAnyActivePaidPlan(myTeamIds);
        }
    }

    // ── STEP 1 予約バナー ───────────────────────────────────────────────────

    private void addReservationCandidates(
            Long userId, AdPlacement placement, String locale, UserPrefs prefs,
            List<SpotlightAllocationSelector.Candidate> ordered,
            IdentityHashMap<SpotlightAllocationSelector.Candidate, SpotlightItem> itemByCandidate) {

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT BIN_TO_UUID(bd.id) AS delivery_id, BIN_TO_UUID(mc.id) AS messaging_campaign_id, "
                                + "ch.banner_creative_id AS creative_id, acc.id AS advertiser_account_id, "
                                + "acc.company_name AS advertiser_name, a.title, a.image_url, a.destination_url, "
                                + "a.width, a.height, a.alt_text "
                                + "FROM ad_banner_deliveries bd "
                                + "JOIN ad_messaging_campaigns mc ON mc.id = bd.campaign_id AND mc.status = 'DELIVERING' "
                                + "JOIN ad_messaging_campaign_channels ch ON ch.campaign_id = mc.id "
                                + "  AND ch.channel_type = 'BANNER' AND ch.placement = :placement "
                                + "  AND ch.locale = COALESCE((SELECT ch2.locale FROM ad_messaging_campaign_channels ch2 "
                                + "     WHERE ch2.campaign_id = mc.id AND ch2.channel_type = 'BANNER' "
                                + "       AND ch2.placement = :placement AND ch2.locale = :locale LIMIT 1), 'ja') "
                                + "JOIN ads a ON a.id = ch.banner_creative_id "
                                + "JOIN advertiser_accounts acc ON acc.id = mc.advertiser_account_id "
                                + "WHERE bd.user_id = :userId AND bd.served_at IS NULL "
                                + "ORDER BY bd.created_at ASC")
                .setParameter("placement", placement.name())
                .setParameter("locale", locale)
                .setParameter("userId", userId)
                .getResultList();

        for (Object[] r : rows) {
            Long advertiserAccountId = toLong(r[3]);
            if (prefs.blockedAdvertisers().contains(advertiserAccountId)) {
                continue; // blocked 広告主の予約はスキップして次候補
            }
            Long creativeId = toLong(r[2]);
            SpotlightAllocationSelector.Candidate candidate = new SpotlightAllocationSelector.Candidate(
                    "HOUSE", creativeId, null, advertiserAccountId, null, null, true);
            SpotlightHouseItem house = new SpotlightHouseItem(
                    creativeId, null, (String) r[1], (String) r[0], advertiserAccountId, (String) r[4],
                    (String) r[5], (String) r[6], (String) r[7], toInteger(r[8]), toInteger(r[9]), (String) r[10]);
            ordered.add(candidate);
            itemByCandidate.put(candidate, new SpotlightItem("HOUSE", house, null));
        }
    }

    // ── STEP 2 運用型 ───────────────────────────────────────────────────────

    private void addOperationalCandidates(
            Long userId, AdPlacement placement, UserPrefs prefs,
            List<SpotlightAllocationSelector.Candidate> ordered,
            IdentityHashMap<SpotlightAllocationSelector.Candidate, SpotlightItem> itemByCandidate) {

        LocalDate today = LocalDate.now();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT a.id AS creative_id, c.id AS campaign_id, acc.id AS advertiser_account_id, "
                                + "acc.company_name AS advertiser_name, a.title, a.image_url, a.destination_url, "
                                + "a.width, a.height, a.alt_text, c.daily_budget, c.unit_price_snapshot, c.pricing_model "
                                + "FROM ads a "
                                + "JOIN ad_campaigns c ON c.id = a.campaign_id AND c.status = 'ACTIVE' "
                                + "  AND c.start_date <= :today AND (c.end_date IS NULL OR c.end_date >= :today) "
                                + "JOIN advertiser_accounts acc ON acc.scope_type = 'ORGANIZATION' "
                                + "  AND acc.scope_id = c.advertiser_organization_id "
                                + "  AND acc.status = 'ACTIVE' AND acc.deleted_at IS NULL "
                                + "WHERE a.status = 'ACTIVE' AND a.placement = :placement "
                                + "ORDER BY c.id ASC, a.id ASC")
                .setParameter("today", today)
                .setParameter("placement", placement.name())
                .getResultList();

        String day = today.format(DAY);

        // campaign_id 単位に集約（クリエイティブは ads.id 昇順で保持）。
        Map<Long, CampaignGroup> groups = new LinkedHashMap<>();
        for (Object[] r : rows) {
            Long creativeId = toLong(r[0]);
            Long campaignId = toLong(r[1]);
            CampaignGroup g = groups.computeIfAbsent(campaignId, k -> new CampaignGroup());
            g.campaignId = campaignId;
            g.advertiserAccountId = toLong(r[2]);
            g.dailyBudget = toBigDecimal(r[10]);
            g.unitPrice = toBigDecimal(r[11]);
            g.pricingModel = (String) r[12];
            g.creativeIdsAsc.add(creativeId);
            g.rowByCreative.put(creativeId, r);
        }

        List<SpotlightAllocationSelector.Candidate> operational = new ArrayList<>();
        Map<Long, Object[]> chosenRowByCreative = new LinkedHashMap<>();

        for (CampaignGroup g : groups.values()) {
            if (prefs.blockedAdvertisers().contains(g.advertiserAccountId)) {
                continue; // blocked 広告主は除外
            }
            // serve-cap 中（1 時間以内再訪）は同一キャンペーンを返さない。
            if (Boolean.TRUE.equals(redisTemplate.hasKey(K_SERVE_CAP + userId + ":" + g.campaignId))) {
                continue;
            }
            // 日予算残: 推定消化額（カウンタ × 単価）が daily_budget 以上なら除外。
            long counter = readCounter(("CPC".equals(g.pricingModel) ? K_CLICKS : K_IMPS)
                    + g.campaignId + ":" + day);
            BigDecimal unit = g.unitPrice == null ? BigDecimal.ZERO : g.unitPrice;
            BigDecimal estimate = unit.multiply(BigDecimal.valueOf(counter));
            if (g.dailyBudget != null && estimate.compareTo(g.dailyBudget) >= 0) {
                continue;
            }
            BigDecimal spendRatio = (g.dailyBudget == null || g.dailyBudget.signum() == 0)
                    ? BigDecimal.ZERO
                    : estimate.divide(g.dailyBudget, 6, java.math.RoundingMode.HALF_UP);

            // キャンペーン内クリエイティブをラウンドロビンで 1 件選択。
            long rr = incrementCounter(K_RR + g.campaignId + ":" + placement.name(), RR_TTL);
            Long chosenCreative = selector.pickCreativeByRoundRobin(g.creativeIdsAsc, rr);

            SpotlightAllocationSelector.Candidate candidate = new SpotlightAllocationSelector.Candidate(
                    "HOUSE", chosenCreative, g.campaignId, g.advertiserAccountId, spendRatio, null, false);
            operational.add(candidate);
            chosenRowByCreative.put(chosenCreative, g.rowByCreative.get(chosenCreative));
        }

        // 順位付け（消化率昇順 → campaign.id 昇順）。
        List<SpotlightAllocationSelector.Candidate> ranked = selector.rankOperational(operational);
        for (SpotlightAllocationSelector.Candidate c : ranked) {
            Object[] r = chosenRowByCreative.get(c.creativeId());
            SpotlightHouseItem house = new SpotlightHouseItem(
                    c.creativeId(), c.campaignId(), null, null, c.advertiserAccountId(), (String) r[3],
                    (String) r[4], (String) r[5], (String) r[6], toInteger(r[7]), toInteger(r[8]), (String) r[9]);
            ordered.add(c);
            itemByCandidate.put(c, new SpotlightItem("HOUSE", house, null));
        }
    }

    // ── STEP 3 アフィリエイト fallback ──────────────────────────────────────

    private void addAffiliateCandidates(
            AdPlacement placement, String template, String prefecture, String locale,
            List<SpotlightAllocationSelector.Candidate> ordered,
            IdentityHashMap<SpotlightAllocationSelector.Candidate, SpotlightItem> itemByCandidate) {

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT provider, tag_id, banner_image_url, banner_width, banner_height, alt_text "
                                + "FROM affiliate_configs "
                                + "WHERE is_active = TRUE AND deleted_at IS NULL AND placement = :placement "
                                + "  AND provider IN ('AMAZON','RAKUTEN') "
                                + "  AND (active_from IS NULL OR active_from <= NOW()) "
                                + "  AND (active_until IS NULL OR active_until >= NOW()) "
                                + "  AND (target_template IS NULL OR target_template = :template) "
                                + "  AND (target_prefecture IS NULL OR target_prefecture = :prefecture) "
                                + "  AND (target_locale IS NULL OR target_locale = :locale) "
                                + "ORDER BY display_priority ASC, id ASC")
                .setParameter("placement", placement.name())
                .setParameter("template", template)
                .setParameter("prefecture", prefecture)
                .setParameter("locale", locale)
                .getResultList();

        for (Object[] r : rows) {
            String provider = (String) r[0];
            String tagId = (String) r[1];
            SpotlightAllocationSelector.Candidate candidate = new SpotlightAllocationSelector.Candidate(
                    "AFFILIATE", null, null, null, null, provider, false);
            SpotlightAffiliateItem affiliate = new SpotlightAffiliateItem(
                    provider, buildAffiliateUrl(provider, tagId), (String) r[2],
                    toInteger(r[3]), toInteger(r[4]), (String) r[5]);
            ordered.add(candidate);
            itemByCandidate.put(candidate, new SpotlightItem("AFFILIATE", null, affiliate));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §6.3 view（インプレッション計上）
    // ═══════════════════════════════════════════════════════════════════════

    @Transactional
    public SpotlightViewResponse recordView(Long userId, Long creativeId, SpotlightViewRequest request) {
        requireServeToken(userId, creativeId);
        AdEntity ad = requireAd(creativeId);
        boolean reservation = validateTargetAndPlacement(userId, creativeId, ad, request.placement(),
                request.campaignId(), request.messagingCampaignId(), request.deliveryId());

        // 二重計上防止（user × creative 600 秒）。SET NX 成功 → INSERT → 採番 id で上書き。
        String dedupeKey = K_IMP_DEDUPE + userId + ":" + creativeId;
        Boolean first = redisTemplate.opsForValue().setIfAbsent(dedupeKey, DEDUPE_PENDING, DEDUPE_TTL);
        if (!Boolean.TRUE.equals(first)) {
            String stored = redisTemplate.opsForValue().get(dedupeKey);
            Long existing = (stored == null || DEDUPE_PENDING.equals(stored)) ? null : Long.valueOf(stored);
            return new SpotlightViewResponse(existing, true);
        }

        Long impressionId;
        if (reservation) {
            UUID mcId = UUID.fromString(request.messagingCampaignId());
            impressionId = adImpressionService.recordForMessagingCampaign(creativeId, mcId, userId);
            // 予約行を実表示として充足（§7.4）。
            em.createNativeQuery(
                            "UPDATE ad_banner_deliveries SET ad_impression_id = :impId, served_at = NOW() "
                                    + "WHERE id = UUID_TO_BIN(:deliveryId)")
                    .setParameter("impId", impressionId)
                    .setParameter("deliveryId", request.deliveryId())
                    .executeUpdate();
        } else {
            impressionId = adImpressionService.record(creativeId, request.campaignId(), userId);
            // 日予算カウンタ INCR（運用型のみ）。
            incrementCounter(K_IMPS + request.campaignId() + ":" + LocalDate.now().format(DAY), BUDGET_TTL);
        }
        // dedupe キーに採番済み impressionId を格納（重複時は DB 照会不要で返せる）。
        redisTemplate.opsForValue().set(dedupeKey, String.valueOf(impressionId), DEDUPE_TTL);
        return new SpotlightViewResponse(impressionId, false);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // §6.4 visit（クリック計上）
    // ═══════════════════════════════════════════════════════════════════════

    @Transactional
    public SpotlightVisitResponse recordVisit(Long userId, Long creativeId, String ipAddress,
                                              SpotlightVisitRequest request) {
        requireServeToken(userId, creativeId);
        AdEntity ad = requireAd(creativeId);
        boolean reservation = validateTargetAndPlacement(userId, creativeId, ad, request.placement(),
                request.campaignId(), request.messagingCampaignId(), request.deliveryId());

        // IP レート制限（60 秒 10 回。11 回目 429 / AD_029）。ipHash は salt + IP の SHA-256。
        // NOTE: 正本 §6.4 の鍵は {ipHash}:{creativeId} だが、§16 AC「別ユーザー・別 creative で
        //       同一 IP 11 回目 429」を満たすため IP 単位（creativeId を含めない）で集計する。
        String rlKey = K_CLICK_RL + hashIp(ipAddress);
        long rl = incrementCounter(rlKey, CLICK_RL_TTL);
        if (rl > CLICK_RL_LIMIT) {
            throw new BusinessException(AdvertisingErrorCode.AD_029);
        }

        // ユーザー単位クールダウン（60 秒）。存在中は記録せず clickId=null / 200。
        String cdKey = K_VISIT_CD + userId + ":" + creativeId;
        Boolean fresh = redisTemplate.opsForValue().setIfAbsent(cdKey, "1", VISIT_CD_TTL);
        if (!Boolean.TRUE.equals(fresh)) {
            return new SpotlightVisitResponse(null);
        }

        Long clickId;
        if (reservation) {
            UUID mcId = UUID.fromString(request.messagingCampaignId());
            clickId = adClickService.recordForMessagingCampaign(creativeId, mcId, request.impressionId(), userId);
            em.createNativeQuery(
                            "UPDATE ad_banner_deliveries SET clicked_at = NOW() WHERE id = UUID_TO_BIN(:deliveryId)")
                    .setParameter("deliveryId", request.deliveryId())
                    .executeUpdate();
        } else {
            clickId = adClickService.record(creativeId, request.campaignId(), request.impressionId(), userId);
            incrementCounter(K_CLICKS + request.campaignId() + ":" + LocalDate.now().format(DAY), BUDGET_TTL);
        }
        return new SpotlightVisitResponse(clickId);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 共通検証
    // ═══════════════════════════════════════════════════════════════════════

    /** serve 証跡が無ければ 404（AD_035）。記録前の最初の関門。 */
    private void requireServeToken(Long userId, Long creativeId) {
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(K_SERVE_TOKEN + userId + ":" + creativeId))) {
            throw new BusinessException(AdvertisingErrorCode.AD_035);
        }
    }

    private AdEntity requireAd(Long creativeId) {
        return adEntityRepository.findById(creativeId)
                .orElseThrow(() -> new BusinessException(AdvertisingErrorCode.AD_035));
    }

    /**
     * ターゲット排他・placement 整合・帰属を検証する。
     *
     * @return 予約バナー（messagingCampaign 系）なら true、運用型なら false
     */
    private boolean validateTargetAndPlacement(Long userId, Long creativeId, AdEntity ad, String reqPlacement,
                                               Long campaignId, String messagingCampaignId, String deliveryId) {
        boolean hasCampaign = campaignId != null;
        boolean hasMessaging = messagingCampaignId != null && !messagingCampaignId.isBlank();
        if (hasCampaign == hasMessaging) {
            // 両方指定・両方 null は排他違反。
            throw new BusinessException(AdvertisingErrorCode.AD_003);
        }
        if (hasMessaging) {
            if (deliveryId == null || deliveryId.isBlank()) {
                throw new BusinessException(AdvertisingErrorCode.AD_003);
            }
            // deliveryId 帰属検証（他人の予約行は 404 で隠蔽）。
            Number owned = (Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM ad_banner_deliveries WHERE id = UUID_TO_BIN(:did) "
                                    + "AND user_id = :userId AND campaign_id = UUID_TO_BIN(:mcId)")
                    .setParameter("did", deliveryId)
                    .setParameter("userId", userId)
                    .setParameter("mcId", messagingCampaignId)
                    .getSingleResult();
            if (owned.longValue() == 0) {
                throw new BusinessException(AdvertisingErrorCode.AD_035);
            }
            // placement 整合: serve 時に確定したチャネル行の placement と一致必須。
            String channelPlacement = (String) em.createNativeQuery(
                            "SELECT placement FROM ad_messaging_campaign_channels "
                                    + "WHERE campaign_id = UUID_TO_BIN(:mcId) AND channel_type = 'BANNER' "
                                    + "AND banner_creative_id = :creativeId LIMIT 1")
                    .setParameter("mcId", messagingCampaignId)
                    .setParameter("creativeId", creativeId)
                    .getSingleResult();
            if (!reqPlacement.equals(channelPlacement)) {
                throw new BusinessException(AdvertisingErrorCode.AD_003);
            }
            return true;
        }
        // 運用型: creative がキャンペーンに属するか（不一致は AD_026）。
        if (!campaignId.equals(ad.getCampaignId())) {
            throw new BusinessException(AdvertisingErrorCode.AD_026);
        }
        // placement 整合: ads.placement と一致必須。
        String adPlacement = ad.getPlacement() == null ? null : ad.getPlacement().name();
        if (!reqPlacement.equals(adPlacement)) {
            throw new BusinessException(AdvertisingErrorCode.AD_003);
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 入力バリデーション・補助
    // ═══════════════════════════════════════════════════════════════════════

    private AdPlacement parsePlacement(String placement) {
        try {
            return AdPlacement.valueOf(placement);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(AdvertisingErrorCode.AD_003);
        }
    }

    private int resolveCount(Integer count) {
        int c = (count == null) ? 1 : count;
        if (c < 1 || c > 2) {
            throw new BusinessException(AdvertisingErrorCode.AD_003);
        }
        return c;
    }

    private void validateScope(String scopeType, Long scopeId) {
        switch (scopeType) {
            case "PERSONAL":
                return;
            case "TEAM":
            case "ORGANIZATION":
                if (scopeId == null) {
                    throw new BusinessException(AdvertisingErrorCode.AD_003);
                }
                return;
            default:
                throw new BusinessException(AdvertisingErrorCode.AD_003);
        }
    }

    private UserPrefs loadUserPrefs(Long userId) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT accept_banner_ads, blocked_advertiser_account_ids "
                                + "FROM user_ad_preferences WHERE user_id = :userId")
                .setParameter("userId", userId)
                .getResultList();
        if (rows.isEmpty()) {
            // 設定行が無ければ受信 ON・ブロックなしの既定（F09.7 既存方針）。
            return new UserPrefs(true, Set.of());
        }
        Object[] r = rows.get(0);
        boolean acceptBanner = toBoolean(r[0]);
        Set<Long> blocked = parseBlocked((String) r[1]);
        return new UserPrefs(acceptBanner, blocked);
    }

    private Set<Long> parseBlocked(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<Number> list = objectMapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            Set<Long> out = new HashSet<>();
            for (Number n : list) {
                out.add(n.longValue());
            }
            return out;
        } catch (Exception e) {
            log.warn("blocked_advertiser_account_ids の JSON 解析に失敗（空扱い）: {}", e.getMessage());
            return Set.of();
        }
    }

    private String buildAffiliateUrl(String provider, String tagId) {
        String tag = tagId == null ? "" : tagId;
        if ("RAKUTEN".equals(provider)) {
            return "https://hb.afl.rakuten.co.jp/?tag=" + tag;
        }
        return "https://www.amazon.co.jp/?tag=" + tag;
    }

    /** salt + IP の SHA-256（生 IP は保存もログ出力もしない。§6.4）。 */
    private String hashIp(String ip) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((ipHashSalt + "|" + (ip == null ? "" : ip)).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private long readCounter(String key) {
        String v = redisTemplate.opsForValue().get(key);
        try {
            return v == null ? 0L : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** SET 0 NX EX で TTL を確保してから INCR する（INCR + EXPIRE 逐次の非原子回避。§7.3）。 */
    private long incrementCounter(String key, Duration ttl) {
        redisTemplate.opsForValue().setIfAbsent(key, "0", ttl);
        Long v = redisTemplate.opsForValue().increment(key);
        return v == null ? 0L : v;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 型変換ヘルパー（native クエリの数値/真偽値は driver 依存）
    // ═══════════════════════════════════════════════════════════════════════

    private static Long toLong(Object o) {
        return o == null ? null : ((Number) o).longValue();
    }

    private static Integer toInteger(Object o) {
        return o == null ? null : ((Number) o).intValue();
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            return null;
        }
        return (o instanceof BigDecimal bd) ? bd : new BigDecimal(o.toString());
    }

    private static boolean toBoolean(Object o) {
        if (o == null) {
            return false;
        }
        if (o instanceof Boolean b) {
            return b;
        }
        return ((Number) o).intValue() != 0;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 内部保持型
    // ═══════════════════════════════════════════════════════════════════════

    private record UserPrefs(boolean acceptBannerAds, Set<Long> blockedAdvertisers) {
    }

    private static final class CampaignGroup {
        Long campaignId;
        Long advertiserAccountId;
        BigDecimal dailyBudget;
        BigDecimal unitPrice;
        String pricingModel;
        final List<Long> creativeIdsAsc = new ArrayList<>();
        final Map<Long, Object[]> rowByCreative = new LinkedHashMap<>();
    }
}
