package com.mannschaft.app.common.architecture.fixtures.village;

import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;
import java.util.Optional;
import java.util.UUID;

/**
 * CMP-260826-1455 村モデレーター述語番人の<b>陰性</b>見本（検出してはならない）。
 * 本番からは呼ばれない。
 */
@SuppressWarnings("unused")
public class VillageGuardLegitMembers {

    private VillageMembershipRepository repo;

    /** BAN フィルタ付きの会員判定。 */
    boolean isUserMember(UUID v, Long u) {
        return repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u)
                .filter(m -> m.getBannedAt() == null)
                .isPresent();
    }

    /** 参加時の BAN 判別（BAN 済みも含めて在籍状態を知る正当な用途）。 */
    void join(UUID v, Long u) {
        Optional<VillageMembershipEntity> existing =
                repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u);
        if (existing.isPresent() && existing.get().getBannedAt() != null) {
            throw new IllegalStateException("MEMBER_BANNED");
        }
    }

    /** 申請時の BAN 判別がラムダの中にある（合成メソッドを呼出し元へ帰属させる）。 */
    void createRequest(UUID v, Long u) {
        repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u)
                .ifPresent(m -> {
                    if (m.getBannedAt() != null) {
                        throw new IllegalStateException("MEMBER_BANNED");
                    }
                    throw new IllegalStateException("ALREADY_MEMBER");
                });
    }

    /** モデレーター判定が正準述語だけを使う。 */
    VillageMembershipEntity requireHeadmanOrElderCanonical(UUID v, Long u) {
        VillageMembershipEntity m = repo.findActiveByVillageIdAndSubject(v, VillageSubjectType.USER, u)
                .orElseThrow();
        if (m.getRole() != VillageRole.HEADMAN && m.getRole() != VillageRole.ELDER) {
            throw new IllegalStateException();
        }
        return m;
    }

    /** 正準述語の実体（...AndBannedAtIsNull）を直接呼ぶモデレーター判定。 */
    boolean isModeratorByFullName(UUID v, Long u) {
        return repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNullAndBannedAtIsNull(
                        v, VillageSubjectType.USER, u)
                .filter(m -> m.getRole() == VillageRole.HEADMAN)
                .isPresent();
    }

    /**
     * コメント・文字列にだけ生述語名とロール名がある（AC-1455-3）。
     * findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull / HEADMAN / ELDER
     */
    VillageMembershipEntity requireModeratorWithDecoyText(UUID v, Long u) {
        // repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, USER, u)
        String decoy = "findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull HEADMAN ELDER";
        return repo.findActiveByVillageIdAndSubject(v, VillageSubjectType.USER, u).orElseThrow();
    }

    /** 型名に Moderator を含む抽象型。 */
    abstract static class ModeratorAdapter {
        abstract boolean isUserMemberInAdapter(UUID v, Long u);
    }

    @Target(ElementType.TYPE_USE)
    @interface A {
        int value() default 0;
    }

    /** 型注釈付き・型名に Moderator を含む匿名クラスの中の正当な会員判定。 */
    void scheduleWithAnnotatedAnonymous(UUID v, Long u) {
        ModeratorAdapter a = new @A(1) ModeratorAdapter() {
            @Override
            boolean isUserMemberInAdapter(UUID v2, Long u2) {
                return repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v2, VillageSubjectType.USER, u2)
                        .filter(m -> m.getBannedAt() == null)
                        .isPresent();
            }
        };
        a.isUserMemberInAdapter(v, u);
    }

    /** enum 定数の本体（定数名に Moderator を含む）の中の正当な会員判定。 */
    enum Kind {
        ModeratorAdapterConstant {
            @Override
            boolean isUserMemberInEnumBody(VillageMembershipRepository r, UUID v, Long u) {
                return r.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u)
                        .filter(m -> m.getBannedAt() == null)
                        .isPresent();
            }
        };

        abstract boolean isUserMemberInEnumBody(VillageMembershipRepository r, UUID v, Long u);
    }
}
