package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.dto.CharterArticleCreateRequest;
import com.mannschaft.app.village.dto.CharterArticleOrderUpdateRequest;
import com.mannschaft.app.village.entity.VillageCharterArticleEntity;
import com.mannschaft.app.village.entity.VillageCharterEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageCharterArticleRepository;
import com.mannschaft.app.village.repository.VillageCharterDrafterRepository;
import com.mannschaft.app.village.repository.VillageCharterRepository;
import com.mannschaft.app.village.repository.VillageCharterRevisionRepository;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F17.3 村憲章 <strong>並行</strong>制御の試練テスト（AC-11b/11d/12b・red 先行・設計書 §6.3/§7）。
 *
 * <h2>本テストの性質（重要 / 検分時に必読）</h2>
 *
 * <p><strong>試練（red）テスト</strong>。悲観ロック直列化（{@code findByIdForUpdate}）・層2 バンプは
 * W1 骨格スタブでは未実装（{@code VillageCharterService} が {@code UnsupportedOperationException} を
 * 投げる）ため、以下はいずれも red:</p>
 * <ul>
 *   <li>AC-11b: 2 管理者の並行 POST → {@code sort_order} が 0,1 の連番（現状は 0 件のまま）</li>
 *   <li>AC-11d: DELETE×PATCH order の並行でデッドロック/500 なし＋削除が反映（現状は削除されない）</li>
 *   <li>AC-12b: POST/DELETE の層2 バンプ→後続 PATCH order が古い charterVersion で 409（現状は別例外）</li>
 * </ul>
 *
 * <p><strong>@Transactional を付けない</strong>のが要点（金型 {@link VillageMeetupCapacityConcurrencyIT}）。
 * 別スレッドから<strong>コミット済みの状態</strong>を見せて競わせるため、セットアップを実コミットし、
 * {@link #tearDown()} で後始末する。</p>
 */
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F17.3 村憲章 並行制御テスト（試練・red・AC-11b/11d/12b）")
class VillageCharterConcurrencyIT extends AbstractMySqlIntegrationTest {

    @Autowired private VillageCharterService charterService;
    @Autowired private VillageRepository villageRepository;
    @Autowired private VillageMembershipRepository membershipRepository;
    @Autowired private VillageCharterRepository charterRepository;
    @Autowired private VillageCharterArticleRepository articleRepository;
    @Autowired private VillageCharterDrafterRepository drafterRepository;
    @Autowired private VillageCharterRevisionRepository revisionRepository;

    @MockitoBean private R2StorageService r2StorageService;

    private static final Long HEADMAN_ID = 17_311_001L;
    private static final Long ELDER_ID = 17_311_002L;

    private java.util.UUID villageId;
    private java.util.UUID charterId;

    @AfterEach
    void tearDown() {
        if (charterId != null) {
            articleRepository.findByCharterIdAndDeletedAtIsNullOrderBySortOrderAsc(charterId)
                    .forEach(articleRepository::delete);
            articleRepository.findAll().stream()
                    .filter(a -> charterId.equals(a.getCharterId()))
                    .forEach(articleRepository::delete);
            drafterRepository.findByCharterIdOrderBySortOrderAsc(charterId).forEach(drafterRepository::delete);
            revisionRepository.findByCharterIdOrderByRevisedAtDesc(charterId).forEach(revisionRepository::delete);
            charterRepository.findById(charterId).ifPresent(charterRepository::delete);
        }
        if (villageId != null) {
            membershipRepository.findAll().stream()
                    .filter(m -> villageId.equals(m.getVillageId()))
                    .forEach(membershipRepository::delete);
            villageRepository.findById(villageId).ifPresent(villageRepository::delete);
        }
    }

    @Test
    @DisplayName("AC-11b 2管理者の同時POST articles → 親charter悲観ロックで直列化しsort_orderは0,1の連番（両成功）")
    void concurrentPostArticles_serialized_sortOrderSequential_AC11b() throws Exception {
        setupVillageWithCharterAndHeadmen();

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> f1 = pool.submit(() -> postArticleWithBarrier(barrier, HEADMAN_ID, "条X"));
            Future<?> f2 = pool.submit(() -> postArticleWithBarrier(barrier, ELDER_ID, "条Y"));
            awaitQuietly(f1);
            awaitQuietly(f2);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        // 不変条件（INV-1）: 非削除条の sort_order は 0,1,… の隙間なし連番（重複なし）。
        List<Integer> sortOrders = articleRepository
                .findByCharterIdAndDeletedAtIsNullOrderBySortOrderAsc(charterId).stream()
                .map(VillageCharterArticleEntity::getSortOrder)
                .collect(Collectors.toList());
        assertThat(sortOrders)
                .as("2管理者の並行POST後、sort_orderは0,1の連番（重複・欠番なし）であるべし")
                .containsExactly(0, 1);
    }

    @Test
    @DisplayName("AC-11d DELETE×PATCH orderの並行でデッドロック/500なし＋削除が反映される（統一ロック順）")
    void concurrentDeleteAndReorder_noDeadlock_AC11d() throws Exception {
        setupVillageWithCharterAndHeadmen();
        VillageCharterArticleEntity a0 = persistArticle(0, "A");
        VillageCharterArticleEntity a1 = persistArticle(1, "B");
        VillageCharterArticleEntity a2 = persistArticle(2, "C");
        long charterVersion = currentCharterVersion();

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // T1: a1 を削除
            Future<?> fDel = pool.submit(() -> runWithBarrier(barrier, failures,
                    () -> charterService.deleteArticle(villageId, a1.getId(), HEADMAN_ID)));
            // T2: 並び替え（a2,a0 の順など・存在集合を送る想定）
            Future<?> fReorder = pool.submit(() -> runWithBarrier(barrier, failures, () -> {
                CharterArticleOrderUpdateRequest req = new CharterArticleOrderUpdateRequest(
                        List.of(a2.getId(), a0.getId(), a1.getId()), charterVersion);
                charterService.reorderArticles(villageId, req, ELDER_ID);
            }));
            awaitQuietly(fDel);
            awaitQuietly(fReorder);
        } finally {
            pool.shutdown();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        // (1) デッドロック（InnoDB ロック順序逆転）由来の例外が出ていないこと（統一ロック順で封殺）。
        assertThat(failures)
                .as("統一ロック順（親charter→条行）でデッドロックは発生しないべし")
                .noneMatch(t -> isDeadlock(t));

        // (2) 削除が反映されている（a1 は非削除集合に残っていない）。
        List<java.util.UUID> aliveIds = articleRepository
                .findByCharterIdAndDeletedAtIsNullOrderBySortOrderAsc(charterId).stream()
                .map(VillageCharterArticleEntity::getId)
                .collect(Collectors.toList());
        assertThat(aliveIds)
                .as("並行DELETE×PATCH order後、削除された条a1は生存集合に含まれないべし")
                .doesNotContain(a1.getId());

        // (3) 生存条の sort_order は 0,1,… の連番（再連番の不変条件・INV-1）。
        List<Integer> sortOrders = articleRepository
                .findByCharterIdAndDeletedAtIsNullOrderBySortOrderAsc(charterId).stream()
                .map(VillageCharterArticleEntity::getSortOrder)
                .collect(Collectors.toList());
        assertThat(sortOrders).containsExactly(0, 1);
    }

    @Test
    @DisplayName("AC-12b POST/DELETEが層2 versionをバンプ→古いcharterVersionでのPATCH orderは409（連鎖）")
    void layer2Bump_thenStalePatchOrder_conflict_AC12b() throws Exception {
        setupVillageWithCharterAndHeadmen();
        VillageCharterArticleEntity a0 = persistArticle(0, "A");
        VillageCharterArticleEntity a1 = persistArticle(1, "B");
        long staleVersion = currentCharterVersion(); // 管理者Xが見た charterVersion=v

        // 管理者Y が条を追加 → 層2 version が v→v+1 にバンプ（実コミット）
        charterService.addArticle(villageId, new CharterArticleCreateRequest("Yが追加", null), ELDER_ID);

        // 管理者X が手元の古い charterVersion=v で PATCH order → 409（CHARTER_ORDER_VERSION_CONFLICT）
        CharterArticleOrderUpdateRequest req = new CharterArticleOrderUpdateRequest(
                List.of(a1.getId(), a0.getId()), staleVersion);
        BusinessException ex = catchBusiness(() -> charterService.reorderArticles(villageId, req, HEADMAN_ID));
        assertThat(ex)
                .as("層2バンプ後、古い charterVersion での PATCH order は 409（VILLAGE_106）であるべし")
                .isNotNull();
        assertThat(ex.getErrorCode().getCode()).isEqualTo("VILLAGE_106");
    }

    // ══════════════════════════════════════════════════════════════════════
    // ヘルパー
    // ══════════════════════════════════════════════════════════════════════

    private void setupVillageWithCharterAndHeadmen() {
        VillageEntity v = villageRepository.saveAndFlush(VillageEntity.builder()
                .slug("chcc-" + Long.toHexString(System.nanoTime()))
                .name("並行憲章村" + System.nanoTime())
                .description("村憲章並行テスト村")
                .type(VillageType.COMMUNITY)
                .joinPolicy(VillageJoinPolicy.FREE)
                .visibility(VillageVisibility.PUBLIC)
                .category("テスト")
                .memberCountCache(0L)
                .createdByUserId(HEADMAN_ID)
                .build());
        villageId = v.getId();
        membershipRepository.saveAndFlush(membership(HEADMAN_ID, VillageRole.HEADMAN));
        membershipRepository.saveAndFlush(membership(ELDER_ID, VillageRole.ELDER));

        VillageCharterEntity c = charterRepository.saveAndFlush(VillageCharterEntity.builder()
                .villageId(villageId)
                .enactedAt(LocalDateTime.now())
                .build());
        charterId = c.getId();
    }

    private VillageMembershipEntity membership(Long userId, VillageRole role) {
        return VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(userId)
                .role(role)
                .joinedAt(LocalDateTime.now())
                .build();
    }

    private VillageCharterArticleEntity persistArticle(int sortOrder, String body) {
        return articleRepository.saveAndFlush(VillageCharterArticleEntity.builder()
                .charterId(charterId)
                .villageId(villageId)
                .sortOrder(sortOrder)
                .body(body)
                .build());
    }

    private long currentCharterVersion() {
        Long v = charterRepository.findById(charterId).map(VillageCharterEntity::getVersion).orElse(0L);
        return v == null ? 0L : v;
    }

    private void postArticleWithBarrier(CyclicBarrier barrier, Long userId, String body) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            charterService.addArticle(villageId, new CharterArticleCreateRequest(body, null), userId);
        } catch (Exception ignored) {
            // 悲観ロック未実装の現状は競合や UnsupportedOperationException が出る。最終 DB 状態で検証する。
        }
    }

    private void runWithBarrier(CyclicBarrier barrier, List<Throwable> failures, Runnable action) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            action.run();
        } catch (Throwable t) {
            failures.add(t);
        }
    }

    private boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof org.springframework.dao.CannotAcquireLockException
                    || c instanceof org.springframework.dao.DeadlockLoserDataAccessException) {
                return true;
            }
            String msg = c.getMessage();
            if (msg != null && msg.toLowerCase().contains("deadlock")) {
                return true;
            }
        }
        return false;
    }

    private BusinessException catchBusiness(Runnable action) {
        try {
            action.run();
            return null;
        } catch (BusinessException be) {
            return be;
        } catch (RuntimeException other) {
            // W1 骨格では UnsupportedOperationException 等が出る（＝red）。BusinessException でないので null 返却。
            return null;
        }
    }

    private void awaitQuietly(Future<?> f) {
        try {
            f.get(30, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // 個々の失敗は最終状態の検証に委ねる。
        }
    }
}
