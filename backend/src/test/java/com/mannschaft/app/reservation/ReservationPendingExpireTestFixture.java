package com.mannschaft.app.reservation;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationPendingExpireScanStateEntity;
import com.mannschaft.app.reservation.entity.ReservationPolicyEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.team.entity.TeamEntity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

/** 専用MySQL試験が保存する最小の有効な進捗行を生成する。 */
final class ReservationPendingExpireTestFixture {

    private ReservationPendingExpireTestFixture() { }

    static ReservationPendingExpireScanStateEntity minimumValidState(Instant now) {
        return ReservationPendingExpireScanStateEntity.initial(now);
    }

    static TeamEntity team(String slug, String timezone) {
        return TeamEntity.builder().name("CMP1730試験").slug(slug).timezone(timezone)
                .visibility(TeamEntity.Visibility.PUBLIC).supporterEnabled(false).build();
    }

    static MembershipEntity membership(Long userId, Long teamId, LocalDateTime joinedAt) {
        return MembershipEntity.builder().userId(userId).scopeType(ScopeType.TEAM).scopeId(teamId)
                .joinedAt(joinedAt).build();
    }

    static ReservationPolicyEntity policy(Long teamId) {
        return ReservationPolicyEntity.builder().teamId(teamId).pendingExpireHours(24).build();
    }

    static ReservationSlotEntity slot(Long teamId, LocalDate date, LocalTime start, LocalTime end, int booked) {
        return ReservationSlotEntity.builder().teamId(teamId).title("CMP1730")
                .slotDate(date).startTime(start).endTime(end).capacity(booked + 1).bookedCount(booked)
                .slotStatus(SlotStatus.AVAILABLE).build();
    }

    static ReservationEntity pending(Long teamId, Long userId, Long slotId, LocalDateTime booked,
                                     UUID group, boolean primary) {
        return ReservationEntity.builder().teamId(teamId).userId(userId).lineId(1L)
                .reservationSlotId(slotId).bookedAt(booked).status(ReservationStatus.PENDING)
                .groupId(group).isGroupPrimary(primary).build();
    }
}
