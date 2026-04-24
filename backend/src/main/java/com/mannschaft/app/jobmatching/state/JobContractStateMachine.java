package com.mannschaft.app.jobmatching.state;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.enums.JobContractStatus;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 求人契約（{@link JobContractStatus}）の状態遷移を司るステートマシン。
 *
 * <p>Phase 13.1.1 MVP では {@code MATCHED → COMPLETION_REPORTED → COMPLETED} の
 * 直線フローと、いずれのポイントからの {@code CANCELLED}、完了報告の
 * 差し戻し（{@code COMPLETION_REPORTED → MATCHED}）を扱う。</p>
 *
 * <p>Phase 13.1.2 以降で {@code AUTHORIZED} / {@code CAPTURED} / {@code PAID} /
 * {@code CHECKED_IN} / {@code CHECKED_OUT} / {@code DISPUTED} 等が追加される際は、
 * {@link #ALLOWED_TRANSITIONS} にエントリを追記するだけで済むよう、
 * {@code EnumMap}＋{@code EnumSet} による疎結合な表現を採用した。</p>
 */
@Component
public class JobContractStateMachine {

    /**
     * 許可される契約状態遷移表。
     *
     * <ul>
     *   <li>{@code MATCHED} → {@code COMPLETION_REPORTED}, {@code CANCELLED}</li>
     *   <li>{@code COMPLETION_REPORTED} → {@code COMPLETED}, {@code MATCHED}（差し戻し）, {@code CANCELLED}</li>
     *   <li>{@code COMPLETED} → 終着（遷移不可）</li>
     *   <li>{@code CANCELLED} → 終着（遷移不可）</li>
     * </ul>
     */
    private static final Map<JobContractStatus, Set<JobContractStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<JobContractStatus, Set<JobContractStatus>> map = new EnumMap<>(JobContractStatus.class);
        map.put(JobContractStatus.MATCHED, EnumSet.of(
                JobContractStatus.COMPLETION_REPORTED,
                JobContractStatus.CANCELLED
        ));
        map.put(JobContractStatus.COMPLETION_REPORTED, EnumSet.of(
                JobContractStatus.COMPLETED,
                JobContractStatus.MATCHED,
                JobContractStatus.CANCELLED
        ));
        map.put(JobContractStatus.COMPLETED, EnumSet.noneOf(JobContractStatus.class));
        map.put(JobContractStatus.CANCELLED, EnumSet.noneOf(JobContractStatus.class));
        ALLOWED_TRANSITIONS = map;
    }

    /**
     * 指定遷移が許容されない場合 {@link BusinessException} を送出する。
     *
     * @param from 現在のステータス
     * @param to   遷移先ステータス
     * @throws BusinessException 不正な遷移のとき（{@link JobmatchingErrorCode#JOB_INVALID_STATE_TRANSITION}）
     */
    public void validate(JobContractStatus from, JobContractStatus to) {
        if (!isValidTransition(from, to)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }
    }

    /**
     * 遷移が許容されるかを判定する。
     *
     * @param from 現在のステータス
     * @param to   遷移先ステータス
     * @return 許容される場合 true
     */
    public boolean isValidTransition(JobContractStatus from, JobContractStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<JobContractStatus> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
