package com.mannschaft.app.team.listener;

import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * F15.4 Phase 4: {@link TeamMemberCountListener} の単体テスト。
 *
 * <p>ASSIGNED → +1, REMOVED → -1, CHANGED → no-op, ORGANIZATION → 無視
 * の各分岐をモックで検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamMemberCountListener 単体テスト")
class TeamMemberCountListenerTest {

    private static final Long TEAM_ID = 100L;
    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 200L;

    @Mock private TeamRepository teamRepository;
    @InjectMocks private TeamMemberCountListener listener;

    @Nested
    @DisplayName("TEAM スコープ")
    class TeamScope {

        @Test
        @DisplayName("ASSIGNED で member_count が +1 される")
        void assignedIncrements() {
            given(teamRepository.incrementMemberCount(TEAM_ID)).willReturn(1);

            listener.onMembershipChanged(new MembershipChangedEvent(
                    USER_ID, "TEAM", TEAM_ID, MembershipChangedEvent.ChangeType.ASSIGNED));

            verify(teamRepository, times(1)).incrementMemberCount(eq(TEAM_ID));
            verify(teamRepository, never()).decrementMemberCount(eq(TEAM_ID));
        }

        @Test
        @DisplayName("REMOVED で member_count が -1 される")
        void removedDecrements() {
            given(teamRepository.decrementMemberCount(TEAM_ID)).willReturn(1);

            listener.onMembershipChanged(new MembershipChangedEvent(
                    USER_ID, "TEAM", TEAM_ID, MembershipChangedEvent.ChangeType.REMOVED));

            verify(teamRepository, times(1)).decrementMemberCount(eq(TEAM_ID));
            verify(teamRepository, never()).incrementMemberCount(eq(TEAM_ID));
        }

        @Test
        @DisplayName("CHANGED ではカウント更新されない（人数不変）")
        void changedDoesNothing() {
            listener.onMembershipChanged(new MembershipChangedEvent(
                    USER_ID, "TEAM", TEAM_ID, MembershipChangedEvent.ChangeType.CHANGED));

            verify(teamRepository, never()).incrementMemberCount(eq(TEAM_ID));
            verify(teamRepository, never()).decrementMemberCount(eq(TEAM_ID));
        }

        @Test
        @DisplayName("同一 teamId への複数 ASSIGNED で都度インクリメントされる")
        void multipleAssignmentsIncrementEachTime() {
            given(teamRepository.incrementMemberCount(TEAM_ID)).willReturn(1);

            listener.onMembershipChanged(new MembershipChangedEvent(
                    1L, "TEAM", TEAM_ID, MembershipChangedEvent.ChangeType.ASSIGNED));
            listener.onMembershipChanged(new MembershipChangedEvent(
                    2L, "TEAM", TEAM_ID, MembershipChangedEvent.ChangeType.ASSIGNED));
            listener.onMembershipChanged(new MembershipChangedEvent(
                    3L, "TEAM", TEAM_ID, MembershipChangedEvent.ChangeType.ASSIGNED));

            verify(teamRepository, times(3)).incrementMemberCount(eq(TEAM_ID));
        }

        @Test
        @DisplayName("Repository が例外を投げてもリスナーは伝播させない（WARN ログのみ）")
        void swallowsRepositoryException() {
            given(teamRepository.incrementMemberCount(TEAM_ID))
                    .willThrow(new RuntimeException("DB error"));

            // 例外が伝播しないことを確認
            listener.onMembershipChanged(new MembershipChangedEvent(
                    USER_ID, "TEAM", TEAM_ID, MembershipChangedEvent.ChangeType.ASSIGNED));

            verify(teamRepository, times(1)).incrementMemberCount(eq(TEAM_ID));
        }
    }

    @Nested
    @DisplayName("ORGANIZATION スコープは対象外")
    class OrganizationScope {

        @Test
        @DisplayName("ASSIGNED でも teamRepository は呼ばれない")
        void organizationAssignedIsIgnored() {
            listener.onMembershipChanged(new MembershipChangedEvent(
                    USER_ID, "ORGANIZATION", ORG_ID, MembershipChangedEvent.ChangeType.ASSIGNED));

            verifyNoInteractions(teamRepository);
        }

        @Test
        @DisplayName("REMOVED でも teamRepository は呼ばれない")
        void organizationRemovedIsIgnored() {
            listener.onMembershipChanged(new MembershipChangedEvent(
                    USER_ID, "ORGANIZATION", ORG_ID, MembershipChangedEvent.ChangeType.REMOVED));

            verifyNoInteractions(teamRepository);
        }
    }
}
