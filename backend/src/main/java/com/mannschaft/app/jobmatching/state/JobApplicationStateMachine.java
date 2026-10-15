package com.mannschaft.app.jobmatching.state;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.enums.JobApplicationStatus;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 求人応募（{@link JobApplicationStatus}）の状態遷移を司るステートマシン。
 *
 * <p>遷移表（F13.1 §5.4）:</p>
 * <ul>
 *   <li>{@code APPLIED} → {@code ACCEPTED}（採用）, {@code REJECTED}（不採用）, {@code WITHDRAWN}（取り下げ）</li>
 *   <li>{@code ACCEPTED} → 終着</li>
 *   <li>{@code REJECTED} → 終着</li>
 *   <li>{@code WITHDRAWN} → 終着</li>
 * </ul>
 */
@Component
public class JobApplicationStateMachine {

    private static final Map<JobApplicationStatus, Set<JobApplicationStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<JobApplicationStatus, Set<JobApplicationStatus>> map = new EnumMap<>(JobApplicationStatus.class);
        map.put(JobApplicationStatus.APPLIED, EnumSet.of(
                JobApplicationStatus.ACCEPTED,
                JobApplicationStatus.REJECTED,
                JobApplicationStatus.WITHDRAWN
        ));
        map.put(JobApplicationStatus.ACCEPTED, EnumSet.noneOf(JobApplicationStatus.class));
        map.put(JobApplicationStatus.REJECTED, EnumSet.noneOf(JobApplicationStatus.class));
        map.put(JobApplicationStatus.WITHDRAWN, EnumSet.noneOf(JobApplicationStatus.class));
        ALLOWED_TRANSITIONS = map;
    }

    /**
     * 指定遷移が許容されない場合 {@link BusinessException} を送出する。
     *
     * @param from 現在のステータス
     * @param to   遷移先ステータス
     * @throws BusinessException 不正な遷移のとき（{@link JobmatchingErrorCode#JOB_INVALID_STATE_TRANSITION}）
     */
    public void validate(JobApplicationStatus from, JobApplicationStatus to) {
        if (!isValidTransition(from, to)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }
    }

    /**
     * 遷移が許容されるかを判定する。
     */
    public boolean isValidTransition(JobApplicationStatus from, JobApplicationStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<JobApplicationStatus> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
