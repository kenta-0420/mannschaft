package com.mannschaft.app.market.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.market.dto.MarketListingResponse;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F22.1 市: 公開 DTO の PII 非漏洩を<strong>実マッピング</strong>で検証する結合テスト
 * （04_security §1.3 / 🟡-1 トートロジー是正）。
 *
 * <p>検分指摘 🟡-1: 旧 {@code MarketControllerTest} の PII 禁則テストは service をモックし
 * PII を持たない手組み DTO を検査するトートロジーだった。本テストは
 * <strong>PII を持つユーザーが作成者</strong>の実 {@link RecruitmentListingEntity} を
 * {@link MarketQueryService} の実マッピング（resolveOwner / resolveRegion 等）に通し、
 * その出力 JSON に禁則ワードが含まれないことを検証する（実マッピングが作成者個人情報を
 * 一切読み込まないことの回帰防止）。</p>
 */
@Transactional
@DisplayName("MarketQueryService PII 非漏洩 結合テスト (F22.1 市)")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MarketQueryServicePiiIntegrationTest extends AbstractMySqlIntegrationTest {

    /** 公開 DTO に<strong>絶対に</strong>含まれてはならないフィールド名・PII 値（04_security §1.3）。 */
    private static final String[] FORBIDDEN = {
            // フィールド名
            "email", "phone", "lastName", "firstName", "birthday", "birthDate",
            "address", "applicants", "participants", "createdByName", "contact",
            // 実 PII 値（作成者ユーザーの個人情報が万一漏れたら検出）
            "山田", "太郎", "pii.creator@example.com", "090-1234-5678",
    };

    @Autowired
    private MarketQueryService marketQueryService;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private Long insertPiiUser() {
        em.createNativeQuery(
                "INSERT INTO users ("
                        + "email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, "
                        + "created_at, updated_at) "
                        + "VALUES ('pii.creator@example.com', '山田', '太郎', '山田 太郎', 'ACTIVE', "
                        + "1, 1, 1, 'NOBODY', 'ANYONE', 1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                        + "1, 0, NOW(), NOW())")
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM users WHERE email = 'pii.creator@example.com'")
                .getSingleResult()).longValue();
    }

    private Long insertTeam(String name) {
        em.createNativeQuery(
                "INSERT INTO teams (name, visibility, supporter_enabled, version, member_count, created_at, updated_at, public_id) "
                        + "VALUES (:name, 'PUBLIC', 1, 0, 0, NOW(), NOW(), UUID_TO_BIN(UUID(), 1))")
                .setParameter("name", name)
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                "SELECT id FROM teams WHERE name = :name")
                .setParameter("name", name)
                .getSingleResult()).longValue();
    }

    private Long persistPublicListing(Long teamId, Long createdBy) {
        LocalDateTime now = LocalDateTime.now();
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(teamId)
                .categoryId(1L)
                .title("11/3 練習試合の相手募集")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(now.plusDays(7))
                .endAt(now.plusDays(7).plusHours(2))
                .applicationDeadline(now.plusDays(5))
                .autoCancelAt(now.plusDays(5))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.PUBLIC)
                .status(RecruitmentListingStatus.OPEN)
                .createdBy(createdBy)
                .build();
        em.persist(listing);
        em.flush();
        return listing.getId();
    }

    @Test
    @DisplayName("公開札一覧の実マッピング出力に禁則ワード（個人名/メール/電話 等）が含まれない")
    void searchListings_realMapping_doesNotLeakPii() throws Exception {
        Long creatorId = insertPiiUser();
        Long teamId = insertTeam("別府FC");
        persistPublicListing(teamId, creatorId);
        em.flush();
        em.clear();

        Page<MarketListingResponse> page = marketQueryService.searchListings(
                null, null, null, null, true, PageRequest.of(0, 20));
        assertThat(page.getContent()).isNotEmpty();

        String json = objectMapper.writeValueAsString(page.getContent());
        for (String forbidden : FORBIDDEN) {
            assertThat(json)
                    .as("公開 DTO（実マッピング）に禁則ワード '%s' が含まれてはならない", forbidden)
                    .doesNotContain(forbidden);
        }
        // 公称名（チーム名）は含まれてよいことを確認（過剰抑制でないことの担保）。
        assertThat(json).contains("別府FC");
    }

    @Test
    @DisplayName("公開札詳細の実マッピング出力に禁則ワードが含まれない")
    void getListing_realMapping_doesNotLeakPii() throws Exception {
        Long creatorId = insertPiiUser();
        Long teamId = insertTeam("別府FC");
        Long id = persistPublicListing(teamId, creatorId);
        em.flush();
        em.clear();

        MarketListingResponse response = marketQueryService.getListing(id);
        String json = objectMapper.writeValueAsString(response);
        for (String forbidden : FORBIDDEN) {
            assertThat(json)
                    .as("公開 DTO 詳細（実マッピング）に禁則ワード '%s' が含まれてはならない", forbidden)
                    .doesNotContain(forbidden);
        }
    }
}
