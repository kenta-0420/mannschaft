package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.dto.RanchState;
import com.mannschaft.app.ranch.dto.RoomSlotSummary;
import com.mannschaft.app.ranch.entity.RanchCollectibleCatalogEntity;
import com.mannschaft.app.ranch.entity.RanchInventoryEntity;
import com.mannschaft.app.ranch.entity.RanchOwnerEntity;
import com.mannschaft.app.ranch.repository.RanchCareWeekBudgetRepository;
import com.mannschaft.app.ranch.repository.RanchCollectibleCatalogRepository;
import com.mannschaft.app.ranch.repository.RanchDinosaurRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Auth guard callbackから順次呼ぶPRIMARY SELECT専用の本人state reader。 */
@Service
@RequiredArgsConstructor
public class RanchStateReader {
    private final RanchOwnerRepository owners;
    private final RanchDinosaurRepository dinosaurs;
    private final RanchRoomPlacementRepository slots;
    private final RanchInventoryRepository inventory;
    private final RanchCollectibleCatalogRepository catalog;
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
        var placements = slots.findByUserIdOrderBySlotKey(userId);
        return states.assemble(userId, serverTime, external, owner,
                dinosaurs.findByUserId(userId).orElse(null),
                placements,
                careBudgets.findByUserIdAndWeekStartsOn(userId, monday).orElse(null),
                rules.currentCareRule(serverTime), decorations(userId, owner, placements));
    }

    private Map<UUID, RoomSlotSummary.Decoration> decorations(Long userId,
            RanchOwnerEntity owner,
            List<com.mannschaft.app.ranch.entity.RanchRoomPlacementEntity> placements) {
        List<UUID> ids = placements.stream().map(slot -> slot.getInventoryId())
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        List<RanchInventoryEntity> owned = inventory.findByUserIdAndIdInAndRevokedFalse(userId, ids)
                .stream().filter(item -> owner.getId().equals(item.getOwnerId())).toList();
        if (owned.isEmpty()) return Map.of();
        Map<String, RanchCollectibleCatalogEntity> approved = catalog
                .findByCollectibleKeyInAndActiveTrue(owned.stream()
                        .map(RanchInventoryEntity::getCollectibleKey).distinct().toList())
                .stream().collect(Collectors.toMap(RanchCollectibleCatalogEntity::getCollectibleKey,
                        Function.identity()));
        return owned.stream().filter(item -> approved.containsKey(item.getCollectibleKey()))
                .collect(Collectors.toMap(RanchInventoryEntity::getId, item -> {
                    var entry = approved.get(item.getCollectibleKey());
                    return new RoomSlotSummary.Decoration(entry.getCollectibleKey(),
                            entry.getLabelKey(), entry.getAssetKey());
                }));
    }
}
