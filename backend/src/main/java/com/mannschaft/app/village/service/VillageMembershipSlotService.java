package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageUserAdmissionLockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 五つの入村入口のUSER枠を、入口のREQUIREDトランザクション内だけで割り当てる。
 * 村ドメインのUSER単位ロック行（village_user_admission_locks）を先に排他ロックし、
 * RRの過去snapshotやmanaged entityを使わずcurrent scalarを読む。
 * 枠・申請・村・招待使用回数の確定を別トランザクションへ分離しない。
 *
 * <p>不変条件の正本はDB制約（uk_vm_user_active_slot / uk_vm_active_subject / ck_vm_active_user_slot）で、
 * 違反は {@link #admit} で業務コードへ写像する。ロックは並行入村での枠番号の衝突と、
 * 所属0件のUSERどうしのギャップロックによるデッドロックを避けるためのもの。
 * auth の users 行は使わない（村の取引がドメインを越境しないため。D-3T）。</p>
 *
 * <p>五入口の取引は READ_COMMITTED とする（既存の VillageCharterService / VillageMeetupService と同じ作法）。
 * REPEATABLE READ のロック読みは該当行が無いとギャップロックを取り、所属0件の別USERどうしが同じ隙間へ
 * 挿入してデッドロックするため。同じUSERどうしはUSER単位ロック行で直列化され、READ_COMMITTED の読みは
 * ロック取得後の確定値を返す。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class VillageMembershipSlotService {
    private final VillageUserAdmissionLockRepository admissionLockRepository;
    private final VillageMembershipRepository membershipRepository;

    /**
     * USER単位の入村ロックを取り、呼出側の取引の終わりまで保持する。
     *
     * <p>USER の実在・状態の確認はしない（入口の認証・認可と、申請・招待の対象者照合が担う）。</p>
     *
     * @return userId が null のとき false
     */
    public boolean lockUser(Long userId) {
        if (userId == null) {
            return false;
        }
        admissionLockRepository.lockForAdmission(UuidV7.generate().toString(), userId);
        return true;
    }

    /** 現在の同村在籍とBAN履歴をscalarで確認し、BAN/既所属のエラーを返す。BANは退村扱いの行も含む。 */
    public void ensureNotCurrentMember(UUID villageId, VillageSubjectType subjectType, Long subjectId) {
        var rows = membershipRepository.findAdmissionPresenceForUpdate(villageId, subjectType.name(), subjectId);
        if (rows.isEmpty()) {
            return;
        }
        boolean banned = false;
        for (var row : rows) {
            Integer flag = row.getBannedFlag();
            if (flag == null) {
                throw new IllegalStateException("村在籍のcurrent projectionが不正です");
            }
            banned |= flag != 0;
        }
        // BAN（退村扱いの行を含む）は再参加を拒否する。BANが無ければ残る行は現役在籍。
        throw new BusinessException(banned ? VillageErrorCode.MEMBER_BANNED : VillageErrorCode.ALREADY_MEMBER);
    }

    /**
     * USER単位ロック取得後のcurrent read。BANも枠を占有し、退村履歴は除外する。
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

    /**
     * 入村の所属行を保存し、その場でflushしてDB制約違反を業務エラーへ写像する。
     *
     * <p>アプリのロックをすり抜けた並行入村（TEAM/ORGなどUSER単位ロックを持たない主体を含む）でも、
     * 現役一意・USER枠のDB制約違反を500にせず、既存の業務コードで返す。
     * 例外は呼出側TXをrollbackさせるため、招待の使用回数や申請の状態も巻き戻る。</p>
     *
     * <p>Entity を受け渡すため package-private とし、同じ village.service の五入口からだけ呼ぶ（D-1）。</p>
     */
    VillageMembershipEntity admit(VillageMembershipEntity membership) {
        try {
            VillageMembershipEntity saved = membershipRepository.save(membership);
            membershipRepository.flush();
            return saved;
        } catch (DataIntegrityViolationException violation) {
            String detail = constraintDetail(violation);
            if (detail.contains("uk_vm_active_subject") || detail.contains("uk_vm_village_subject")) {
                throw new BusinessException(VillageErrorCode.ALREADY_MEMBER, violation);
            }
            if (detail.contains("uk_vm_user_active_slot") || detail.contains("ck_vm_active_user_slot")) {
                throw new BusinessException(VillageErrorCode.PARTICIPATION_LIMIT_EXCEEDED, violation);
            }
            throw violation;
        }
    }

    private static String constraintDetail(Throwable error) {
        StringBuilder detail = new StringBuilder();
        for (Throwable cause = error; cause != null && detail.length() < 4096; cause = cause.getCause()) {
            if (cause.getMessage() != null) {
                detail.append(cause.getMessage()).append(' ');
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return detail.toString();
    }

    /** 保存予定の枠と保存後の所在籍数。警告判定も同じcurrent readを使う。 */
    public record Allocation(Short slot, int joinedCount) { }
}
