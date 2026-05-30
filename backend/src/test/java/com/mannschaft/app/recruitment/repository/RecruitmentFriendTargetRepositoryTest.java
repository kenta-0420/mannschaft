package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.entity.RecruitmentFriendTargetEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RecruitmentFriendTargetRepository} 結合テスト。F22.1 市・部隊1。
 *
 * <p>実 MySQL（Testcontainers・singleton container）に対し、recruitment_listings の
 * 親行を native SQL で INSERT した上で、3 粒度の宛先を save / find / delete する
 * 基本動作を検証する。Docker 不在時は {@code @EnabledIf} によりスキップされる。</p>
 *
 * <p>サービスロジックの契約テスト（MARKET_001 等）は第二陣で test-first に実装する
 * ため本テストには含めない。</p>
 */
@Transactional
@DisplayName("RecruitmentFriendTargetRepository 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class RecruitmentFriendTargetRepositoryTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private RecruitmentFriendTargetRepository repository;

    @PersistenceContext
    private EntityManager em;

    private static final Long CATEGORY_ID = 1L; // Flyway 初期データ futsal_open
    private static final Long CREATED_BY = 1L;

    /**
     * recruitment_listings の親行を native SQL で INSERT し、採番された ID を返す。
     * FK（fk_rft_listing）を満たすために必要。
     */
    private Long insertListing(String title) {
        LocalDateTime now = LocalDateTime.now();
        em.createNativeQuery("""
                INSERT INTO recruitment_listings
                  (scope_type, scope_id, category_id, title, participation_type,
                   start_at, end_at, application_deadline, auto_cancel_at,
                   capacity, min_capacity, visibility, status, created_by)
                VALUES
                  ('TEAM', 1, :cat, :title, 'INDIVIDUAL',
                   :startAt, :endAt, :deadline, :autoCancel,
                   10, 4, 'FRIEND_TEAMS_ONLY', 'DRAFT', :createdBy)
                """)
                .setParameter("cat", CATEGORY_ID)
                .setParameter("title", title)
                .setParameter("startAt", now.plusDays(7))
                .setParameter("endAt", now.plusDays(7).plusHours(2))
                .setParameter("deadline", now.plusDays(5))
                .setParameter("autoCancel", now.plusDays(5))
                .setParameter("createdBy", CREATED_BY)
                .executeUpdate();
        Number id = (Number) em.createNativeQuery(
                        "SELECT id FROM recruitment_listings WHERE title = :title")
                .setParameter("title", title)
                .getSingleResult();
        return id.longValue();
    }

    @Test
    @DisplayName("3 粒度の宛先を保存し listing_id で取得できる（UUIDv7 PK 採番）")
    void 三粒度の宛先を保存しlistingIdで取得できる() {
        Long listingId = insertListing("rft-listing-all");

        repository.save(RecruitmentFriendTargetEntity.ofAllFriends(listingId));
        repository.save(RecruitmentFriendTargetEntity.ofFolder(listingId, 200L));
        repository.save(RecruitmentFriendTargetEntity.ofTeam(listingId, 300L));
        em.flush();
        em.clear();

        List<RecruitmentFriendTargetEntity> found = repository.findByListingId(listingId);

        assertThat(found).hasSize(3);
        assertThat(found).allSatisfy(t -> {
            assertThat(t.getId()).isNotNull();
            assertThat(t.getListingId()).isEqualTo(listingId);
            assertThat(t.getCreatedAt()).isNotNull();
        });
        assertThat(repository.countByListingId(listingId)).isEqualTo(3);
    }

    @Test
    @DisplayName("findByTeamIdIn は TEAM 宛先のみ取得する")
    void findByTeamIdInはTEAM宛先のみ取得する() {
        Long listingId = insertListing("rft-listing-team");
        repository.save(RecruitmentFriendTargetEntity.ofTeam(listingId, 555L));
        repository.save(RecruitmentFriendTargetEntity.ofAllFriends(listingId));
        em.flush();
        em.clear();

        List<RecruitmentFriendTargetEntity> found = repository.findByTeamIdIn(List.of(555L, 999L));

        assertThat(found).hasSize(1);
        assertThat(found.get(0).getTeamId()).isEqualTo(555L);
    }

    @Test
    @DisplayName("deleteByFolderId はフォルダ宛先を削除する（フォルダ削除連動）")
    void deleteByFolderIdはフォルダ宛先を削除する() {
        Long listingId = insertListing("rft-listing-folder");
        repository.save(RecruitmentFriendTargetEntity.ofFolder(listingId, 777L));
        repository.save(RecruitmentFriendTargetEntity.ofAllFriends(listingId));
        em.flush();
        em.clear();

        int deleted = repository.deleteByFolderId(777L);
        em.flush();
        em.clear();

        assertThat(deleted).isEqualTo(1);
        assertThat(repository.findByFolderId(777L)).isEmpty();
        assertThat(repository.countByListingId(listingId)).isEqualTo(1);
    }
}
