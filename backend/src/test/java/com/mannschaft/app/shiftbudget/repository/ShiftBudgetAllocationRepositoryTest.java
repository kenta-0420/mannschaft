package com.mannschaft.app.shiftbudget.repository;

import com.mannschaft.app.shiftbudget.entity.ShiftBudgetAllocationEntity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F08.7 Phase 9-β {@link ShiftBudgetAllocationRepository} 結合テスト。
 *
 * <p>設計書 §5.2 / §9.5 / §11.1 に対応するクエリの正常系・異常系を検証する。</p>
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Transactional
@DisplayName("ShiftBudgetAllocationRepository 結合テスト")
class ShiftBudgetAllocationRepositoryTest {

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

    // OOM 対策（既存パターン踏襲）
    @MockitoBean
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;

    @Autowired
    private ShiftBudgetAllocationRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long ORG_A = 1001L;
    private static final Long ORG_B = 1002L;
    private static final Long TEAM_A = 2001L;
    private static final Long FISCAL_YEAR = 3001L;
    private static final Long CATEGORY = 4001L;
    private static final Long CREATED_BY = 5001L;

    /**
     * 指定スコープの生存割当を 1 件永続化する。
     *
     * <p>flush 後に {@link EntityManager#clear()} を呼び 1 次キャッシュをクリアする
     * （Repository テストの決定論性を担保するため、JPQL が DB の WHERE 句を素通しで評価するように）。</p>
     */
    private ShiftBudgetAllocationEntity persistAllocation(
            Long organizationId, Long teamId, Long projectId,
            LocalDate periodStart, LocalDate periodEnd, BigDecimal allocated) {
        ShiftBudgetAllocationEntity entity = ShiftBudgetAllocationEntity.builder()
                .organizationId(organizationId)
                .teamId(teamId)
                .projectId(projectId)
                .fiscalYearId(FISCAL_YEAR)
                .budgetCategoryId(CATEGORY)
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .allocatedAmount(allocated)
                .consumedAmount(BigDecimal.ZERO)
                .confirmedAmount(BigDecimal.ZERO)
                .currency("JPY")
                .createdBy(CREATED_BY)
                .version(0L)
                .build();
        em.persist(entity);
        em.flush();
        em.clear();
        return entity;
    }

