package com.mannschaft.app.timetable.personal.listener;

import com.mannschaft.app.role.event.MembershipChangedEvent;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F03.15 Phase 4: ユーザーがチームから脱退（または除名）された際に、
 * 自身の個人時間割コマに残っているそのチームへのリンクを自動解除するリスナー。
 *
 * <p>設計書 §5.4 を参照。コマ自体とメモ・添付ファイルは保持する（リンク列のみ NULL クリア）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PersonalTimetableSlotMembershipRevokeListener {

    private final PersonalTimetableSlotRepository personalSlotRepository;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onMembershipChanged(MembershipChangedEvent event) {
        if (event.changeType() != MembershipChangedEvent.ChangeType.REMOVED) {
            return;
        }
        if (!"TEAM".equalsIgnoreCase(event.scopeType())) {
            return;
        }

        try {
            List<PersonalTimetableSlotEntity> slots = personalSlotRepository
                    .findByLinkedTeamIdAndOwnerUserId(event.userId(), event.scopeId());
            for (PersonalTimetableSlotEntity s : slots) {
                s.unlink();
            }
            if (!slots.isEmpty()) {
                personalSlotRepository.saveAll(slots);
                log.info("チーム脱退に伴うリンク自動解除: userId={}, teamId={}, slotCount={}",
                        event.userId(), event.scopeId(), slots.size());
            }
        } catch (Exception ex) {
            log.error("PersonalTimetableSlotMembershipRevokeListener 失敗: userId={}, teamId={}, error={}",
                    event.userId(), event.scopeId(), ex.getMessage(), ex);
        }
    }
}
