package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.dto.RanchState;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.repository.RanchCareWeekBudgetRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import com.mannschaft.app.ranch.repository.RanchRoomPlacementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Objects;

/** Auth guard callbackから順次呼ぶPRIMARY SELECT専用の本人state reader。 */
@Service
@RequiredArgsConstructor
public class RanchStateReader {
    private final RanchOwnerRepository owners;
    private final RanchDinosaurRepository dinosaurs;
    private final RanchRoomPlacementRepository slots;
    private final RanchCareWeekBudgetRepository careBudgets;
    private final RanchRuleProvider rules;
    private final RanchStateAssembler states;

    // readOnly=false はReplicaRoutingAspectのPRIMARY経路選択用。DMLは行わない。
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public RanchState read(Long userId, Instant serverTime,
                           RanchStateAssembler.ExternalProjection external) {
        Objects.requireNonNull(userId, "本人IDは必須です");
        Objects.requireNonNull(serverTime, "時刻は必須です");
        Objects.requireNonNull(external, "認可済み投影は必須です");
        RanchOwnerEntity owner = owners.findByUserId(userId).orElse(null);
        if (owner == null) {
            return states.assemble(userId, serverTime, external, null, null,
                    List.of(), null, rules.currentCareRule(serverTime));
        }
        LocalDate monday = serverTime.atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return states.assemble(userId, serverTime, external, owner,
                dinosaurs.findByUserId(userId).orElse(null),
                slots.findByUserIdOrderBySlotKey(userId),
                careBudgets.findByUserIdAndWeekStartsOn(userId, monday).orElse(null),
                rules.currentCareRule(serverTime));
    }
}
