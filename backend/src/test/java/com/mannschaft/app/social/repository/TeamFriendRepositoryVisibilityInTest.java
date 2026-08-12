package com.mannschaft.app.social.repository;

import com.mannschaft.app.social.entity.TeamFriendEntity;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-028 Phase D — {@link TeamFriendRepository#findVisibleByTeamAIdOrTeamBId} 結合テスト。
 *
 * <p>受け入れ条件 AC-D3（歯抜けゼロ）・AC-D4（総件数正確）を実 MySQL に対して検証する。
 * 旧実装（1ページ取得後に {@code is_public} でメモリフィルタ）は、非公開の行が間に混ざると
 * 要求件数を割り込む「歯抜け」があったため、<strong>公開行と非公開行を交互に挿入した</strong>
 * フィクスチャで検証する（全件公開のフィクスチャでは歯抜けを検出できない —
 * {@code ActivityResultRepositoryVisibilityInTest} と同じ教訓）。</p>
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("TeamFriendRepository.findVisibleByTeamAIdOrTeamBId — 結合テスト")
class TeamFriendRepositoryVisibilityInTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private TeamFriendRepository teamFriendRepository;

    @PersistenceContext
    private EntityManager em;

    private static final Long SELF_TEAM_ID = 1L;

    /** 相手チームIDの採番カウンタ（team_a_id &lt; team_b_id 制約を満たすため SELF_TEAM_ID より大きい値のみ使用） */
    private long nextFriendTeamId = 1000L;

    @BeforeEach
    void setUp() {
        // AC-D3 の赤を検出できるフィクスチャ: 公開(is_public=true) 20件の間に
        // 非公開(is_public=false) を10件挟み込む。
        for (int i = 0; i < 20; i++) {
            persistFriend(true);
            if (i < 10) {
                persistFriend(false);
            }
        }
        em.flush();
        em.clear();
    }

    /**
     * AC-D3: 公開行が20件存在する以上、size=20 で必ず20件返る。
     * 旧実装は非公開行が間に混ざるとここで20件を割り込んでいた（歯抜け）。
     */
    @Test
    @DisplayName("AC-D3: 公開行20件+非公開行混在でも size=20 で必ず20件返る（歯抜けゼロ）")
    void 歯抜けゼロ() {
        Page<TeamFriendEntity> page = teamFriendRepository.findVisibleByTeamAIdOrTeamBId(
                SELF_TEAM_ID, SELF_TEAM_ID, true, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).allMatch(TeamFriendEntity::getIsPublic);
    }

    /**
     * AC-D4: 総件数は実際の公開件数（20件）と一致し、非公開行を含めた全行数（30件）や
     * 近似値にはならない。
     */
    @Test
    @DisplayName("AC-D4: 総件数は実公開件数と一致する（近似ではない）")
    void 総件数は実公開件数と一致() {
        Page<TeamFriendEntity> page = teamFriendRepository.findVisibleByTeamAIdOrTeamBId(
                SELF_TEAM_ID, SELF_TEAM_ID, true, PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(20L);
    }

    /**
     * AC-D6（拒否側）: publicOnly=true の閲覧者（SUPPORTER 相当）には非公開行が一切含まれない。
     */
    @Test
    @DisplayName("AC-D6（拒否側）: publicOnly=trueで非公開行は漏れない")
    void 非公開行は漏れない() {
        Page<TeamFriendEntity> page = teamFriendRepository.findVisibleByTeamAIdOrTeamBId(
                SELF_TEAM_ID, SELF_TEAM_ID, true, PageRequest.of(0, 100));

        assertThat(page.getContent()).hasSize(20);
        assertThat(page.getContent()).noneMatch(f -> !f.getIsPublic());
    }

    /**
     * AC-D6（許可側）: publicOnly=false（ADMIN 相当）には非公開行も含めた全件が返る。
     */
    @Test
    @DisplayName("AC-D6（許可側）: publicOnly=falseなら非公開行も含まれる")
    void publicOnlyFalseなら非公開行も含まれる() {
        Page<TeamFriendEntity> page = teamFriendRepository.findVisibleByTeamAIdOrTeamBId(
                SELF_TEAM_ID, SELF_TEAM_ID, false, PageRequest.of(0, 100));

        assertThat(page.getTotalElements()).isEqualTo(30L); // 20 公開 + 10 非公開
        assertThat(page.getContent()).anyMatch(f -> !f.getIsPublic());
    }

    private void persistFriend(boolean isPublic) {
        long friendTeamId = nextFriendTeamId++;
        long teamAId = Math.min(SELF_TEAM_ID, friendTeamId);
        long teamBId = Math.max(SELF_TEAM_ID, friendTeamId);
        TeamFriendEntity entity = TeamFriendEntity.builder()
                .teamAId(teamAId)
                .teamBId(teamBId)
                .aFollowId(1L)
                .bFollowId(2L)
                .establishedAt(LocalDateTime.now())
                .isPublic(isPublic)
                .build();
        em.persist(entity);
    }
}
