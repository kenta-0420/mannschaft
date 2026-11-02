package com.mannschaft.app.village.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;

import java.util.Objects;

/**
 * 村の主体（USER / TEAM / ORGANIZATION）として振る舞う権限の判定結果。
 *
 * <p>{@link VillageSubjectAuthorizationGuard} が取引の外で判定し、村の取引へは判定結果だけを渡す。
 * 取引の中から role ドメインの判定を呼ぶと取引がドメインを越境する（D-3T、CLAUDE.md DB 設計の原則 #5）ため。
 * 生成は同じパッケージの判定役に限り（コンストラクタは package-private）、取引の中では
 * {@link #require} で「判定した主体と実際の主体が一致し、許可されている」ことを確かめる。</p>
 *
 * <p>拒否の例外は従来と同じ位置（村の可視性・参加方式の検査の後）で取引の中から投げる。
 * 判定役で先に投げると、見えない村に対して 404 ではなく 403 を返し、村の実在を明かしてしまうため。</p>
 */
public final class VillageSubjectAuthorization {

    private final Long actorUserId;
    private final VillageSubjectType subjectType;
    private final Long subjectId;
    private final boolean permitted;
    /** 操作者が SYSTEM_ADMIN か（非公開村の可視性判定に使う。取引の外で判定済み）。 */
    private final boolean actorIsSystemAdmin;

    VillageSubjectAuthorization(Long actorUserId, VillageSubjectType subjectType, Long subjectId, boolean permitted) {
        this(actorUserId, subjectType, subjectId, permitted, false);
    }

    VillageSubjectAuthorization(Long actorUserId, VillageSubjectType subjectType, Long subjectId, boolean permitted,
                                boolean actorIsSystemAdmin) {
        this.actorUserId = actorUserId;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.permitted = permitted;
        this.actorIsSystemAdmin = actorIsSystemAdmin;
    }

    /** 対象の主体を特定できなかった（所属が無い等）ときの判定。常に拒否する。 */
    static VillageSubjectAuthorization unresolved(Long actorUserId, boolean actorIsSystemAdmin) {
        return new VillageSubjectAuthorization(actorUserId, null, null, false, actorIsSystemAdmin);
    }

    /**
     * 村の可視性判定に使う SYSTEM_ADMIN 判定。判定が無い・操作者が違うときは false（秘匿側へ倒す）。
     */
    static boolean actorIsSystemAdmin(VillageSubjectAuthorization authorization, Long actorUserId) {
        return authorization != null && authorization.actorIsSystemAdmin
                && Objects.equals(authorization.actorUserId, actorUserId);
    }

    /**
     * 取引の中で、判定済みの主体と実際の主体が一致し、許可されていることを確かめる。
     *
     * @throws BusinessException 判定が無い・主体が一致しない・許可されていない（{@link VillageErrorCode#REPRESENT_FORBIDDEN} / 403）
     */
    static void require(VillageSubjectAuthorization authorization,
                        Long actorUserId, VillageSubjectType subjectType, Long subjectId) {
        if (authorization == null
                || !authorization.permitted
                || !Objects.equals(authorization.actorUserId, actorUserId)
                || authorization.subjectType != subjectType
                || !Objects.equals(authorization.subjectId, subjectId)) {
            throw new BusinessException(VillageErrorCode.REPRESENT_FORBIDDEN);
        }
    }
}
