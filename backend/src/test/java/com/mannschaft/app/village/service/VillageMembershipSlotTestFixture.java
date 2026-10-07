package com.mannschaft.app.village.service;

import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageJoinRequestRepository.AdmissionSubject;
import com.mannschaft.app.village.repository.VillageMembershipRepository.AdmissionPresenceRow;
import com.mannschaft.app.village.repository.VillageMembershipRepository.AdmissionSlotRow;

import java.util.List;
import java.util.stream.IntStream;

/** 入村current scalar専用の最小fixture。managed entityやRR snapshotを入力にしない。 */
public final class VillageMembershipSlotTestFixture {
    private VillageMembershipSlotTestFixture() { }

    public static List<AdmissionSlotRow> occupied(int count) {
        if (count < 0 || count > 100) {
            throw new IllegalArgumentException("占有枠数は0..100です");
        }
        return IntStream.rangeClosed(1, count).mapToObj(i -> slot((short) i)).toList();
    }

    public static AdmissionSlotRow slot(Short slot) { return new Slot(slot); }
    public static AdmissionPresenceRow presence(boolean banned) { return new Presence(banned ? 1 : 0); }
    public static AdmissionSubject subject(VillageSubjectType type, Long id) { return new Subject(type, id); }

    private record Slot(Short value) implements AdmissionSlotRow {
        @Override public Short getUserSlot() { return value; }
    }
    private record Presence(Integer value) implements AdmissionPresenceRow {
        @Override public Integer getBannedFlag() { return value; }
    }
    private record Subject(VillageSubjectType type, Long id) implements AdmissionSubject {
        @Override public VillageSubjectType getSubjectType() { return type; }
        @Override public Long getSubjectId() { return id; }
    }
}
