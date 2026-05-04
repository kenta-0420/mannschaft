package com.mannschaft.app.activity.visibility;

import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.ActivityVisibility;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F00 Phase B — {@link ActivityResultRepository#findVisibilityProjectionsByIdIn} 結合テスト。
 *
 * <p>実 MySQL（Testcontainers）に対し最小限の {@link ActivityResultEntity} を投入し、
 * Spring Data Projection 経由で {@link ActivityResultVisibilityProjection} に
 * 各カラムが正しくマッピングされること、{@code @SQLRestriction("deleted_at IS NULL")} で
 * 論理削除済が自動除外されることを検証する。</p>
 *
 * <p>セットアップ方針は既存 {@code MembershipBatchQueryServiceIntegrationTest} を踏襲し、
 * {@code ddl-auto=create-drop}・{@code @Transactional} ロールバックで隔離する。
 * Activity Entity は JPA persist 経由で挿入することで NOT NULL カラムを Builder で明示する。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("ActivityResultRepository.findVisibilityProjectionsByIdIn — 結合テスト")
class ActivityResultVisibilityProjectionRepositoryTest {

    @Container
    @SuppressWarnings("resource")
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mannschaft_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    // OOM 対策（既存 Repository テストパターン踏襲）
    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private ActivityResultRepository activityResultRepository;

    @PersistenceContext
    private EntityManager em;

    private Long teamPublicId;
    private Long teamMembersId;
    private Long orgMembersId;
    private Long committeeMembersId;
    private Long deletedId;

    @BeforeEach
    void setUp() {
        teamPublicId = persistActivity(ActivityScopeType.TEAM, 100L,
                ActivityVisibility.PUBLIC, 999L, false);
        teamMembersId = persistActivity(ActivityScopeType.TEAM, 100L,
                ActivityVisibility.MEMBERS_ONLY, 999L, false);
        orgMembersId = persistActivity(ActivityScopeType.ORGANIZATION, 500L,
                ActivityVisibility.MEMBERS_ONLY, 888L, false);
        committeeMembersId = persistActivity(ActivityScopeType.COMMITTEE, 700L,
                ActivityVisibility.MEMBERS_ONLY, 777L, false);
        deletedId = persistActivity(ActivityScopeType.TEAM, 100L,
                ActivityVisibility.PUBLIC, 999L, true);
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("複数 ID を 1 SQL で取得し、各カラムが Projection に正しく載る")
    void findByIdIn_returnsProjectionsWithAllFields() {
        List<ActivityResultVisibilityProjection> result = activityResultRepository
                .findVisibilityProjectionsByIdIn(List.of(teamPublicId, teamMembersId, orgMembersId));

        assertThat(result).hasSize(3);
        ActivityResultVisibilityProjection teamPub = findById(result, teamPublicId);
        assertThat(teamPub.scopeType()).isEqualTo("TEAM");
        assertThat(teamPub.scopeId()).isEqualTo(100L);
        assertThat(teamPub.authorUserId()).isEqualTo(999L);
        assertThat(teamPub.visibility()).isEqualTo(ActivityVisibility.PUBLIC);
        assertThat(teamPub.visibilityTemplateId()).isNull(); // Activity は CUSTOM_TEMPLATE 概念なし

        ActivityResultVisibilityProjection orgMem = findById(result, orgMembersId);
        assertThat(orgMem.scopeType()).isEqualTo("ORGANIZATION");
        assertThat(orgMem.scopeId()).isEqualTo(500L);
        assertThat(orgMem.authorUserId()).isEqualTo(888L);
        assertThat(orgMem.visibility()).isEqualTo(ActivityVisibility.MEMBERS_ONLY);
    }

    @Test
    @DisplayName("COMMITTEE スコープも文字列 \"COMMITTEE\" として返る（Resolver 側で fail-closed）")
    void findByIdIn_committeeScopeReturned() {
        List<ActivityResultVisibilityProjection> result = activityResultRepository
                .findVisibilityProjectionsByIdIn(List.of(committeeMembersId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).scopeType()).isEqualTo("COMMITTEE");
        assertThat(result.get(0).scopeId()).isEqualTo(700L);
    }

    @Test
    @DisplayName("論理削除済の Activity は @SQLRestriction で Projection に届かない")
    void findByIdIn_deletedRowExcluded() {
        List<ActivityResultVisibilityProjection> result = activityResultRepository
                .findVisibilityProjectionsByIdIn(List.of(deletedId, teamPublicId));

        // teamPublicId のみ返り、論理削除済の deletedId は除外される
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(teamPublicId);
    }

    @Test
    @DisplayName("未存在 ID は単に結果に含まれない（IDOR 防止 §11.3）")
    void findByIdIn_unknownIdSkipped() {
        List<ActivityResultVisibilityProjection> result = activityResultRepository
                .findVisibilityProjectionsByIdIn(List.of(999_999L, teamPublicId));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(teamPublicId);
    }

    @Test
    @DisplayName("空集合入力は空 List を返す（呼び出し側のショートカットだが Repository も安全）")
    void findByIdIn_emptyInput_returnsEmpty() {
        List<ActivityResultVisibilityProjection> result = activityResultRepository
                .findVisibilityProjectionsByIdIn(List.of());
        assertThat(result).isEmpty();
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    /**
     * 最小限の NOT NULL を満たして ActivityResultEntity を persist する。
     *
     * @param scopeType    スコープ種別
     * @param scopeId      スコープ ID
     * @param visibility   可視性
     * @param createdBy    作成者 user_id
     * @param softDeleted  true で論理削除済（deletedAt セット）
     * @return 永続化した ID
     */
    private Long persistActivity(ActivityScopeType scopeType, Long scopeId,
                                  ActivityVisibility visibility, Long createdBy, boolean softDeleted) {
        ActivityResultEntity entity = ActivityResultEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .templateId(1L)
                .title("F00 Phase B test activity")
                .activityDate(LocalDate.of(2026, 5, 4))
                .fieldValues("{}")
                .visibility(visibility)
                .createdBy(createdBy)
                .build();
        em.persist(entity);
        em.flush();
        if (softDeleted) {
            entity.softDelete();
            em.flush();
        }
        return entity.getId();
    }

    private ActivityResultVisibilityProjection findById(
            List<ActivityResultVisibilityProjection> projections, Long id) {
        return projections.stream()
                .filter(p -> id.equals(p.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Projection not found: id=" + id));
    }
}
