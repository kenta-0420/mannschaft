package com.mannschaft.app.gallery.repository;

import com.mannschaft.app.gallery.AlbumVisibility;
import com.mannschaft.app.gallery.entity.PhotoAlbumEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-028 Phase B — {@link PhotoAlbumRepository#findByTeamIdAndVisibilityInOrderByEventDateDesc}
 * 結合テスト。
 *
 * <p>受け入れ条件 AC-6（歯抜けゼロ）・AC-7（総件数正確）・AC-9（fail-closed 維持）を
 * 実 MySQL に対して検証する。可視（ALL_MEMBERS）行と不可視（ADMIN_ONLY）行を交互に
 * 挿入したフィクスチャで検証する（全件公開データのみでは歯抜けを検出できない）。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("PhotoAlbumRepository.findByTeamIdAndVisibilityInOrderByEventDateDesc — 結合テスト")
class PhotoAlbumRepositoryVisibilityInTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private PhotoAlbumRepository albumRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long TEAM_ID = 200L;

    @BeforeEach
    void setUp() {
        // AC-6 の赤を検出できるフィクスチャ: 可視(ALL_MEMBERS) 20件の間に
        // 不可視(ADMIN_ONLY) を10件挟み込む。
        for (int i = 0; i < 20; i++) {
            persistAlbum(AlbumVisibility.ALL_MEMBERS);
            if (i < 10) {
                persistAlbum(AlbumVisibility.ADMIN_ONLY);
            }
        }
        em.flush();
        em.clear();
    }

    /**
     * AC-6: MEMBER 相当（ALL_MEMBERS まで可視）の閲覧者でも、20件の ALL_MEMBERS 行が
     * 存在する以上 size=20 で必ず 20 件返る。
     */
    @Test
    @DisplayName("AC-6: 可視行20件+不可視行混在でも size=20 で必ず20件返る（歯抜けゼロ）")
    void 歯抜けゼロ() {
        Page<PhotoAlbumEntity> page = albumRepository.findByTeamIdAndVisibilityInOrderByEventDateDesc(
                TEAM_ID, Set.of(AlbumVisibility.ALL_MEMBERS), PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allMatch(a -> a.getVisibility() == AlbumVisibility.ALL_MEMBERS);
    }

    /**
     * AC-7: 総件数は実可視件数（20件）と一致する。
     */
    @Test
    @DisplayName("AC-7: 総件数は実可視件数と一致する（上界近似ではない）")
    void 総件数は実可視件数と一致() {
        Page<PhotoAlbumEntity> page = albumRepository.findByTeamIdAndVisibilityInOrderByEventDateDesc(
                TEAM_ID, Set.of(AlbumVisibility.ALL_MEMBERS), PageRequest.of(0, 5));

        assertThat(page.getTotalElements()).isEqualTo(20L);
    }

    /**
     * AC-9（拒否側）: MEMBER 相当の閲覧者（ADMIN_ONLY は不可視）には ADMIN_ONLY 行が漏れない。
     */
    @Test
    @DisplayName("AC-9（拒否側）: MEMBER相当の閲覧者にADMIN_ONLY行は漏れない")
    void 機微な行は漏れない() {
        Page<PhotoAlbumEntity> page = albumRepository.findByTeamIdAndVisibilityInOrderByEventDateDesc(
                TEAM_ID, Set.of(AlbumVisibility.ALL_MEMBERS), PageRequest.of(0, 100));

        assertThat(page.getContent()).noneMatch(a -> a.getVisibility() == AlbumVisibility.ADMIN_ONLY);
    }

    /**
     * AC-9（許可側）: ADMIN 相当の閲覧者（ALL_MEMBERS + ADMIN_ONLY まで可視）には
     * ADMIN_ONLY 行も正しく含まれる。
     */
    @Test
    @DisplayName("AC-9（許可側）: ADMIN相当の閲覧者にはADMIN_ONLY行が含まれる")
    void ADMIN相当なら含まれる() {
        Page<PhotoAlbumEntity> page = albumRepository.findByTeamIdAndVisibilityInOrderByEventDateDesc(
                TEAM_ID, Set.of(AlbumVisibility.ALL_MEMBERS, AlbumVisibility.ADMIN_ONLY),
                PageRequest.of(0, 100));

        assertThat(page.getTotalElements()).isEqualTo(30L); // 20 ALL_MEMBERS + 10 ADMIN_ONLY
        assertThat(page.getContent()).anyMatch(a -> a.getVisibility() == AlbumVisibility.ADMIN_ONLY);
    }

    private void persistAlbum(AlbumVisibility visibility) {
        PhotoAlbumEntity entity = PhotoAlbumEntity.builder()
                .teamId(TEAM_ID)
                .title("アルバム")
                .eventDate(LocalDate.now())
                .visibility(visibility)
                .createdBy(1L)
                .build();
        em.persist(entity);
    }
}
