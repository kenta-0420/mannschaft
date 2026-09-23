package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.dto.AvailabilityDefaultRequest;
import com.mannschaft.app.shift.dto.AvailabilityDefaultResponse;
import com.mannschaft.app.shift.dto.BulkAvailabilityDefaultRequest;
import com.mannschaft.app.shift.entity.MemberAvailabilityDefaultEntity;
import com.mannschaft.app.shift.repository.MemberAvailabilityDefaultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private final AccessControlService accessControlService;
    /**
     * D-5（クロスドメイン Repository 依存の禁止）により team ドメインの Repository を
     * 直接引けないため、common の越境窓口 {@link NameResolverService} を使って teamId の実在確認を行う。
     */
    private final NameResolverService nameResolverService;

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

        // 入力検証も delete より前に行う（拒否経路で既存行を破壊的に消さないため。CMP-260912-1758）
        validateAvailabilities(req.getAvailabilities());

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
     * デフォルト勤務可能時間一括設定の入力を検証する（CMP-260912-1758）。
     *
     * <p>{@code dayOfWeek} の範囲（0〜6）は {@link AvailabilityDefaultRequest} の Bean Validation で
     * 検証済み（{@code @Min}/{@code @Max}）。ここではリスト全体を見なければ判定できない検証のみ行う。</p>
     *
     * <p><b>15分グリッドに揃えない理由（設計判断・決定済み）:</b> 枠側の
     * {@code ShiftSlotTimeValidator}（戦役A-1）は15分刻みを要求するが、曜日既定はこれに揃えない。
     * 既存データが全件 {@code 00:00}-{@code 23:59} であり、{@code 23:59} は15分グリッドに乗らないため、
     * 揃えると既存データが全件不正になる。ここでは前後関係のみ検証する。</p>
     *
     * @throws BusinessException {@code startTime >= endTime} なら {@link ShiftErrorCode#INVALID_TIME_RANGE}、
     *                           同一 {@code dayOfWeek} の重複行があれば
     *                           {@link ShiftErrorCode#DUPLICATE_AVAILABILITY_DAY_OF_WEEK}、
     *                           {@code preference} が {@link ShiftPreference} の有効値でなければ
     *                           {@link ShiftErrorCode#INVALID_AVAILABILITY_PREFERENCE}
     */
    private void validateAvailabilities(List<AvailabilityDefaultRequest> availabilities) {
        Set<Integer> seenDaysOfWeek = new HashSet<>();
        for (AvailabilityDefaultRequest avail : availabilities) {
            if (!avail.getStartTime().isBefore(avail.getEndTime())) {
                throw new BusinessException(ShiftErrorCode.INVALID_TIME_RANGE);
            }
            if (!seenDaysOfWeek.add(avail.getDayOfWeek())) {
                throw new BusinessException(ShiftErrorCode.DUPLICATE_AVAILABILITY_DAY_OF_WEEK);
            }
            try {
                ShiftPreference.valueOf(avail.getPreference());
            } catch (IllegalArgumentException e) {
                throw new BusinessException(ShiftErrorCode.INVALID_AVAILABILITY_PREFERENCE);
            }
        }
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
     * {@code member_availability_defaults.team_id} のクロスドメイン FK が既に削除済みで DB が止めないため）。
     * D-5（クロスドメイン Repository 依存の禁止）により team ドメインの Repository を直接引けないため、
     * common の越境窓口 {@link NameResolverService} を使う。{@code findAllById} の結果に現れないことが
     * 非実在を意味する。</p>
     *
     * @throws BusinessException 認可拒否時（{@link CommonErrorCode#COMMON_002} / 403）
     */
    private void checkTeamAccess(Long userId, Long teamId) {
        if (!nameResolverService.resolveTeamNames(Set.of(teamId)).containsKey(teamId)) {
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
