package com.mannschaft.app.timetable.personal.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timetable.TimetableStatus;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import com.mannschaft.app.timetable.entity.TimetableSlotEntity;
import com.mannschaft.app.timetable.personal.PersonalTimetableErrorCode;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSlotEntity;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableRepository;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSlotRepository;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F03.15 Phase 4: 個人時間割コマのチームリンク登録/解除サービス。
 *
 * <p>設計書 §6.4 に従い、リンク先チームの MEMBER 以上であることを検証する（403）。
 * リンク先 timetable は ACTIVE のみ受け付ける（409）。
 * linked_slot_id 指定時、曜日×時限がリンク先と一致することも検証する（409）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalTimetableSlotLinkService {

    private final PersonalTimetableRepository personalTimetableRepository;
    private final PersonalTimetableSlotRepository personalSlotRepository;
    private final TimetableRepository timetableRepository;
    private final TimetableSlotRepository timetableSlotRepository;
    private final UserRoleRepository userRoleRepository;

    /**
     * チームリンクを登録/更新する。
     */
    @Transactional
    public PersonalTimetableSlotEntity link(
            Long personalTimetableId,
            Long slotId,
            Long currentUserId,
            Long linkedTeamId,
            Long linkedTimetableId,
            Long linkedSlotId,
            Boolean autoSyncChanges) {

        PersonalTimetableSlotEntity slot = ensureOwnedSlot(personalTimetableId, slotId, currentUserId);

        if (linkedTeamId == null || linkedTimetableId == null) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_TIMETABLE_REQUIRED);
        }

        // チーム MEMBER 以上であることを検証（user_roles に該当 team_id の行が1件以上）
        if (!userRoleRepository.existsByUserIdAndTeamId(currentUserId, linkedTeamId)) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_NOT_TEAM_MEMBER);
        }

        // 時間割の存在＆チーム所属＆ ACTIVE 検証
        TimetableEntity timetable = timetableRepository.findById(linkedTimetableId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_TARGET_TIMETABLE_NOT_FOUND));
        if (timetable.getTeamId() == null || !timetable.getTeamId().equals(linkedTeamId)) {
            throw new BusinessException(
                    PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_TARGET_TIMETABLE_NOT_FOUND);
        }
        if (timetable.getStatus() != TimetableStatus.ACTIVE) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_STATUS_INVALID);
        }

        // linked_slot_id 指定時の整合性検証
        if (linkedSlotId != null) {
            TimetableSlotEntity targetSlot = timetableSlotRepository.findById(linkedSlotId)
                    .orElseThrow(() -> new BusinessException(
                            PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_TARGET_SLOT_NOT_FOUND));
            if (!targetSlot.getTimetableId().equals(linkedTimetableId)) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_TARGET_SLOT_NOT_FOUND);
            }
            if (!targetSlot.getDayOfWeek().equals(slot.getDayOfWeek())
                    || !targetSlot.getPeriodNumber().equals(slot.getPeriodNumber())) {
                throw new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_SLOT_LINK_POSITION_MISMATCH);
            }
        }

        slot.linkTo(linkedTeamId, linkedTimetableId, linkedSlotId, autoSyncChanges);
        PersonalTimetableSlotEntity saved = personalSlotRepository.save(slot);

        log.info("個人コマにチームリンクを設定: pid={}, slotId={}, teamId={}, ttId={}, slotIdLinked={}",
                personalTimetableId, slotId, linkedTeamId, linkedTimetableId, linkedSlotId);
        return saved;
    }

    /**
     * チームリンクを解除する。
     */
    @Transactional
    public void unlink(Long personalTimetableId, Long slotId, Long currentUserId) {
        PersonalTimetableSlotEntity slot = ensureOwnedSlot(personalTimetableId, slotId, currentUserId);
        slot.unlink();
        personalSlotRepository.save(slot);
        log.info("個人コマのチームリンクを解除: pid={}, slotId={}", personalTimetableId, slotId);
    }

    private PersonalTimetableSlotEntity ensureOwnedSlot(
            Long personalTimetableId, Long slotId, Long currentUserId) {
        PersonalTimetableEntity timetable = personalTimetableRepository
                .findByIdAndUserIdAndDeletedAtIsNull(personalTimetableId, currentUserId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND));

        PersonalTimetableSlotEntity slot = personalSlotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(
                        PersonalTimetableErrorCode.PERSONAL_SLOT_NOT_FOUND));
        if (!slot.getPersonalTimetableId().equals(timetable.getId())) {
            throw new BusinessException(PersonalTimetableErrorCode.PERSONAL_SLOT_NOT_FOUND);
        }
        return slot;
    }
}
