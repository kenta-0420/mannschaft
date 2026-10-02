package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.domain.ArchiveReason;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F14.3 AC-701: {@code MembershipRepository#findByScopeAndActive}（通常の組合員一覧の
 * 単一参照経路 — {@code MemberQueryDispatcher} の全メソッドがここを通る）が、
 * アーカイブ在籍者（{@code left_at IS NULL} かつ {@code archived_at} 非 NULL）を
 * 除外することを実 MySQL で固定する。
 *
 * <p>設計書: docs/features/F14.3_resident_life_events.md §6.3 / AC-701
 * （「何もしなければ現れる。{@code findByScopeAndActive} は {@code left_at IS NULL} しか
 * 見ないため、アーカイブ在籍者は通過してしまう。{@code archived_at IS NULL} の追加が必要」）</p>
 */
@DisplayName("MembershipRepository アーカイブ在籍の通常一覧除外契約（AC-701）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MembershipRepositoryArchiveExclusionIT extends AbstractMySqlIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final long SCOPE_ID = 991_001L;

    @Autowired
    private MembershipRepository membershipRepository;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        membershipRepository.deleteAll();
    }

    private MembershipEntity activeMembership(Long userId) {
        return membershipRepository.save(MembershipEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build());
    }

    private MembershipEntity archivedMembership(Long userId) {
        MembershipEntity m = MembershipEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.TEAM)
                .scopeId(SCOPE_ID)
                .roleKind(RoleKind.MEMBER)
                .joinedAt(LocalDateTime.now())
                .build();
        // アーカイブ在籍: archived_at 等が非 NULL・left_at は NULL のまま（自動退会前）
        m.setArchivedAt(Instant.now());
        m.setArchiveReason(ArchiveReason.RELOCATED);
        m.setArchiveExpiresAt(Instant.now().plusSeconds(3600));
        return membershipRepository.save(m);
    }

    @Test
    @DisplayName("AC-701: アーカイブ在籍者は通常一覧・件数の双方から除外され、通常在籍者は残る")
    void archivedMemberIsExcludedFromNormalListAndCount() {
        long normalUserId = 800_000L + SEQ.incrementAndGet();
        long archivedUserId = 800_000L + SEQ.incrementAndGet();
        activeMembership(normalUserId);
        archivedMembership(archivedUserId);

        Page<MembershipEntity> page = membershipRepository
                .findByScopeAndActive(ScopeType.TEAM, SCOPE_ID, Pageable.unpaged());

        assertThat(page.getContent())
                .extracting(MembershipEntity::getUserId)
                .containsExactly(normalUserId)
                .doesNotContain(archivedUserId);
        assertThat(page.getTotalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("正常系: 通常在籍者のみの場合は従来どおり出る（除外の巻き添えがない）")
    void normalMemberStillListed() {
        long normalUserId = 800_000L + SEQ.incrementAndGet();
        activeMembership(normalUserId);

        Page<MembershipEntity> page = membershipRepository
                .findByScopeAndActive(ScopeType.TEAM, SCOPE_ID, Pageable.unpaged());

        assertThat(page.getContent())
                .extracting(MembershipEntity::getUserId)
                .containsExactly(normalUserId);
        assertThat(page.getTotalElements()).isEqualTo(1L);
    }
}
