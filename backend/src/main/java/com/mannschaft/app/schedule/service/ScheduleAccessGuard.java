package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * schedule ドメインのうち「本人性」に基づく認可判定を一元化するガード。
 *
 * <p>個人スケジュールは<b>所有者本人</b>のみが閲覧・更新・削除でき、代理出席の承諾・辞退は
 * <b>その委任の代理人本人</b>のみが行える。本クラスはこれらの判定を 1 箇所に集約し、
 * 各サービスの public 入口から直接呼び出せる形で提供する。</p>
 *
 * <p>判定はすべて<b>取得済みの対象エンティティ</b>に対して行う。リクエストで渡された
 * 識別子は「どのエンティティを取得するか」を決めるだけであり、判定の根拠にはしない。
 * これにより、他人の識別子を差し込んで到達する経路を構造的に塞ぐ。</p>
 *
 * <p>スコープ（チーム／組織）に対するロール判定は本クラスの責務ではなく、
 * {@link ScheduleService#checkScopeViewAccess(Long, Long)} /
 * {@link ScheduleService#checkScopeAdminAccess(Long, Long)} が
 * {@link com.mannschaft.app.common.AccessControlService} の per-scope 判定で保証する。</p>
 *
 * <p>本クラスは状態を持たない（依存注入なし）。判定に必要な材料は引数で受け取る。</p>
 */
@Service
public class ScheduleAccessGuard {

    /**
     * 個人スケジュールの閲覧・更新・削除について、対象スケジュールが
     * <b>操作者本人の所有物である</b>ことを保証する。
     *
     * @param schedule 対象スケジュールエンティティ
     * @param userId   操作者ユーザー ID
     * @throws BusinessException 本人の所有物でない場合
     *                           （{@link ScheduleErrorCode#NOT_SCHEDULE_OWNER}）
     */
    public void requireScheduleOwner(ScheduleEntity schedule, Long userId) {
        if (!isScheduleOwnedBy(schedule, userId)) {
            throw new BusinessException(ScheduleErrorCode.NOT_SCHEDULE_OWNER);
        }
    }

    /**
     * 対象スケジュールが操作者本人の所有物かを返す。
     *
     * <p>一括削除のように「対象ごとに可否を判定し、不可のものを件数として集計する」
     * 経路のために真偽値版を提供する。一括操作でも判定は<b>1 件ずつ</b>行うこと。</p>
     *
     * @param schedule 対象スケジュールエンティティ（{@code null} 可）
     * @param userId   操作者ユーザー ID
     * @return 本人の所有物なら {@code true}
     */
    public boolean isScheduleOwnedBy(ScheduleEntity schedule, Long userId) {
        return schedule != null
                && userId != null
                && Objects.equals(schedule.getUserId(), userId);
    }

    /**
     * 代理出席の承諾・辞退について、対象の委任が<b>操作者本人あての委任である</b>ことを保証する。
     *
     * <p>判定は委任エンティティの {@code delegateId} で行い、状態遷移の可否
     * （PENDING かどうか）よりも先に評価する。順序を守ることで、あて先でない利用者が
     * 応答の違いから委任の状態を観測する経路を作らない。</p>
     *
     * @param delegation   対象の委任エンティティ
     * @param actingUserId 操作者ユーザー ID
     * @throws BusinessException 本人あての委任でない場合
     *                           （{@link ScheduleErrorCode#SCHEDULE_DELEGATION_NOT_DELEGATE}）
     */
    public void requireDelegationDelegate(ScheduleDelegationEntity delegation, Long actingUserId) {
        if (delegation == null
                || actingUserId == null
                || !Objects.equals(delegation.getDelegateId(), actingUserId)) {
            throw new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_DELEGATE);
        }
    }
}
