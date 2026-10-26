package com.mannschaft.app.cms.service;

import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * {@link BlogMediaService#cleanupOrphanMedia()} の統合テスト（Issue #2601）。
 *
 * <p>R2 削除という取り消せない外部操作を含む 1 件処理が、REQUIRES_NEW の独立トランザクションで
 * コミットされることを実 DB（MySQL Testcontainers）で検証する。クラスレベル {@code @Transactional} は
 * 付けない。1 次キャッシュにより、独立トランザクション側でコミットされた削除がこのテストの
 * コンテキストから見えなくなる事故を避けるため（既知の罠）。検証は毎回
 * {@link EntityManager#clear()} 後に DB から native query で読み直した値で行う。
 *
 * <p>フィクスチャ投入は {@link TransactionTemplate} で明示的なトランザクションに包んで
 * コミットまで確定させる。バッチ側は {@code REQUIRES_NEW} の独立トランザクションで動くため、
 * 未コミットのフィクスチャはそもそも見えないという事情もある。
 *
 * <p>R2 はモック化する（実 SDK 接続を避けるため）。使用量減算（{@link StorageQuotaService}）も
 * モック化し、呼び出し有無・引数のみを検証する（実クォータ行は本テストの関心外）。
 */
@DisplayName("BlogMediaService#cleanupOrphanMedia 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BlogMediaOrphanCleanupIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private BlogMediaService blogMediaService;

    @MockitoBean
    private R2StorageService r2StorageService;

    @MockitoBean
    private StorageQuotaService storageQuotaService;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TransactionTemplate txTemplate;

    /** 本テストが投入した行を識別するための s3Key プレフィックス。 */
    private static final String KEY_PREFIX = "blog/TEAM/8801/2601-it-";

    /**
     * 本テストが投入した行を毎回撤去する。
     *
     * <p>バッチは組織を問わず全孤立行を全件走査するため、投入したフィクスチャを残すと
     * 後続のテストや他のテストクラスのバッチ実行まで巻き込んでしまう。
     */
    @AfterEach
    void cleanUpFixtures() {
        txTemplate.executeWithoutResult(status ->
                em.createNativeQuery("DELETE FROM blog_media_uploads WHERE s3_key LIKE :prefix")
                        .setParameter("prefix", KEY_PREFIX + "%")
                        .executeUpdate());
    }

    /**
     * 孤立メディア（blog_post_id IS NULL, created_at が 72 時間より過去）を 1 件投入する。
     * created_at は Entity の {@code @Builder.Default} を上書きするため native query で直接書く。
     *
     * @return 投入した行の ID
     */
    private Long insertOrphan(String s3Key, long fileSize) {
        em.createNativeQuery(
                        "INSERT INTO blog_media_uploads "
                                + "(blog_post_id, uploader_id, media_type, s3_key, file_size, content_type, "
                                + "processing_status, created_at) "
                                + "VALUES (NULL, 1, 'IMAGE', :s3Key, :fileSize, 'image/jpeg', 'READY', :createdAt)")
                .setParameter("s3Key", s3Key)
                .setParameter("fileSize", fileSize)
                .setParameter("createdAt", LocalDateTime.now().minusHours(100))
                .executeUpdate();
        return ((Number) em.createNativeQuery("SELECT id FROM blog_media_uploads WHERE s3_key = :s3Key")
                        .setParameter("s3Key", s3Key)
                        .getSingleResult())
                .longValue();
    }

    private boolean rowExists(Long id) {
        return !em.createNativeQuery("SELECT id FROM blog_media_uploads WHERE id = :id")
                .setParameter("id", id)
                .getResultList()
                .isEmpty();
    }

    @Test
    @DisplayName("複数件が対象のとき、途中の1件で例外が起きても他の件の削除はコミットされて残る")
    void cleanupOrphanMedia_途中の1件が失敗しても他の削除はコミットされる() {
        String okKey1 = KEY_PREFIX + "ok1.jpg";
        String brokenKey = KEY_PREFIX + "broken.jpg";
        String okKey2 = KEY_PREFIX + "ok2.jpg";

        Long okId1 = txTemplate.execute(status -> insertOrphan(okKey1, 1024L));
        Long brokenId = txTemplate.execute(status -> insertOrphan(brokenKey, 1024L));
        Long okId2 = txTemplate.execute(status -> insertOrphan(okKey2, 1024L));
        em.clear();

        // 想定外の例外（R2 削除ではなく、使用量減算側）を broken 行にのみ発生させる。
        // これにより broken の 1 件だけが REQUIRES_NEW トランザクション内でロールバックされ、
        // 他の 2 件のコミットには影響しないことを検証できる。
        doThrow(new RuntimeException("想定外の使用量減算エラー"))
                .when(storageQuotaService).recordDeletion(
                        any(), any(), anyLong(), any(), anyString(), eq(brokenId), any());

        // 本丸: バッチが例外を外に投げずに完走すること
        assertThatCode(() -> blogMediaService.cleanupOrphanMedia()).doesNotThrowAnyException();

        em.clear();

        // 正常系2件はコミットされて DB から削除されている
        assertThat(rowExists(okId1)).isFalse();
        assertThat(rowExists(okId2)).isFalse();
        // 異常系1件は独立トランザクションがロールバックされ、行が残る
        assertThat(rowExists(brokenId)).isTrue();
    }

    @Test
    @DisplayName("R2削除に失敗した場合、DB行の削除は確定するが使用量は減算しない（実体が残るため）")
    void cleanupOrphanMedia_R2削除失敗でもDB削除は確定する() {
        String failKey = KEY_PREFIX + "r2fail.jpg";
        Long failId = txTemplate.execute(status -> insertOrphan(failKey, 2048L));
        em.clear();

        doThrow(new RuntimeException("R2接続エラー")).when(r2StorageService).delete(failKey);

        assertThatCode(() -> blogMediaService.cleanupOrphanMedia()).doesNotThrowAnyException();

        em.clear();

        // R2 削除に失敗しても DB 行は削除確定（孤児オブジェクトにはなるが孤立行はDBに残さない）
        assertThat(rowExists(failId)).isFalse();
        then(r2StorageService).should().delete(failKey);
        // R2 にオブジェクトが残っている以上、使用量を減算してはならない。
        // 減算すると used_bytes が実体より過少になり、以後の上限判定を誤らせる。
        then(storageQuotaService).should(never()).recordDeletion(
                any(), any(), anyLong(), any(), anyString(), eq(failId), any());
    }

    @Test
    @DisplayName("既存の二重減算防止(claim-then-act)は壊れていない: 条件付きDELETEで行を確保できた実行だけが使用量を減算する")
    void cleanupOrphanMedia_二重減算防止が壊れていない() {
        String key = "blog/TEAM/8801/2601-it-dedup.jpg";
        Long id = txTemplate.execute(status -> insertOrphan(key, 4096L));
        em.clear();

        // 1回目実行: 行を確保して削除・使用量減算する
        blogMediaService.cleanupOrphanMedia();
        em.clear();
        assertThat(rowExists(id)).isFalse();

        // 2回目実行: 対象行は既に削除済みで findByBlogPostIdIsNullAndCreatedAtBefore にも出てこないため
        // Runner は再度呼ばれない。使用量減算の呼び出し回数が1回のままであることを検証する。
        blogMediaService.cleanupOrphanMedia();
        em.clear();

        then(storageQuotaService).should(times(1)).recordDeletion(
                eq(StorageScopeType.TEAM), eq(8801L), eq(4096L), any(), anyString(), eq(id), any());
    }
}
