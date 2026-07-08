package com.mannschaft.app.advertising.spotlight;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;

/**
 * F09.19.2 サービング / 計測 IT の共通基底。
 *
 * <p>正本 §6.2〜6.4・§7.1〜7.5・§16 F09.19.2。{@link AbstractMySqlIntegrationTest} を継承し、
 * 実 MySQL（Testcontainers・共有コンテキスト）に対して Controller → Service → Repository /
 * ネイティブ SQL フィクスチャで検証する。金型は {@code OperationalAdCampaignCrudIT}（.1）。</p>
 *
 * <p><b>Valkey について</b>: 基底 {@code AbstractMySqlIntegrationTest} は {@code StringRedisTemplate} を
 * {@code @MockitoBean} 化する。本基底では {@link #wireInMemoryRedis()} でモックを
 * <b>ステートフルな in-memory フェイク</b>に仕立て、serve 証跡 / serve-cap / dedupe /
 * クールダウン / IP レート制限が「実装さえ入れば」green 化できるようにする（best-effort。
 * 出陣の実装が標準的な {@code opsForValue().setIfAbsent/get/increment} 経由であることを前提とする）。
 * 試練時点ではサービス骨格が {@link UnsupportedOperationException} を投げるため全テスト red。</p>
 */
abstract class AbstractSpotlightIT extends AbstractMySqlIntegrationTest {

    @PersistenceContext
    protected EntityManager em;

    /** in-memory Valkey フェイクのバッキングストア（各テストで作り直す）。 */
    protected Map<String, String> redisStore;

