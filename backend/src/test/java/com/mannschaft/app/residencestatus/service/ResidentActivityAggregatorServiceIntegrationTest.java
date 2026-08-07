package com.mannschaft.app.residencestatus.service;

import com.mannschaft.app.residencestatus.entity.ResidentActivitySnapshot;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResidentActivityAggregatorService#deleteOldSnapshots()} の統合テスト（Issue #2601）。
 *
 * <p>一括 UPDATE をバッチループで回す実装であることを、実 DB（MySQL Testcontainers）で検証する。
 * クラスレベル {@code @Transactional} は付けない。1 次キャッシュにより
 * バッチ処理側（別トランザクション）での論理削除がこのテストのコンテキストから
 * 見えなくなる事故を避けるため（既知の罠）。検証は毎回 {@link EntityManager#clear()} 後に
 * DB から読み直した {@code deletedAt} の値そのもので行う。
 *
 * <p>その代わり、フィクスチャ投入は {@link TransactionTemplate} で明示的な
 * トランザクションに包んで<b>コミットまで確定させる</b>。バッチ側は
 * {@code REQUIRES_NEW} の独立トランザクションで動くため、未コミットのフィクスチャは
 * そもそも見えないという事情もある。
 */
@DisplayName("ResidentActivityAggregatorService#deleteOldSnapshots 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class ResidentActivityAggregatorServiceIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ResidentActivityAggregatorService service;

    @Autowired
    private com.mannschaft.app.residencestatus.repository.ResidentActivitySnapshotRepository snapshotRepo;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TransactionTemplate txTemplate;

    private static final Long ORG_ID = 8001L;
    private static final Long DWELLING_ID = 8101L;
    private static final Long REGISTRY_ID = 8201L;
    private static final Long USER_ID = 8301L;

    /** 冪等テスト用。件数アサーションを行うテストと組織を分け、実行順に依存させない。 */
    private static final Long ORG_ID_IDEMPOTENT = 8002L;

    private ResidentActivitySnapshot buildSnapshot(LocalDate snapshotDate) {
        return buildSnapshot(snapshotDate, ORG_ID);
    }

    private ResidentActivitySnapshot buildSnapshot(LocalDate snapshotDate, Long organizationId) {
        return ResidentActivitySnapshot.builder()
                .organizationId(organizationId)
                .dwellingUnitId(DWELLING_ID)
                .residentRegistryId(REGISTRY_ID)
                .subjectUserId(USER_ID)
                .snapshotDate(snapshotDate)
                .activityScoreTotal(1)
                .activityBreakdownJson("{\"login\":1}")
                .build();
    }

    @Test
    @DisplayName("バッチサイズ(1000)を跨ぐ件数の古いスナップショットが全件論理削除される（ループ動作の証明）")
    void deleteOldSnapshots_バッチサイズ跨ぎで全件論理削除される() {
        LocalDate oldDate = LocalDate.now().minusDays(31);
        LocalDate withinRetentionDate = LocalDate.now().minusDays(5);

        // バッチサイズ定数を跨ぐ件数（AC-1: ループが回ることの証明）
        int oldCount = ResidentActivityAggregatorService.SNAPSHOT_DELETE_BATCH_SIZE + 250;
        txTemplate.executeWithoutResult(status -> {
            for (int i = 0; i < oldCount; i++) {
                em.persist(buildSnapshot(oldDate));
                if (i % 100 == 0) {
                    em.flush();
                    em.clear();
                }
            }
            // AC-2: cutoff 以降のものは削除対象外
            em.persist(buildSnapshot(withinRetentionDate));
            em.flush();
        });
        em.clear();

        service.deleteOldSnapshots();

        em.clear();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT snapshot_date, deleted_at FROM resident_activity_snapshots WHERE organization_id = :orgId")
                .setParameter("orgId", ORG_ID)
                .getResultList();

        long oldDeletedCount = rows.stream()
                .filter(r -> ((java.sql.Date) r[0]).toLocalDate().equals(oldDate))
                .filter(r -> r[1] != null)
                .count();
        long withinRetentionDeletedCount = rows.stream()
                .filter(r -> ((java.sql.Date) r[0]).toLocalDate().equals(withinRetentionDate))
                .filter(r -> r[1] != null)
                .count();

        assertThat(rows).hasSize(oldCount + 1);
        // AC-1: cutoff より古い分は全件論理削除される（バッチループが最後まで回った証明）
        assertThat(oldDeletedCount).isEqualTo(oldCount);
        // AC-2: cutoff 以降は削除されない
        assertThat(withinRetentionDeletedCount).isEqualTo(0);
    }

    @Test
    @DisplayName("既に論理削除済みのスナップショットは対象外（冪等）で updated_at が変化しない")
    void deleteOldSnapshots_既に削除済みは二重更新されない() {
        LocalDate oldDate = LocalDate.now().minusDays(40);
        ResidentActivitySnapshot alreadyDeleted = buildSnapshot(oldDate, ORG_ID_IDEMPOTENT);
        LocalDateTime fixedDeletedAt = LocalDateTime.now().minusDays(10);
        txTemplate.executeWithoutResult(status -> {
            em.persist(alreadyDeleted);
            em.flush();
            em.createNativeQuery(
                            "UPDATE resident_activity_snapshots SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id")
                    .setParameter("deletedAt", fixedDeletedAt)
                    .setParameter("id", alreadyDeleted.getId())
                    .executeUpdate();
        });
        em.clear();

        service.deleteOldSnapshots();

        em.clear();

        Object result = em.createNativeQuery(
                        "SELECT deleted_at FROM resident_activity_snapshots WHERE id = :id")
                .setParameter("id", alreadyDeleted.getId())
                .getResultList()
                .get(0);
        // deleted_at は最初に設定した固定値のまま（AC-3: 二重更新されない＝冪等）
        LocalDateTime persisted = (result instanceof java.sql.Timestamp ts)
                ? ts.toLocalDateTime() : (LocalDateTime) result;
        assertThat(persisted).isEqualToIgnoringNanos(fixedDeletedAt);
    }
}
