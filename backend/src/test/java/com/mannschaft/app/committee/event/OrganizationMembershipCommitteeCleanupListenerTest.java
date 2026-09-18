package com.mannschaft.app.committee.event;

import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationResolution;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeInvitationRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link OrganizationMembershipCommitteeCleanupListener} の単体・同期購読契約テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("組織脱退時の委員会メンバーシップクリーンアップ")
class OrganizationMembershipCommitteeCleanupListenerTest {

    private static final long ORGANIZATION_ID = 10L;
    private static final long COMMITTEE_ID = 20L;
    private static final long REMOVED_USER_ID = 30L;
    private static final LocalDateTime CLEANUP_AT = LocalDateTime.of(2026, 8, 20, 10, 11);
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-08-20T10:11:00Z"), ZoneOffset.UTC);

    @Mock
    private CommitteeRepository committeeRepository;

    @Mock
    private CommitteeMemberRepository committeeMemberRepository;

    @Mock
    private CommitteeInvitationRepository committeeInvitationRepository;

    @Nested
    @DisplayName("後継者選定")
    class Succession {

        @Test
        @DisplayName("唯一の委員長が脱退すると参加日時より役職優先度を優先して副委員長を昇格する")
        void 唯一の委員長脱退_役職優先で副委員長を昇格する() {
            CommitteeEntity committee = committee(CommitteeStatus.ACTIVE);
            CommitteeMemberEntity chair = member(1L, REMOVED_USER_ID, CommitteeRole.CHAIR, 1);
            CommitteeMemberEntity secretary = member(2L, 40L, CommitteeRole.SECRETARY, 2);
            CommitteeMemberEntity viceChair = member(3L, 50L, CommitteeRole.VICE_CHAIR, 3);
            given(committeeRepository.findActiveCommitteesByOrganizationAndUserForUpdate(
                    ORGANIZATION_ID, REMOVED_USER_ID)).willReturn(List.of(committee));
            given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(COMMITTEE_ID))
                    .willReturn(List.of(chair, secretary, viceChair));

            listener().onMembershipChanged(removedEvent());

            assertThat(chair.getLeftAt()).isEqualTo(CLEANUP_AT);
            assertThat(viceChair.getRole()).isEqualTo(CommitteeRole.CHAIR);
            assertThat(secretary.getRole()).isEqualTo(CommitteeRole.SECRETARY);
            verify(committeeMemberRepository).saveAll(List.of(chair, viceChair));
            verify(committeeRepository, never()).save(committee);
        }

        @Test
        @DisplayName("同順位なら参加日時が古いメンバーを昇格する")
        void 同順位_参加日時順で昇格する() {
            CommitteeEntity committee = committee(CommitteeStatus.ACTIVE);
            CommitteeMemberEntity chair = member(1L, REMOVED_USER_ID, CommitteeRole.CHAIR, 1);
            CommitteeMemberEntity newer = member(2L, 40L, CommitteeRole.VICE_CHAIR, 4);
            CommitteeMemberEntity older = member(3L, 50L, CommitteeRole.VICE_CHAIR, 2);
            given(committeeRepository.findActiveCommitteesByOrganizationAndUserForUpdate(
                    ORGANIZATION_ID, REMOVED_USER_ID)).willReturn(List.of(committee));
            given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(COMMITTEE_ID))
                    .willReturn(List.of(chair, newer, older));

            listener().onMembershipChanged(removedEvent());

