package com.mannschaft.app.recruitment.repository;

import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentFriendTargetEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
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
 * 親行をエンティティ経由で永続化した上で、3 粒度の宛先を save / find / delete する
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
        // エンティティ経由で永続化する。native SQL だと ddl-auto:create 生成スキーマで
        // @Builder.Default 列（confirmed_count 等）が「NOT NULL・DB既定なし」になり INSERT が
        // 落ちるため不可。ビルダー経由なら @Builder.Default が適用され全列が埋まる。
        RecruitmentListingEntity listing = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(1L)
                .categoryId(CATEGORY_ID)
                .title(title)
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(now.plusDays(7))
                .endAt(now.plusDays(7).plusHours(2))
                .applicationDeadline(now.plusDays(5))
                .autoCancelAt(now.plusDays(5))
                .capacity(10)
                .minCapacity(4)
                .visibility(RecruitmentVisibility.FRIEND_TEAMS_ONLY)
                .createdBy(CREATED_BY)
                .build();
        em.persist(listing);
        em.flush();
        return listing.getId();
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
