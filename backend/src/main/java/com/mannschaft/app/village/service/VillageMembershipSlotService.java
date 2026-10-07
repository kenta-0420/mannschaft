package com.mannschaft.app.village.service;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 五つの入村入口のUSER枠を、入口のREQUIREDトランザクション内だけで割り当てる。
 * USER行を先に排他ロックし、RRの過去snapshotやmanaged entityを使わずcurrent scalarを読む。
 * 枠・申請・村・招待使用回数の確定を別トランザクションへ分離しない。
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class VillageMembershipSlotService {
    private final UserRowLockService userRowLockService;
    private final VillageMembershipRepository membershipRepository;

    /** USER根の取得のみ。非現役/不在の応答は各入口の既存の秘匿境界で選ぶ。 */
    public boolean lockUser(Long userId) {
        return userId != null && userRowLockService.lock(userId) == UserRowLockService.UserState.ACTIVE;
    }

    /** 現在の同村在籍をscalarで確認し、BAN/既所属の既存エラーを保つ。 */
    public void ensureNotCurrentMember(UUID villageId, VillageSubjectType subjectType, Long subjectId) {
        var rows = membershipRepository.findAdmissionPresenceForUpdate(villageId, subjectType.name(), subjectId);
        if (!rows.isEmpty()) {
            Integer banned = rows.getFirst().getBannedFlag();
            if (banned == null || rows.size() != 1) {
                throw new IllegalStateException("村在籍のcurrent projectionが不正です");
            }
            throw new BusinessException(banned != 0 ? VillageErrorCode.MEMBER_BANNED : VillageErrorCode.ALREADY_MEMBER);
        }
    }

    /**
     * USER根取得後のcurrent read。BANも枠を占有し、退村履歴は除外する。
     * count+1ではなく1..100の最小空きを返す。DB不変条件の破損を上限エラーへ隠さない。
     */
    public Allocation allocate(Long userId) {
        boolean[] occupied = new boolean[VillageMembershipService.PARTICIPATION_HARD_LIMIT + 1];
        var rows = membershipRepository.findAdmissionSlotsForUpdate(userId);
        for (var row : rows) {
            Short slot = row.getUserSlot();
            if (slot == null || slot < 1 || slot > VillageMembershipService.PARTICIPATION_HARD_LIMIT || occupied[slot]) {
                throw new IllegalStateException("村USER枠のcurrent projectionが不正です");
            }
            occupied[slot] = true;
        }
        for (short slot = 1; slot <= VillageMembershipService.PARTICIPATION_HARD_LIMIT; slot++) {
            if (!occupied[slot]) {
                return new Allocation(slot, rows.size() + 1);
            }
        }
        throw new BusinessException(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED);
    }

    /** 保存予定の枠と保存後の所在籍数。警告判定も同じcurrent readを使う。 */
    public record Allocation(Short slot, int joinedCount) { }
}