            assertThat(older.getRole()).isEqualTo(CommitteeRole.CHAIR);
            assertThat(newer.getRole()).isEqualTo(CommitteeRole.VICE_CHAIR);
        }

        @Test
        @DisplayName("同順位かつ同じ参加日時ならIDが小さいメンバーを昇格する")
        void 同順位同参加日時_ID順で昇格する() {
            CommitteeEntity committee = committee(CommitteeStatus.ACTIVE);
            CommitteeMemberEntity chair = member(1L, REMOVED_USER_ID, CommitteeRole.CHAIR, 1);
            CommitteeMemberEntity laterId = member(9L, 40L, CommitteeRole.VICE_CHAIR, 2);
            CommitteeMemberEntity earlierId = member(8L, 50L, CommitteeRole.VICE_CHAIR, 2);
            given(committeeRepository.findActiveCommitteesByOrganizationAndUserForUpdate(
                    ORGANIZATION_ID, REMOVED_USER_ID)).willReturn(List.of(committee));
            given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(COMMITTEE_ID))
                    .willReturn(List.of(chair, laterId, earlierId));

            listener().onMembershipChanged(removedEvent());

            assertThat(earlierId.getRole()).isEqualTo(CommitteeRole.CHAIR);
            assertThat(laterId.getRole()).isEqualTo(CommitteeRole.VICE_CHAIR);
        }

        @Test
        @DisplayName("別の委員長が残る場合は新たな委員長を昇格しない")
        void 別委員長あり_追加昇格しない() {
            CommitteeEntity committee = committee(CommitteeStatus.ACTIVE);
            CommitteeMemberEntity removedChair = member(1L, REMOVED_USER_ID, CommitteeRole.CHAIR, 1);
            CommitteeMemberEntity remainingChair = member(2L, 40L, CommitteeRole.CHAIR, 2);
            CommitteeMemberEntity viceChair = member(3L, 50L, CommitteeRole.VICE_CHAIR, 3);
            given(committeeRepository.findActiveCommitteesByOrganizationAndUserForUpdate(
                    ORGANIZATION_ID, REMOVED_USER_ID)).willReturn(List.of(committee));
            given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(COMMITTEE_ID))
                    .willReturn(List.of(removedChair, remainingChair, viceChair));

            listener().onMembershipChanged(removedEvent());

            assertThat(remainingChair.getRole()).isEqualTo(CommitteeRole.CHAIR);
            assertThat(viceChair.getRole()).isEqualTo(CommitteeRole.VICE_CHAIR);
            verify(committeeMemberRepository).saveAll(List.of(removedChair));
        }
    }

    @Nested
    @DisplayName("終了・アーカイブ・失敗契約")
    class CleanupContract {

        @Test
        @DisplayName("残存メンバーがゼロなら委員会をアーカイブして同じ時刻で対象メンバーを終了する")
        void 残存ゼロ_委員会をアーカイブする() {
            CommitteeEntity committee = committee(CommitteeStatus.ACTIVE);
            CommitteeMemberEntity onlyMember = member(1L, REMOVED_USER_ID, CommitteeRole.MEMBER, 1);
            given(committeeRepository.findActiveCommitteesByOrganizationAndUserForUpdate(
                    ORGANIZATION_ID, REMOVED_USER_ID)).willReturn(List.of(committee));
            given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(COMMITTEE_ID))
                    .willReturn(List.of(onlyMember));

            listener().onMembershipChanged(removedEvent());

            assertThat(onlyMember.getLeftAt()).isEqualTo(CLEANUP_AT);
            assertThat(committee.getStatus()).isEqualTo(CommitteeStatus.ARCHIVED);
            assertThat(committee.getArchivedAt()).isEqualTo(CLEANUP_AT);
            verify(committeeRepository).save(committee);
        }

        @Test
        @DisplayName("論理削除済み委員会でも現役メンバーを終了し状態更新はしない")
        void 論理削除済み委員会_現役メンバーだけ終了する() {
            CommitteeEntity committee = CommitteeEntity.builder()
                    .id(COMMITTEE_ID)
                    .organizationId(ORGANIZATION_ID)
                    .name("削除済み委員会")
                    .status(CommitteeStatus.ACTIVE)
                    .deletedAt(LocalDateTime.of(2026, 8, 1, 0, 0))
                    .build();
            CommitteeMemberEntity onlyMember = member(1L, REMOVED_USER_ID, CommitteeRole.MEMBER, 1);
            given(committeeRepository.findActiveCommitteesByOrganizationAndUserForUpdate(
                    ORGANIZATION_ID, REMOVED_USER_ID)).willReturn(List.of(committee));
            given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(COMMITTEE_ID))
                    .willReturn(List.of(onlyMember));

            listener().onMembershipChanged(removedEvent());

            assertThat(onlyMember.getLeftAt()).isEqualTo(CLEANUP_AT);
            assertThat(committee.getStatus()).isEqualTo(CommitteeStatus.ACTIVE);
            verify(committeeRepository, never()).save(committee);
        }

        @Test
        @DisplayName("組織配下委員会の未解決招集状を同じトランザクションでキャンセルする")
        void 未解決招集状_キャンセルする() {
            CommitteeInvitationEntity invitation = CommitteeInvitationEntity.builder()
                    .id(100L)
                    .committeeId(COMMITTEE_ID)
                    .inviteeUserId(REMOVED_USER_ID)
                    .inviteToken("token")
                    .expiresAt(CLEANUP_AT.plusDays(1))
                    .build();
            given(committeeRepository.findActiveCommitteesByOrganizationAndUserForUpdate(
                    ORGANIZATION_ID, REMOVED_USER_ID)).willReturn(List.of());
            given(committeeInvitationRepository.findPendingByOrganizationAndInviteeForUpdate(
                    ORGANIZATION_ID, REMOVED_USER_ID)).willReturn(List.of(invitation));

            listener().onMembershipChanged(removedEvent());

            assertThat(invitation.getResolution()).isEqualTo(CommitteeInvitationResolution.CANCELLED);
            assertThat(invitation.getResolvedAt()).isEqualTo(CLEANUP_AT);
            verify(committeeInvitationRepository).saveAll(List.of(invitation));
        }

        @Test
        @DisplayName("保存失敗を握り潰さず発行元へ伝播する")
        void 保存失敗_例外を伝播する() {
            CommitteeEntity committee = committee(CommitteeStatus.ACTIVE);
            CommitteeMemberEntity member = member(1L, REMOVED_USER_ID, CommitteeRole.MEMBER, 1);
            given(committeeRepository.findActiveCommitteesByOrganizationAndUserForUpdate(
                    ORGANIZATION_ID, REMOVED_USER_ID)).willReturn(List.of(committee));
            given(committeeMemberRepository.findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(COMMITTEE_ID))
                    .willReturn(List.of(member));
            given(committeeMemberRepository.saveAll(anyList())).willThrow(new IllegalStateException("DB failure"));

            assertThatThrownBy(() -> listener().onMembershipChanged(removedEvent()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("DB failure");
        }

        @Test
        @DisplayName("TEAMまたはREMOVED以外のイベントは直接呼び出されても無視する")
        void 対象外イベント_何もしない() {
            listener().onMembershipChanged(new MembershipChangedEvent(
                    REMOVED_USER_ID, "TEAM", ORGANIZATION_ID, MembershipChangedEvent.ChangeType.REMOVED));
            listener().onMembershipChanged(new MembershipChangedEvent(
                    REMOVED_USER_ID, "ORGANIZATION", ORGANIZATION_ID, MembershipChangedEvent.ChangeType.CHANGED));

            verifyNoInteractions(committeeRepository, committeeMemberRepository);
        }
    }

    @Nested
    @DisplayName("同期トランザクション・ロック契約")
    class TransactionAndLockContract {

        @Test
        @DisplayName("リスナーはORGANIZATIONのREMOVEDを同期購読し既存トランザクションを必須とする")
        void リスナー注釈契約() throws Exception {
            Method method = OrganizationMembershipCommitteeCleanupListener.class
                    .getMethod("onMembershipChanged", MembershipChangedEvent.class);

            EventListener eventListener = method.getAnnotation(EventListener.class);
            Transactional transactional = method.getAnnotation(Transactional.class);
            assertThat(eventListener).isNotNull();
            assertThat(eventListener.condition()).contains("REMOVED", "ORGANIZATION");
            assertThat(transactional).isNotNull();
            assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
        }

        @Test
        @DisplayName("対象委員会と現役メンバーの取得は悲観書込ロックを宣言する")
        void リポジトリロック契約() throws Exception {
            Method committees = CommitteeRepository.class.getMethod(
                    "findActiveCommitteesByOrganizationAndUserForUpdate", Long.class, Long.class);
            Method members = CommitteeMemberRepository.class.getMethod(
                    "findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc", Long.class);

            assertThat(committees.getAnnotation(Query.class).nativeQuery()).isTrue();
            assertThat(committees.getAnnotation(Query.class).value())
                    .contains("ORDER BY c.id ASC", "FOR UPDATE");
            assertThat(members.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        }
    }

    private OrganizationMembershipCommitteeCleanupListener listener() {
        return new OrganizationMembershipCommitteeCleanupListener(
                committeeRepository, committeeMemberRepository, committeeInvitationRepository, FIXED_CLOCK);
    }

    private static MembershipChangedEvent removedEvent() {
        return new MembershipChangedEvent(
                REMOVED_USER_ID,
                "ORGANIZATION",
                ORGANIZATION_ID,
                MembershipChangedEvent.ChangeType.REMOVED);
    }

    private static CommitteeEntity committee(CommitteeStatus status) {
        return CommitteeEntity.builder()
                .id(COMMITTEE_ID)
                .organizationId(ORGANIZATION_ID)
                .name("委員会")
                .status(status)
                .build();
    }

    private static CommitteeMemberEntity member(
            Long id, Long userId, CommitteeRole role, int joinedAtDay) {
        return CommitteeMemberEntity.builder()
                .id(id)
                .committeeId(COMMITTEE_ID)
                .userId(userId)
                .role(role)
                .joinedAt(LocalDateTime.of(2026, 1, joinedAtDay, 0, 0))
                .build();
    }
}
