package com.mannschaft.app.jobmatching.state;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.jobmatching.enums.JobPostingStatus;
import com.mannschaft.app.jobmatching.exception.JobmatchingErrorCode;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 求人投稿（{@link JobPostingStatus}）の状態遷移を司るステートマシン。
 *
 * <p>遷移表（F13.1 §5.4）:</p>
 * <ul>
 *   <li>{@code DRAFT} → {@code OPEN}（公開）, {@code CANCELLED}（破棄）</li>
 *   <li>{@code OPEN} → {@code CLOSED}（定員充足・締切通過）, {@code CANCELLED}</li>
 *   <li>{@code CLOSED} → 終着</li>
 *   <li>{@code CANCELLED} → 終着</li>
 * </ul>
 */
@Component
public class JobPostingStateMachine {

    private static final Map<JobPostingStatus, Set<JobPostingStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<JobPostingStatus, Set<JobPostingStatus>> map = new EnumMap<>(JobPostingStatus.class);
        map.put(JobPostingStatus.DRAFT, EnumSet.of(
                JobPostingStatus.OPEN,
                JobPostingStatus.CANCELLED
        ));
        map.put(JobPostingStatus.OPEN, EnumSet.of(
                JobPostingStatus.CLOSED,
                JobPostingStatus.CANCELLED
        ));
        map.put(JobPostingStatus.CLOSED, EnumSet.noneOf(JobPostingStatus.class));
        map.put(JobPostingStatus.CANCELLED, EnumSet.noneOf(JobPostingStatus.class));
        ALLOWED_TRANSITIONS = map;
    }

    /**
     * 指定遷移が許容されない場合 {@link BusinessException} を送出する。
     *
     * @param from 現在のステータス
     * @param to   遷移先ステータス
     * @throws BusinessException 不正な遷移のとき（{@link JobmatchingErrorCode#JOB_INVALID_STATE_TRANSITION}）
     */
    public void validate(JobPostingStatus from, JobPostingStatus to) {
        if (!isValidTransition(from, to)) {
            throw new BusinessException(JobmatchingErrorCode.JOB_INVALID_STATE_TRANSITION);
        }
    }

    /**
     * 遷移が許容されるかを判定する。
     */
    public boolean isValidTransition(JobPostingStatus from, JobPostingStatus to) {
        if (from == null || to == null) {
            return false;
        }
        Set<JobPostingStatus> allowed = ALLOWED_TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }
}
