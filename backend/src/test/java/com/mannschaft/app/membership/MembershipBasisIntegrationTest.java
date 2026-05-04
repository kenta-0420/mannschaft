package com.mannschaft.app.membership;

import com.mannschaft.app.membership.domain.LeaveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.membership.entity.PositionEntity;
import com.mannschaft.app.membership.repository.MemberPositionRepository;
import com.mannschaft.app.membership.repository.MembershipRepository;
import com.mannschaft.app.membership.repository.PositionRepository;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F00.5 メンバーシップ基盤の統合テスト（Testcontainers MySQL）。
 *
 * <p>設計書 §14.2 のスコープ:</p>
 * <ul>
 *   <li>DDL 適用と CHECK / UNIQUE 制約の発火検証</li>
 *   <li>部分 UNIQUE（active_key）の挙動 — 古い行 NULL / 新規行 NOT NULL</li>
 *   <li>再加入時の uq_memberships_active 衝突</li>
 * </ul>
 */
@DisplayName("F00.5 メンバーシップ基盤 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MembershipBasisIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private MemberPositionRepository memberPositionRepository;

    @Autowired
    private PositionRepository positionRepository;

    @PersistenceContext
    private EntityManager em;

    @Test
    @Transactional
    @DisplayName("DDL: memberships に INSERT / SELECT が成立する")
    void insertAndSelect() {
        MembershipEntity entity = MembershipEntity.builder()
                .userId(1L)
                .scopeType(ScopeType.TEAM)
                .scopeId(100L)
                .roleKind(RoleKind.MEMBER)
                .build();
        MembershipEntity saved = membershipRepository.save(entity);
        em.flush();

        Optional<MembershipEntity> found = membershipRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getRoleKind()).isEqualTo(RoleKind.MEMBER);
        assertThat(found.get().getScopeType()).isEqualTo(ScopeType.TEAM);
        assertThat(found.get().isActive()).isTrue();
    }

    @Test
    @Transactional
    @DisplayName("uq_memberships_active: 同一 user × scope のアクティブ 2 行目は拒否")
    void duplicateActiveRejected() {
        membershipRepository.save(MembershipEntity.builder()
                .userId(2L).scopeType(ScopeType.TEAM).scopeId(101L)
                .roleKind(RoleKind.MEMBER).build());
        em.flush();

        assertThatThrownBy(() -> {
            membershipRepository.save(MembershipEntity.builder()
                    .userId(2L).scopeType(ScopeType.TEAM).scopeId(101L)
                    .roleKind(RoleKind.MEMBER).build());
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    @DisplayName("再加入: 退会済の旧行を残しつつ新行 INSERT が成立")
    void rejoinAfterLeave() {
        // 入会
        MembershipEntity first = membershipRepository.save(MembershipEntity.builder()
                .userId(3L).scopeType(ScopeType.TEAM).scopeId(102L)
                .roleKind(RoleKind.MEMBER).build());
        em.flush();

        // 退会（少し未来の時刻にして CHECK chk_memberships_period をクリア）
        first.setLeftAt(LocalDateTime.now().plusSeconds(1));
        first.setLeaveReason(LeaveReason.SELF);
        membershipRepository.save(first);
        em.flush();

        // 再加入（joined_at は新時刻、active_key は新行のみ立つ）
        MembershipEntity rejoin = membershipRepository.save(MembershipEntity.builder()
                .userId(3L).scopeType(ScopeType.TEAM).scopeId(102L)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now().plusSeconds(2))
                .build());
        em.flush();

        List<MembershipEntity> history = membershipRepository
                .findHistoryByUserAndScope(3L, ScopeType.TEAM, 102L);
        assertThat(history).hasSize(2);
        assertThat(rejoin.getId()).isNotEqualTo(first.getId());
    }

    @Test
    @Transactional
    @DisplayName("chk_memberships_period: left_at < joined_at は CHECK で拒否")
    void periodInvertedRejected() {
        MembershipEntity entity = MembershipEntity.builder()
                .userId(4L).scopeType(ScopeType.TEAM).scopeId(103L)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build();
        membershipRepository.save(entity);
        em.flush();

        entity.setLeftAt(LocalDateTime.now().minusYears(1));
        entity.setLeaveReason(LeaveReason.OTHER);

        assertThatThrownBy(() -> {
            membershipRepository.save(entity);
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    @DisplayName("chk_memberships_left_reason: left_at と leave_reason の片方だけは拒否")
    void leftReasonInconsistencyRejected() {
        MembershipEntity entity = MembershipEntity.builder()
                .userId(5L).scopeType(ScopeType.TEAM).scopeId(104L)
                .roleKind(RoleKind.MEMBER).build();
        membershipRepository.save(entity);
        em.flush();

        entity.setLeftAt(LocalDateTime.now().plusSeconds(1));
        // leave_reason をセットしない → CHECK で拒否

        assertThatThrownBy(() -> {
            membershipRepository.save(entity);
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    @DisplayName("positions: uq_positions_scope_name が同 scope での重複を拒否")
    void positionScopeNameUnique() {
        positionRepository.save(PositionEntity.builder()
                .scopeType(ScopeType.TEAM).scopeId(105L)
                .name("TREASURER").displayName("会計係").build());
        em.flush();

        assertThatThrownBy(() -> {
            positionRepository.save(PositionEntity.builder()
                    .scopeType(ScopeType.TEAM).scopeId(105L)
                    .name("TREASURER").displayName("会計係2").build());
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
