package com.mannschaft.app.committee.event;

import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeInvitationEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeInvitationRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.role.event.MembershipChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 組織メンバーシップ終了時に、配下委員会のメンバーシップを同一トランザクションで終了する。
 *
 * <p>通常の委員会離脱 API は「唯一の委員長は離脱不可」という利用者操作の契約を維持する。
 * 一方、親組織から既に脱退した利用者を委員会に残すことはできないため、本リスナーだけは
 * 後継選定または委員会アーカイブを伴う強制クリーンアップを行う。</p>
 *
 * <p>素の {@link EventListener} による同期購読と {@link Propagation#MANDATORY} を組み合わせ、
 * 発行元トランザクションの内側で処理する。例外は捕捉しないため、クリーンアップ失敗時は
 * 組織メンバーシップ終了を含む発行元トランザクション全体がロールバックされる。</p>
 */
@Component
@RequiredArgsConstructor
public class OrganizationMembershipCommitteeCleanupListener {

    private static final Comparator<CommitteeMemberEntity> SUCCESSOR_ORDER =
            Comparator.comparingInt(OrganizationMembershipCommitteeCleanupListener::rolePriority)
                    .thenComparing(CommitteeMemberEntity::getJoinedAt)
                    .thenComparing(CommitteeMemberEntity::getId);

    private final CommitteeRepository committeeRepository;
    private final CommitteeMemberRepository committeeMemberRepository;
    private final CommitteeInvitationRepository committeeInvitationRepository;

    @Qualifier("wallClock")
    private final Clock clock;

    /** 組織から削除された利用者の委員会メンバーシップを終了する。 */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "停止すると組織脱退者が配下委員会に残り認可境界が壊れるため、組織メンバーシップ終了と常に同一トランザクションで実行する")
    @EventListener(condition = "#event.changeType().name() == 'REMOVED' && #event.scopeType().equalsIgnoreCase('ORGANIZATION')")
    @Transactional(propagation = Propagation.MANDATORY)
    public void onMembershipChanged(MembershipChangedEvent event) {
        if (event.changeType() != MembershipChangedEvent.ChangeType.REMOVED
                || !"ORGANIZATION".equalsIgnoreCase(event.scopeType())) {
            return;
        }

        LocalDateTime cleanupAt = LocalDateTime.now(clock);
        List<CommitteeEntity> committees = committeeRepository
                .findActiveCommitteesByOrganizationAndUserForUpdate(event.scopeId(), event.userId());
        for (CommitteeEntity committee : committees) {
            cleanupCommittee(committee, event.userId(), cleanupAt);
        }

        List<CommitteeInvitationEntity> pendingInvitations = committeeInvitationRepository
                .findPendingByOrganizationAndInviteeForUpdate(event.scopeId(), event.userId());
        pendingInvitations.forEach(invitation -> invitation.markCancelledAt(cleanupAt));
        if (!pendingInvitations.isEmpty()) {
            committeeInvitationRepository.saveAll(pendingInvitations);
        }
    }

    private void cleanupCommittee(CommitteeEntity committee, Long removedUserId, LocalDateTime cleanupAt) {
        List<CommitteeMemberEntity> activeMembers = committeeMemberRepository
                .findByCommitteeIdAndLeftAtIsNullOrderByJoinedAtAscIdAsc(committee.getId());
        List<CommitteeMemberEntity> removedMemberships = activeMembers.stream()
                .filter(member -> removedUserId.equals(member.getUserId()))
                .toList();
        if (removedMemberships.isEmpty()) {
            return;
        }

        List<CommitteeMemberEntity> remainingMembers = activeMembers.stream()
                .filter(member -> !removedUserId.equals(member.getUserId()))
                .toList();
        boolean removedUserWasChair = removedMemberships.stream()
                .anyMatch(member -> member.getRole() == CommitteeRole.CHAIR);
        boolean anotherChairRemains = remainingMembers.stream()
                .anyMatch(member -> member.getRole() == CommitteeRole.CHAIR);

        List<CommitteeMemberEntity> changedMemberships = new ArrayList<>(removedMemberships);
        if (remainingMembers.isEmpty() && committee.getDeletedAt() == null) {
            committee.applyStatusTransition(CommitteeStatus.ARCHIVED, cleanupAt);
            committeeRepository.save(committee);
        } else if (removedUserWasChair && !anotherChairRemains) {
            CommitteeMemberEntity successor = remainingMembers.stream()
                    .min(SUCCESSOR_ORDER)
                    .orElseThrow();
            successor.updateRole(CommitteeRole.CHAIR);
            changedMemberships.add(successor);
        }

        removedMemberships.forEach(member -> member.leaveAt(cleanupAt));
        committeeMemberRepository.saveAll(changedMemberships);
    }

    private static int rolePriority(CommitteeMemberEntity member) {
        if (member.getRole() == CommitteeRole.VICE_CHAIR) {
            return 0;
        }
        if (member.getRole() == CommitteeRole.SECRETARY) {
            return 1;
        }
        if (member.getRole() == CommitteeRole.MEMBER) {
            return 2;
        }
        return 3;
    }
}
