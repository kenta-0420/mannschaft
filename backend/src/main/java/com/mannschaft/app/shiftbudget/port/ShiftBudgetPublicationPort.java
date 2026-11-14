package com.mannschaft.app.shiftbudget.port;

import java.util.Optional;

/** 消化記録と公開状態の変更を、呼出元の同一トランザクションで直列化する境界。 */
public interface ShiftBudgetPublicationPort {

    /** 親を更新ロックし、コミットまで保持する。削除済み・不在の親は空を返す。 */
    Optional<PublicationState> lockPublication(Long scheduleId);

    /** 消化判定に必要な公開状態だけを渡す。 */
    record PublicationState(long publicationGeneration, boolean published) {
        /** 現在の公開に対応するイベントかを判定する。 */
        public boolean accepts(long generation) {
            return published && publicationGeneration == generation;
        }
    }
}