    protected void setUpCommon() {
        wireInMemoryRedis();
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // Valkey in-memory フェイク（best-effort・green 化用）
    // ═════════════════════════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    protected void wireInMemoryRedis() {
        reset(redisTemplate);
        redisStore = new ConcurrentHashMap<>();
        ValueOperations<String, String> vops = mock(ValueOperations.class);
        lenient().when(redisTemplate.opsForValue()).thenReturn(vops);

        // SET key value NX EX <duration>（原子 setIfAbsent）
        lenient().when(vops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenAnswer(inv -> redisStore.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        lenient().when(vops.setIfAbsent(anyString(), anyString()))
                .thenAnswer(inv -> redisStore.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);

        // SET key value（上書き）
        lenient().doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(vops).set(anyString(), anyString());
        lenient().doAnswer(inv -> {
            redisStore.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(vops).set(anyString(), anyString(), any(Duration.class));

        // GET key
        lenient().when(vops.get(anyString())).thenAnswer(inv -> redisStore.get(inv.getArgument(0)));

        // INCR key
        lenient().when(vops.increment(anyString())).thenAnswer(inv -> {
            String k = inv.getArgument(0);
            long nv = Long.parseLong(redisStore.getOrDefault(k, "0")) + 1;
            redisStore.put(k, Long.toString(nv));
            return nv;
        });

        // DELETE / hasKey / getExpire / expire（TTL は本テストでは満了させない）
        lenient().when(redisTemplate.delete(anyString()))
                .thenAnswer(inv -> redisStore.remove(inv.getArgument(0)) != null);
        lenient().when(redisTemplate.hasKey(anyString()))
                .thenAnswer(inv -> redisStore.containsKey(inv.getArgument(0)));
        lenient().when(redisTemplate.getExpire(anyString(), any())).thenReturn(600L);
        lenient().when(redisTemplate.expire(anyString(), anyLong(), any())).thenReturn(true);
    }

    // ═════════════════════════════════════════════════════════════════════
    // 認証ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    protected void setAuthentication(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    protected void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    // ═════════════════════════════════════════════════════════════════════
    // ネイティブ SQL フィクスチャ（.1 CrudIT 踏襲 + サービング用拡張）
    // ═════════════════════════════════════════════════════════════════════

    protected Long insertUser(String email) {
        em.createNativeQuery(
                        "INSERT INTO users (email, last_name, first_name, display_name, status, "
                                + "is_searchable, handle_searchable, contact_approval_required, "
                                + "online_visibility, dm_receive_from, encryption_key_version, "
                                + "locale, timezone, reporting_restricted, follow_list_visibility, "
                                + "care_notification_enabled, offline_only, created_at, updated_at) "
                                + "VALUES (:email, 'サービング', 'テスト', 'サービング テスト', 'ACTIVE', "
                                + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                                + "1, 0, NOW(), NOW())")
                .setParameter("email", email)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = :email")
                .setParameter("email", email).getSingleResult()).longValue();
    }

    protected void insertRole(String name, String displayName, int priority, boolean isSystem) {
        em.createNativeQuery(
                        "INSERT INTO roles (name, display_name, priority, is_system, created_at, updated_at) "
                                + "VALUES (:name, :dn, :priority, :sys, NOW(), NOW())")
                .setParameter("name", name).setParameter("dn", displayName)
                .setParameter("priority", priority).setParameter("sys", isSystem ? 1 : 0)
                .executeUpdate();
    }

    protected Long roleId(String name) {
        return ((Number) em.createNativeQuery("SELECT id FROM roles WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    protected void insertUserRole(Long uid, Long roleId, Long teamId, Long orgId) {
        em.createNativeQuery(
                        "INSERT INTO user_roles (user_id, role_id, team_id, organization_id, created_at, updated_at) "
                                + "VALUES (:uid, :rid, :tid, :oid, NOW(), NOW())")
                .setParameter("uid", uid).setParameter("rid", roleId)
                .setParameter("tid", teamId).setParameter("oid", orgId)
                .executeUpdate();
    }

    protected Long insertOrganization(String name) {
        em.createNativeQuery(
                        "INSERT INTO organizations (name, org_type, visibility, hierarchy_visibility, "
                                + "supporter_enabled, version, slug, created_at, updated_at) "
                                + "VALUES (:name, 'OTHER', 'PUBLIC', 'NONE', 1, 0, "
                                + "CONCAT('s-', LEFT(REPLACE(UUID(),'-',''),8)), NOW(), NOW())")
                .setParameter("name", name).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM organizations WHERE name = :name")
                .setParameter("name", name).getSingleResult()).longValue();
    }

    protected Long insertTeam(String name) {
        em.createNativeQuery(
                        "INSERT INTO teams (name, visibility, supporter_enabled, version, created_at, updated_at) "
                                + "VALUES (:name, 'PUBLIC', 1, 0, NOW(), NOW())")
                .setParameter("name", name).executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM teams").getSingleResult()).longValue();
    }

    /** memberships に user→team の在籍行を追加する（PERSONAL 有料判定用）。 */
    protected void insertTeamMembership(Long userId, Long teamId) {
        em.createNativeQuery(
                        "INSERT INTO memberships (user_id, scope_type, scope_id, role_kind, joined_at, "
                                + "created_at, updated_at) "
                                + "VALUES (:uid, 'TEAM', :tid, 'MEMBER', NOW(), NOW(), NOW())")
                .setParameter("uid", userId).setParameter("tid", teamId).executeUpdate();
    }

    /** team_subscriptions を追加する（plan_type: FREE/MODULE/PACKAGE/ORGANIZATION、status: ACTIVE/EXPIRED 等）。 */
    protected void insertTeamSubscription(Long teamId, String planType, String status) {
        em.createNativeQuery(
                        "INSERT INTO team_subscriptions (team_id, plan_type, status, created_at, updated_at) "
                                + "VALUES (:tid, :pt, :st, NOW(), NOW())")
                .setParameter("tid", teamId).setParameter("pt", planType).setParameter("st", status)
                .executeUpdate();
    }

    protected Long insertAdvertiserAccount(Long orgId, String companyName) {
        em.createNativeQuery(
                        "INSERT INTO advertiser_accounts (scope_type, scope_id, status, company_name, "
                                + "contact_email, billing_method, credit_limit, created_at, updated_at) "
                                + "VALUES ('ORGANIZATION', :oid, 'ACTIVE', :cn, 'ads@example.com', "
                                + "'STRIPE', 100000, NOW(), NOW())")
                .setParameter("oid", orgId).setParameter("cn", companyName).executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM advertiser_accounts WHERE company_name = :cn")
                .setParameter("cn", companyName).getSingleResult()).longValue();
    }

    /**
     * ACTIVE 運用型キャンペーンを挿入する（本日が [start, end] 内・単価スナップショット付き）。
     */
    protected Long insertActiveOperationalCampaign(Long orgId, String name, String status,
                                                   java.math.BigDecimal unitPriceSnapshot) {
        em.createNativeQuery(
                        "INSERT INTO ad_campaigns (advertiser_organization_id, name, status, pricing_model, "
                                + "daily_budget, start_date, end_date, unit_price_snapshot, created_at, updated_at) "
                                + "VALUES (:oid, :name, :status, 'CPM', :budget, "
                                + "DATE_SUB(CURDATE(), INTERVAL 1 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                                + ":snap, NOW(), NOW())")
                .setParameter("oid", orgId).setParameter("name", name).setParameter("status", status)
                .setParameter("budget", new java.math.BigDecimal("3000.00"))
                .setParameter("snap", unitPriceSnapshot)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ad_campaigns").getSingleResult()).longValue();
    }

    /** ads（クリエイティブ）を挿入する。 */
    protected Long insertCreative(Long campaignId, String title, String placement, String status) {
        em.createNativeQuery(
                        "INSERT INTO ads (campaign_id, title, image_url, destination_url, placement, "
                                + "width, height, alt_text, status, created_at, updated_at) "
                                + "VALUES (:cid, :title, 'https://example.com/img.png', 'https://example.com/lp', "
                                + ":pl, 300, 250, :alt, :status, NOW(), NOW())")
                .setParameter("cid", campaignId).setParameter("title", title)
                .setParameter("pl", placement).setParameter("alt", title + " alt").setParameter("status", status)
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ads").getSingleResult()).longValue();
    }

    /**
     * 「serve される運用型 HOUSE 候補」を 1 セット作る（ACTIVE キャンペーン + ACTIVE クリエイティブ）。
     *
     * @return クリエイティブ（ads）の id
     */
    protected Long insertServableHouseCandidate(Long orgId, Long advAccountId, String placement,
                                                java.math.BigDecimal unitPriceSnapshot, String name) {
        Long campaignId = insertActiveOperationalCampaign(orgId, name, "ACTIVE", unitPriceSnapshot);
        return insertCreative(campaignId, name + "-creative", placement, "ACTIVE");
    }

    /** 運用型キャンペーンの id を、直近作成の ad_campaigns から引く補助（creative→campaign 紐付け確認用）。 */
    protected Long campaignIdOfCreative(Long creativeId) {
        return ((Number) em.createNativeQuery("SELECT campaign_id FROM ads WHERE id = :id")
                .setParameter("id", creativeId).getSingleResult()).longValue();
    }

    protected void insertAffiliateConfig(String provider, String placement, int displayPriority) {
        em.createNativeQuery(
                        "INSERT INTO affiliate_configs (provider, tag_id, placement, banner_image_url, "
                                + "is_active, display_priority, created_at, updated_at) "
                                + "VALUES (:prov, :tag, :pl, 'https://example.com/aff.png', TRUE, :prio, NOW(), NOW())")
                .setParameter("prov", provider).setParameter("tag", provider.toLowerCase() + "-tag")
                .setParameter("pl", placement).setParameter("prio", displayPriority)
                .executeUpdate();
    }

    /**
     * user_ad_preferences を作る。
     *
     * @param blockedAdvertiserJson 例 "[]" / "[7]"
     */
    protected void insertUserAdPreferences(Long userId, boolean acceptBannerAds, String blockedAdvertiserJson) {
        em.createNativeQuery(
                        "INSERT INTO user_ad_preferences (id, user_id, accept_announcement_ads, accept_email_ads, "
                                + "accept_push_ads, accept_banner_ads, blocked_advertiser_account_ids, "
                                + "unsubscribe_token_version, created_at, updated_at) "
                                + "VALUES (UUID_TO_BIN(UUID()), :uid, TRUE, TRUE, TRUE, :banner, :blocked, 1, NOW(), NOW())")
                .setParameter("uid", userId).setParameter("banner", acceptBannerAds)
                .setParameter("blocked", blockedAdvertiserJson)
                .executeUpdate();
    }

    /**
     * F09.17 予約バナー一式を作る: DELIVERING キャンペーン + BANNER チャネル（placement） + 未表示予約行。
     *
     * @return [messagingCampaignId(uuid), deliveryId(uuid), creativeId(ads.id)]
     */
    protected ReservationFixture insertReservationBanner(Long orgId, Long advAccountId, Long userId,
                                                         String placement, Long creatorUserId) {
        String campaignUuid = UUID.randomUUID().toString();
        String deliveryUuid = UUID.randomUUID().toString();

        // BANNER クリエイティブ（ads）。予約バナーも ads を参照する
        Long creativeCampaign = insertActiveOperationalCampaign(orgId, "予約用親", "ACTIVE",
                new java.math.BigDecimal("500.0000"));
        Long creativeId = insertCreative(creativeCampaign, "予約バナー", placement, "ACTIVE");

        em.createNativeQuery(
                        "INSERT INTO ad_messaging_campaigns (id, advertiser_account_id, organization_id, name, "
                                + "status, total_budget_yen, starts_at, ends_at, created_by_user_id, created_at, updated_at) "
                                + "VALUES (UUID_TO_BIN(:cid), :aid, :oid, '予約キャンペーン', 'DELIVERING', 100000, "
                                + "DATE_SUB(NOW(), INTERVAL 1 DAY), DATE_ADD(NOW(), INTERVAL 7 DAY), :creator, NOW(), NOW())")
                .setParameter("cid", campaignUuid).setParameter("aid", advAccountId)
                .setParameter("oid", orgId).setParameter("creator", creatorUserId)
                .executeUpdate();

        em.createNativeQuery(
                        "INSERT INTO ad_messaging_campaign_channels (id, campaign_id, channel_type, locale, "
                                + "body_markdown, banner_creative_id, placement, created_at, updated_at) "
                                + "VALUES (UUID_TO_BIN(UUID()), UUID_TO_BIN(:cid), 'BANNER', 'ja', "
                                + "'本文', :creative, :pl, NOW(), NOW())")
                .setParameter("cid", campaignUuid).setParameter("creative", creativeId).setParameter("pl", placement)
                .executeUpdate();

        // 未表示予約: served_at NULL / ad_impression_id NULL（V144.<ts> の NULL 許容化が前提）
        em.createNativeQuery(
                        "INSERT INTO ad_banner_deliveries (id, campaign_id, user_id, ad_impression_id, served_at, "
                                + "month_key, created_at) "
                                + "VALUES (UUID_TO_BIN(:did), UUID_TO_BIN(:cid), :uid, NULL, NULL, "
                                + "DATE_FORMAT(NOW(), '%Y-%m'), NOW())")
                .setParameter("did", deliveryUuid).setParameter("cid", campaignUuid).setParameter("uid", userId)
                .executeUpdate();

        return new ReservationFixture(campaignUuid, deliveryUuid, creativeId);
    }

    /** 予約バナーフィクスチャの識別子束。 */
    protected record ReservationFixture(String messagingCampaignId, String deliveryId, Long creativeId) {
    }

    // ═════════════════════════════════════════════════════════════════════
    // DB 実測ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    protected long countImpressions(Long creativeId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM ad_impressions WHERE ad_id = :id")
                .setParameter("id", creativeId).getSingleResult()).longValue();
    }

    protected long countClicks(Long creativeId) {
        return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM ad_clicks WHERE ad_id = :id")
                .setParameter("id", creativeId).getSingleResult()).longValue();
    }

    /** 予約行の served_at / ad_impression_id が充足されたか（充足なら true）。 */
    protected boolean isDeliveryServed(String deliveryUuid) {
        Object row = em.createNativeQuery(
                        "SELECT served_at FROM ad_banner_deliveries WHERE id = UUID_TO_BIN(:did)")
                .setParameter("did", deliveryUuid).getSingleResult();
        return row != null;
    }
}