    @Test
    @DisplayName("保存_全フィールドが永続化される")
    void 保存_全フィールドが永続化される() {
        ShiftBudgetAllocationEntity saved = persistAllocation(
                ORG_A, TEAM_A, null,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                new BigDecimal("500000"));

        ShiftBudgetAllocationEntity found = em.find(ShiftBudgetAllocationEntity.class, saved.getId());
        assertThat(found).isNotNull();
        assertThat(found.getOrganizationId()).isEqualTo(ORG_A);
        assertThat(found.getTeamId()).isEqualTo(TEAM_A);
        assertThat(found.getProjectId()).isNull();
        assertThat(found.getFiscalYearId()).isEqualTo(FISCAL_YEAR);
        assertThat(found.getBudgetCategoryId()).isEqualTo(CATEGORY);
        assertThat(found.getPeriodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(found.getPeriodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(found.getAllocatedAmount()).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(found.getConsumedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(found.getConfirmedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(found.getCurrency()).isEqualTo("JPY");
        assertThat(found.getCreatedBy()).isEqualTo(CREATED_BY);
        assertThat(found.getVersion()).isEqualTo(0L);
        assertThat(found.getDeletedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Nested
    @DisplayName("findByIdAndOrganizationIdAndDeletedAtIsNull — 多テナント検索")
    class FindByIdAndOrganization {

        @Test
        @DisplayName("自組織のIDは取得できる")
        void 自組織のIDは取得できる() {
            ShiftBudgetAllocationEntity saved = persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("100000"));

            Optional<ShiftBudgetAllocationEntity> result =
                    repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_A);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("別組織のIDは見えない（IDOR対策）")
        void 別組織のIDは見えない() {
            ShiftBudgetAllocationEntity saved = persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("100000"));

            Optional<ShiftBudgetAllocationEntity> result =
                    repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_B);

            assertThat(result)
                    .as("組織 B から組織 A の allocation は見えてはならない（IDOR 対策）")
                    .isEmpty();
        }

        @Test
        @DisplayName("論理削除済みは見えない")
        void 論理削除済みは見えない() {
            ShiftBudgetAllocationEntity saved = persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("100000"));
            ShiftBudgetAllocationEntity managed =
                    em.find(ShiftBudgetAllocationEntity.class, saved.getId());
            managed.markDeleted();
            em.flush();
            em.clear();

            Optional<ShiftBudgetAllocationEntity> result =
                    repository.findByIdAndOrganizationIdAndDeletedAtIsNull(saved.getId(), ORG_A);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("findContainingPeriod — 当該日が含まれる割当を解決（hook 用）")
    class FindContainingPeriod {

        @Test
        @DisplayName("当該月のallocationを返す")
        void 当該月のallocationを返す() {
            ShiftBudgetAllocationEntity saved = persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("100000"));

            Optional<ShiftBudgetAllocationEntity> result = repository.findContainingPeriod(
                    ORG_A, TEAM_A, LocalDate.of(2026, 6, 15));

            assertThat(result).isPresent();
            assertThat(result.get().getId()).isEqualTo(saved.getId());
        }

        @Test
        @DisplayName("範囲外（前月）は空")
        void 範囲外は空() {
            persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("100000"));

            Optional<ShiftBudgetAllocationEntity> result = repository.findContainingPeriod(
                    ORG_A, TEAM_A, LocalDate.of(2026, 5, 31));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("teamId が NULL の組織全体割当も解決できる")
        void 組織全体割当も解決できる() {
            persistAllocation(
                    ORG_A, null, null,
                    LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                    new BigDecimal("200000"));

            Optional<ShiftBudgetAllocationEntity> result = repository.findContainingPeriod(
                    ORG_A, null, LocalDate.of(2026, 7, 10));

            assertThat(result).isPresent();
        }
    }

    @Nested
    @DisplayName("incrementConsumedAmount / decrementConsumedAmount — アトミック増減 (Q4)")
    class ConsumedAmountAtomic {

        @Test
        @DisplayName("アトミック増減_加算が反映される")
        void アトミック増減_加算が反映される() {
            ShiftBudgetAllocationEntity saved = persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("500000"));

            int affected = repository.incrementConsumedAmount(saved.getId(), new BigDecimal("12000"));
            em.flush();
            em.clear();

            assertThat(affected).isEqualTo(1);
            ShiftBudgetAllocationEntity reloaded = em.find(ShiftBudgetAllocationEntity.class, saved.getId());
            assertThat(reloaded.getConsumedAmount()).isEqualByComparingTo(new BigDecimal("12000"));
        }

        @Test
        @DisplayName("アトミック減算_減算が反映される")
        void アトミック減算_減算が反映される() {
            ShiftBudgetAllocationEntity saved = persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("500000"));
            // まず加算しておく
            repository.incrementConsumedAmount(saved.getId(), new BigDecimal("20000"));
            em.flush();

            int affected = repository.decrementConsumedAmount(saved.getId(), new BigDecimal("8000"));
            em.flush();
            em.clear();

            assertThat(affected).isEqualTo(1);
            ShiftBudgetAllocationEntity reloaded = em.find(ShiftBudgetAllocationEntity.class, saved.getId());
            assertThat(reloaded.getConsumedAmount()).isEqualByComparingTo(new BigDecimal("12000"));
        }
    }

    @Nested
    @DisplayName("UNIQUE 制約 uq_sba_scope_category_period")
    class UniqueConstraint {

        @Test
        @Disabled("Phase 9-γ 検証結果: project_id は設計書 §5.2 通り NULLABLE 維持と決定（マスター御裁可 Q3）。"
                + "NULL を含む UNIQUE は MySQL 仕様で機能しないため、防衛線は "
                + "ShiftBudgetAllocationService.findLiveByScope の SELECT FOR UPDATE で確定。"
                + "本テストは恒久 @Disabled。"
                + "代替: ShiftBudgetAllocationServiceTest.同一スコープ並行Create_例外")
        @DisplayName("同一スコープ重複INSERT_例外")
        void 同一スコープ重複INSERT_例外() {
            persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("100000"));

            assertThatThrownBy(() -> persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("200000")))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }

        @Test
        @DisplayName("論理削除後の同一スコープINSERT_可能（deleted_at 含有 UNIQUE の確認）")
        void 論理削除後の同一スコープINSERT_可能() {
            ShiftBudgetAllocationEntity first = persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("100000"));
            // 論理削除（deleted_at セット）
            ShiftBudgetAllocationEntity managed =
                    em.find(ShiftBudgetAllocationEntity.class, first.getId());
            managed.markDeleted();
            em.flush();
            em.clear();

            // 同一スコープで再 INSERT が可能であることを検証
            ShiftBudgetAllocationEntity second = persistAllocation(
                    ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("300000"));

            assertThat(second.getId()).isNotEqualTo(first.getId());
            assertThat(second.getAllocatedAmount()).isEqualByComparingTo(new BigDecimal("300000"));
        }
    }

    @Nested
    @DisplayName("findByOrganizationIdAndDeletedAtIsNullOrderByPeriodStartDesc — 一覧取得")
    class ListByOrganization {

        @Test
        @DisplayName("組織配下を期間降順で返す（論理削除済みを除外）")
        void 組織配下を期間降順で返す() {
            persistAllocation(ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30),
                    new BigDecimal("100000"));
            persistAllocation(ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                    new BigDecimal("200000"));
            persistAllocation(ORG_A, TEAM_A, null,
                    LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31),
                    new BigDecimal("150000"));
            // 別組織レコードはノイズとして除外される
            persistAllocation(ORG_B, TEAM_A, null,
                    LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
                    new BigDecimal("999999"));

            Pageable page = PageRequest.of(0, 10);
            List<ShiftBudgetAllocationEntity> result =
                    repository.findByOrganizationIdAndDeletedAtIsNullOrderByPeriodStartDesc(ORG_A, page);

            assertThat(result).hasSize(3);
            assertThat(result.get(0).getPeriodStart()).isEqualTo(LocalDate.of(2026, 6, 1));
            assertThat(result.get(1).getPeriodStart()).isEqualTo(LocalDate.of(2026, 5, 1));
            assertThat(result.get(2).getPeriodStart()).isEqualTo(LocalDate.of(2026, 4, 1));
        }
    }
}
