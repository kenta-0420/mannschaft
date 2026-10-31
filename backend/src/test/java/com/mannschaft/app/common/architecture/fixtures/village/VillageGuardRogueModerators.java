package com.mannschaft.app.common.architecture.fixtures.village;

import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.repository.VillageMembershipRepository;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * CMP-260826-1455 村モデレーター述語番人の<b>陽性</b>見本（検出されなければならない）。
 * 本番からは呼ばれない。各メソッド名は番人のテストで個別に照合するため一意にしている。
 */
@SuppressWarnings({"unused", "ResultOfMethodCallIgnored"})
public class VillageGuardRogueModerators {

    private VillageMembershipRepository repo;

    /** 名前でモデレーター判定と分かるメソッドが生述語を呼ぶ。 */
    VillageMembershipEntity requireModerator(UUID v, Long u) {
        return repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u)
                .orElseThrow();
    }

    /** 名前は中立だが HEADMAN/ELDER のロール比較を含む。 */
    void approveAll(UUID v, Long u) {
        VillageMembershipEntity actor = repo
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u)
                .orElseThrow();
        if (actor.getRole() != VillageRole.HEADMAN && actor.getRole() != VillageRole.ELDER) {
            throw new IllegalStateException();
        }
    }

    /** bannedAt フィルタを後付けしてもモデレーター判定では許さない。 */
    void ensureReviewer(UUID v, Long u) {
        repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u)
                .filter(m -> m.getBannedAt() == null)
                .filter(m -> m.getRole() == VillageRole.ELDER)
                .orElseThrow();
    }

    /** メソッド参照 repo::生述語。 */
    boolean isHeadman(UUID v, Long u) {
        MembershipFinder finder = repo::findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull;
        return finder.find(v, VillageSubjectType.USER, u).isPresent();
    }

    /** 生述語の呼び出しがラムダ（合成メソッド）の中にだけある。 */
    void requireHeadmanOrElder(UUID v, Long u) {
        Supplier<Optional<VillageMembershipEntity>> s =
                () -> repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u);
        s.get().orElseThrow();
    }

    /** 名前は中立で、ロール比較が enum の switch（$SwitchMap 経由）。 */
    boolean approveBySwitch(UUID v, Long u) {
        VillageMembershipEntity m = repo
                .findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u)
                .orElseThrow();
        switch (m.getRole()) {
            case VISITOR:
                return false;
            default:
                return true;
        }
    }

    /** モデレーター判定ではないが、BAN を見ずに生述語を使う（R2: BAN フィルタも許可ヘルパでもない）。 */
    Optional<VillageMembershipEntity> loadForDisplay(UUID v, Long u) {
        return repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u);
    }

    /** ネストした record のメソッド。 */
    record Inner(VillageMembershipRepository r) {
        boolean isModeratorInRecord(UUID v, Long u) {
            return r.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u)
                    .isPresent();
        }
    }

    /** フィールド初期化子の匿名クラスのメソッド。 */
    private final Object fieldAnonymous = new Object() {
        boolean isModeratorInFieldAnonymous(UUID v, Long u) {
            return repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u)
                    .isPresent();
        }
    };

    /** 中立メソッド内の匿名クラスのメソッド。 */
    void schedule(UUID v, Long u) {
        Runnable r = new Runnable() {
            @Override
            public void run() {
                requireElderInAnonymous(v, u);
            }

            void requireElderInAnonymous(UUID v2, Long u2) {
                repo.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v2, VillageSubjectType.USER, u2)
                        .orElseThrow();
            }
        };
        r.run();
    }

    /** enum 定数の本体のメソッド。 */
    enum Kind {
        ModeratorAdapter {
            @Override
            boolean check(VillageMembershipRepository r, UUID v, Long u) {
                return requireModeratorInEnumBody(r, v, u);
            }

            boolean requireModeratorInEnumBody(VillageMembershipRepository r, UUID v, Long u) {
                return r.findByVillageIdAndSubjectTypeAndSubjectIdAndLeftAtIsNull(v, VillageSubjectType.USER, u)
                        .isPresent();
            }
        };

        abstract boolean check(VillageMembershipRepository r, UUID v, Long u);
    }
}
