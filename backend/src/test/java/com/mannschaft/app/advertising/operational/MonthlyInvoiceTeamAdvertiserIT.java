package com.mannschaft.app.advertising.operational;

import com.mannschaft.app.advertising.service.MonthlyInvoiceBatchService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.19.5 AC-5.4 月次請求バッチが TEAM 広告主のキャンペーンを請求書に含めることの検証（試練 / red 先行）。
 *
 * <p>正本: {@code docs/features/F09.19_ad_slot_serving.md} §5.2（V144.005/006 scope 化 + コード側参照替え）・
 * §16 F09.19.5（「MonthlyInvoiceBatchService が TEAM 広告主のキャンペーンを請求書に含める
 * （scope_id 流用バグの根治確認）」）。</p>
 *
 * <p><b>バグの所在</b>: {@code MonthlyInvoiceBatchService.generateInvoiceForAccount} が
 * {@code adCampaignRepository.findByAdvertiserOrganizationId(account.getScopeId())} で
 * キャンペーンを引いている。TEAM 広告主では {@code scope_id = team_id} であり、
 * {@code ad_campaigns.advertiser_organization_id}（organization_id）とは一致しないため、
 * <b>TEAM 広告主のキャンペーンが 1 件も請求書に載らない</b>。
 * 根治は {@code findByAdvertiserAccountId(account.getId())} への置換（V144.005 で
 * {@code advertiser_account_id} を導入し、キャンペーンを advertiser_account 直結にする）。</p>
 *
 * <p><b>red 分類（実装不在）</b>: 本テストは TEAM 広告主に帰属するキャンペーンを
 * {@code advertiser_account_id} で紐付けて seed する。試練時点では:</p>
 * <ol>
 *   <li>ad_campaigns に {@code advertiser_account_id} 列が無く（V144.005 未適用・Entity 未拡張）、
 *       かつ {@code advertiser_organization_id} が NOT NULL のため seed INSERT が失敗する、または</li>
 *   <li>仮に載っても現行バッチは org_id 流用で TEAM キャンペーンを拾えず請求書が生成されない</li>
 * </ol>
 * <p>いずれも「請求書が生成されない」= red。出陣で V144.005/006 + Entity 拡張 +
 * {@code findByAdvertiserAccountId} 参照替えを行うと green。</p>
 *
 * <p>seed INSERT は<b>前方互換</b>のため {@code advertiser_organization_id}（V144.006 で DROP 予定）を
 * 参照せず、{@code advertiser_account_id} のみで紐付ける。test プロファイルは {@code flyway.enabled=false} +
 * {@code ddl-auto=create} のため、緑化後は Entity から生成された新スキーマ（advertiser_account_id 有り・
 * advertiser_organization_id 無し）に対して成立する。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F09.19.5 月次請求バッチ TEAM 広告主包含テスト（試練）")
class MonthlyInvoiceTeamAdvertiserIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private MonthlyInvoiceBatchService monthlyInvoiceBatchService;

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("ac5_4: TEAM 広告主のキャンペーンが前月分の請求書に明細として計上される")
    void ac5_4_チーム広告主のキャンペーンが月次請求に含まれる() {
        // given: TEAM スコープの ACTIVE 広告主アカウント（billing=INVOICE）
        Long teamAccountId = insertAdvertiserAccount("TEAM", 987654L, "チーム広告主", "INVOICE");

        // TEAM 広告主に advertiser_account_id で直結するキャンペーン（前方互換 seed）
        Long campaignId = insertCampaignForAccount(teamAccountId, "チーム運用型キャンペーン");

        // 前月内の日次統計（CPM 単価 500 円 × 2000 imp = cost 1000.00 円）
        insertDailyStatsLastMonth(campaignId, 2000L, 10L, new BigDecimal("1000.00"));

        em.flush();
        em.clear();

        // when: 月次請求バッチ（前月分）を実行
        monthlyInvoiceBatchService.generateMonthlyInvoices();

        em.flush();
        em.clear();

        // then: TEAM 広告主アカウントの請求書が 1 件生成される
        Number invoiceCount = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM ad_invoices WHERE advertiser_account_id = :aid")
                .setParameter("aid", teamAccountId)
                .getSingleResult();
        assertThat(invoiceCount.longValue())
                .as("TEAM 広告主の請求書が生成される（scope_id 流用バグの根治後は 1 件）")
                .isEqualTo(1L);

        // 明細に当該キャンペーンが載り、subtotal = 集計 cost 合算（1000.00）
        Object subtotal = em.createNativeQuery(
                        "SELECT ii.subtotal FROM ad_invoice_items ii "
                                + "JOIN ad_invoices i ON i.id = ii.invoice_id "
                                + "WHERE i.advertiser_account_id = :aid AND ii.campaign_id = :cid")
                .setParameter("aid", teamAccountId)
                .setParameter("cid", campaignId)
                .getSingleResult();
        assertThat(new BigDecimal(subtotal.toString()))
                .as("TEAM 広告主キャンペーンの明細金額が集計 cost 合算になる")
                .isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    // ═════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ═════════════════════════════════════════════════════════════════════

    private Long insertAdvertiserAccount(String scopeType, Long scopeId, String companyName, String billingMethod) {
        em.createNativeQuery(
                        "INSERT INTO advertiser_accounts (scope_type, scope_id, status, company_name, "
                                + "contact_email, billing_method, credit_limit, created_at, updated_at) "
                                + "VALUES (:st, :sid, 'ACTIVE', :cn, 'ads@example.com', :bm, 100000, NOW(), NOW())")
                .setParameter("st", scopeType)
                .setParameter("sid", scopeId)
                .setParameter("cn", companyName)
                .setParameter("bm", billingMethod)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT id FROM advertiser_accounts WHERE company_name = :cn")
                .setParameter("cn", companyName)
                .getSingleResult()).longValue();
    }

    /**
     * TEAM 広告主に {@code advertiser_account_id} で直結する運用型キャンペーンを seed する。
     *
     * <p>前方互換のため {@code advertiser_organization_id}（V144.006 で DROP 予定）は指定しない。
     * 試練時点では列不在 / NOT NULL 違反で失敗し（= 実装不在の red）、出陣後に成立する。</p>
     */
    private Long insertCampaignForAccount(Long advertiserAccountId, String name) {
        em.createNativeQuery(
                        "INSERT INTO ad_campaigns (advertiser_account_id, name, status, pricing_model, "
                                + "daily_budget, start_date, end_date, created_at, updated_at) "
                                + "VALUES (:aid, :name, 'ACTIVE', 'CPM', :budget, "
                                + "DATE_SUB(CURDATE(), INTERVAL 40 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), "
                                + "NOW(), NOW())")
                .setParameter("aid", advertiserAccountId)
                .setParameter("name", name)
                .setParameter("budget", new BigDecimal("3000.00"))
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT MAX(id) FROM ad_campaigns").getSingleResult()).longValue();
    }

    /** 前月 15 日付の日次統計を 1 行 seed する（バッチの集計対象期間 = 前月）。 */
    private void insertDailyStatsLastMonth(Long campaignId, long impressions, long clicks, BigDecimal cost) {
        em.createNativeQuery(
                        "INSERT INTO ad_daily_stats (campaign_id, ad_id, date, impressions, clicks, cost, "
                                + "created_at, updated_at) "
                                + "VALUES (:cid, 1, "
                                + "DATE_ADD(DATE_SUB(DATE_FORMAT(CURDATE(), '%Y-%m-01'), INTERVAL 1 MONTH), INTERVAL 14 DAY), "
                                + ":imp, :clk, :cost, NOW(), NOW())")
                .setParameter("cid", campaignId)
                .setParameter("imp", impressions)
                .setParameter("clk", clicks)
                .setParameter("cost", cost)
                .executeUpdate();
    }
}
