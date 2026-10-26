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
 * 差し戻し（{@code COMPLETION_REPORTED → MATCHED}）を扱っていた。</p>
 *
 * <p>Phase 13.1.2 で QR チェックイン／アウトに伴う以下の遷移を追加した（設計書 §5.4）:</p>
 * <ul>
 *   <li>{@code MATCHED → CHECKED_IN}（IN-QR スキャン）</li>
 *   <li>{@code CHECKED_IN → IN_PROGRESS}（チェックイン成立後の自動遷移）</li>
 *   <li>{@code IN_PROGRESS → CHECKED_OUT}（OUT-QR スキャン）</li>
 *   <li>{@code CHECKED_OUT → COMPLETION_REPORTED}（完了報告送信）</li>
 *   <li>{@code COMPLETION_REPORTED → IN_PROGRESS}（差し戻し・再チェックイン不要）</li>
 *   <li>各中間状態からの {@code CANCELLED}（紛争化／合意キャンセル）</li>
 * </ul>
 *
 * <p>さらに後続 Phase（13.1.4）の大規模募集 ORG_CONFIRM 方式向けに
 * {@code MATCHED → TIME_CONFIRMED} と {@code TIME_CONFIRMED → COMPLETION_REPORTED} の
 * 遷移も先行定義しておく。</p>
 *
 * <p>差し戻しの遷移先（{@code MATCHED} or {@code IN_PROGRESS}）は**両方許可**し、
 * 呼び出し側（{@link com.mannschaft.app.jobmatching.service.JobContractService 等}）で
 * 分岐判断する責務分担とする（論点F 既決）。</p>
 *
 * <p>{@code AUTHORIZED} / {@code CAPTURED} / {@code PAID} / {@code DISPUTED} は
 * Phase 13.1.3 以降で追加する際に本表へ追記すればよい。</p>
 */
@Component
public class JobContractStateMachine {

    /**
     * 許可される契約状態遷移表。Phase 13.1.2 時点。
     *
     * <ul>
     *   <li>{@code MATCHED} → {@code CHECKED_IN}, {@code TIME_CONFIRMED},
     *       {@code COMPLETION_REPORTED}, {@code CANCELLED}</li>
     *   <li>{@code CHECKED_IN} → {@code IN_PROGRESS}, {@code CANCELLED}</li>
     *   <li>{@code IN_PROGRESS} → {@code CHECKED_OUT}, {@code CANCELLED}</li>
     *   <li>{@code CHECKED_OUT} → {@code COMPLETION_REPORTED}, {@code CANCELLED}</li>
     *   <li>{@code TIME_CONFIRMED} → {@code COMPLETION_REPORTED}（Phase 13.1.4 用先行定義）</li>
     *   <li>{@code COMPLETION_REPORTED} → {@code COMPLETED}, {@code MATCHED}, {@code IN_PROGRESS}, {@code CANCELLED}</li>
     *   <li>{@code COMPLETED} → 終着（遷移不可）</li>
     *   <li>{@code CANCELLED} → 終着（遷移不可）</li>
     * </ul>
     */
    private static final Map<JobContractStatus, Set<JobContractStatus>> ALLOWED_TRANSITIONS;

    static {
        Map<JobContractStatus, Set<JobContractStatus>> map = new EnumMap<>(JobContractStatus.class);
        map.put(JobContractStatus.MATCHED, EnumSet.of(
                JobContractStatus.CHECKED_IN,
                JobContractStatus.TIME_CONFIRMED,
                JobContractStatus.COMPLETION_REPORTED,
                JobContractStatus.CANCELLED
        ));
        map.put(JobContractStatus.CHECKED_IN, EnumSet.of(
                JobContractStatus.IN_PROGRESS,
                JobContractStatus.CANCELLED
        ));
        map.put(JobContractStatus.IN_PROGRESS, EnumSet.of(
                JobContractStatus.CHECKED_OUT,
                JobContractStatus.CANCELLED
        ));
        map.put(JobContractStatus.CHECKED_OUT, EnumSet.of(
                JobContractStatus.COMPLETION_REPORTED,
                JobContractStatus.CANCELLED
        ));
        map.put(JobContractStatus.TIME_CONFIRMED, EnumSet.of(
                JobContractStatus.COMPLETION_REPORTED
        ));
        map.put(JobContractStatus.COMPLETION_REPORTED, EnumSet.of(
                JobContractStatus.COMPLETED,
                JobContractStatus.MATCHED,
                JobContractStatus.IN_PROGRESS,
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
