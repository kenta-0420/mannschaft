package com.mannschaft.app.shift.service;

import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.dto.AvailabilityDefaultResponse;
import com.mannschaft.app.shift.dto.BulkAvailabilityDefaultRequest;
import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import com.mannschaft.app.shift.repository.MemberAvailabilityDefaultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * シフト勤務可能時間サービス。メンバーのデフォルト勤務可能時間の管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftAvailabilityService {

    private final MemberAvailabilityDefaultRepository availabilityRepository;
    private final ShiftMapper shiftMapper;

    /**
     * デフォルト勤務可能時間を取得する。
     *
     * @param userId ユーザーID
     * @param teamId チームID
     * @return デフォルト勤務可能時間一覧
     */
    public List<AvailabilityDefaultResponse> getAvailabilityDefaults(Long userId, Long teamId) {
        List<MemberAvailabilityDefaultEntity> entities = availabilityRepository
                .findByUserIdAndTeamIdOrderByDayOfWeekAscStartTimeAsc(userId, teamId);
        return shiftMapper.toAvailabilityResponseList(entities);
    }

    /**
     * デフォルト勤務可能時間を一括設定する（既存データを全削除して再作成）。
     *
     * @param userId ユーザーID
     * @param teamId チームID
     * @param req    一括設定リクエスト
     * @return 設定された勤務可能時間一覧
     */
    @Transactional
    public List<AvailabilityDefaultResponse> setAvailabilityDefaults(Long userId, Long teamId,
                                                                     BulkAvailabilityDefaultRequest req) {
        // 既存データを全削除
        availabilityRepository.deleteByUserIdAndTeamId(userId, teamId);

        // 新規作成
        List<MemberAvailabilityDefaultEntity> entities = req.getAvailabilities().stream()
                .map(avail -> MemberAvailabilityDefaultEntity.builder()
                        .userId(userId)
                        .teamId(teamId)
                        .dayOfWeek(avail.getDayOfWeek())
                        .startTime(avail.getStartTime())
                        .endTime(avail.getEndTime())
                        .preference(ShiftPreference.valueOf(avail.getPreference()))
                        .note(avail.getNote())
                        .build())
                .toList();

        entities = availabilityRepository.saveAll(entities);
        log.info("デフォルト勤務可能時間設定: userId={}, teamId={}, count={}", userId, teamId, entities.size());
        return shiftMapper.toAvailabilityResponseList(entities);
    }

    /**
     * デフォルト勤務可能時間を削除する。
     *
     * @param userId ユーザーID
     * @param teamId チームID
     */
    @Transactional
    public void deleteAvailabilityDefaults(Long userId, Long teamId) {
        availabilityRepository.deleteByUserIdAndTeamId(userId, teamId);
        log.info("デフォルト勤務可能時間削除: userId={}, teamId={}", userId, teamId);
    }
}
