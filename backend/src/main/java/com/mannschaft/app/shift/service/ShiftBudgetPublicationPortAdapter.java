package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shiftbudget.port.ShiftBudgetPublicationPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 公開世代の参照を、呼出元が保持する親行ロックへ変換する。 */
@Component
@RequiredArgsConstructor
public class ShiftBudgetPublicationPortAdapter implements ShiftBudgetPublicationPort {

    private final ShiftScheduleRepository scheduleRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PublicationState> lockPublication(Long scheduleId) {
        return scheduleRepository.findByIdForUpdate(scheduleId)
                .map(schedule -> new PublicationState(schedule.getPublicationGeneration(),
                        schedule.getStatus() == ShiftScheduleStatus.PUBLISHED));
    }
}
