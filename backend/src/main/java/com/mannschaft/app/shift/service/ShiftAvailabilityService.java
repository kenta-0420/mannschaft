package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.dto.AvailabilityDefaultResponse;
import com.mannschaft.app.shift.dto.BulkAvailabilityDefaultRequest;
import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import com.mannschaft.app.shift.repository.MemberAvailabilityDefaultRepository;
import com.mannschaft.app.team.repository.TeamRepository;
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
    /** クロスドメインFK禁止の原則に従い、teamId は Long で保持し TeamRepository 経由で実在確認する */
    private final TeamRepository teamRepository;
    private final AccessControlService accessControlService;

    /**
     * デフォルト勤務可能時間を取得する。
     *
     * @param userId ユーザーID
     * @param teamId チームID
     * @return デフォルト勤務可能時間一覧
     */
    public List<AvailabilityDefaultResponse> getAvailabilityDefaults(Long userId, Long teamId) {
        checkTeamAccess(userId, teamId);
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
        // 認可検証は delete より前に行う（拒否経路で既存行を破壊的に消さないため）
        checkTeamAccess(userId, teamId);

        // 既存データを全削除
        availabilityRepository.deleteByUserIdAndTeamId(userId, teamId);

        // 新規作成
        List<MemberAvailabilityDefaultEntity> entities = req.getAvailabilities().stream()
                .map(avail -> (MemberAvailabilityDefaultEntity) MemberAvailabilityDefaultEntity.builder()
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
        checkTeamAccess(userId, teamId);
        availabilityRepository.deleteByUserIdAndTeamId(userId, teamId);
        log.info("デフォルト勤務可能時間削除: userId={}, teamId={}", userId, teamId);
    }

    /**
     * 対象 teamId への per-scope 認可を検証する（AccessControlService をこのメソッドから直接呼ぶ。
     * 番人 {@code AuthzControllerGuardArchTest} の委譲探索は深さ2までのため）。
     *
     * <p>認可式: {@code isSystemAdmin || isAdminOrAbove || (isMember && !isSupporter)}</p>
     * <ul>
     *   <li>{@code isAdminOrAbove} との論理和が必要な理由: {@code AccessControlService#isMember} は
     *       memberships のみを見るため、{@code user_roles} にしか ADMIN 行を持たない利用者を落とす</li>
     *   <li>{@code isSystemAdmin} を別途先頭に置く理由: {@code isAdminOrAbove} が参照する
     *       {@code ADMIN_ROLES} は SYSTEM_ADMIN を含まない</li>
     *   <li>{@code !isSupporter} で明示的に除く理由: {@code isMember} は SUPPORTER 種別のメンバーも
     *       true を返すため</li>
     * </ul>
     *
     * <p>存在しない teamId は、SYSTEM_ADMIN であっても非メンバーと同一の 403 とする
     * （存在オラクルを作らない。実在確認をアプリ層で行う理由は
     * {@code member_availability_defaults.team_id} のクロスドメイン FK が既に削除済みで DB が止めないため）。</p>
     *
     * @throws BusinessException 認可拒否時（{@link CommonErrorCode#COMMON_002} / 403）
     */
    private void checkTeamAccess(Long userId, Long teamId) {
        if (!teamRepository.existsById(teamId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (accessControlService.isAdminOrAbove(userId, teamId, "TEAM")) {
            return;
        }
        if (!accessControlService.isMember(userId, teamId, "TEAM")
                || accessControlService.isSupporter(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
