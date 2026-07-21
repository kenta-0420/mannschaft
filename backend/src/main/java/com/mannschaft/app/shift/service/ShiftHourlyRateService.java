package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.dto.CreateHourlyRateRequest;
import com.mannschaft.app.shift.dto.HourlyRateResponse;
import com.mannschaft.app.shift.entity.ShiftHourlyRateEntity;
import com.mannschaft.app.shift.repository.ShiftHourlyRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * シフト時給サービス。メンバーの時給設定・履歴管理を担当する。
 *
 * <p><b>認可（認可根治 Wave6 追加戦）:</b> 時給は金銭情報のため、参照・登録とも
 * {@link #checkHourlyRateAccess} で per-scope 認可を強制する。判定は F03.5 設計書
 * {@code 01_db_design.md}（時給の閲覧権限 = 本人 + ADMIN/DEPUTY_ADMIN）に準拠する。
 * shift ドメインの金型 {@code ShiftScheduleService#checkScheduleAdminAccess} と同一方針
 * （SYSTEM_ADMIN 短絡許可 + {@code AccessControlService} による TEAM スコープ判定・違反は 403）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftHourlyRateService {

    private final ShiftHourlyRateRepository hourlyRateRepository;
    private final ShiftMapper shiftMapper;
    private final AccessControlService accessControlService;

    /**
     * ユーザーの時給履歴を取得する。
     *
     * @param userId        対象ユーザーID
     * @param teamId        チームID
     * @param currentUserId 操作ユーザーID（認可判定に使用）
     * @return 時給履歴一覧
     */
    public List<HourlyRateResponse> listHourlyRates(Long userId, Long teamId, Long currentUserId) {
        checkHourlyRateAccess(currentUserId, userId, teamId);
        List<ShiftHourlyRateEntity> entities = hourlyRateRepository
                .findByUserIdAndTeamIdOrderByEffectiveFromDesc(userId, teamId);
        return shiftMapper.toHourlyRateResponseList(entities);
    }

    /**
     * 特定日時点の有効時給を取得する。
     *
     * @param userId        対象ユーザーID
     * @param teamId        チームID
     * @param date          基準日
     * @param currentUserId 操作ユーザーID（認可判定に使用）
     * @return 有効な時給（存在しない場合はnull）
     */
    public HourlyRateResponse getEffectiveRate(Long userId, Long teamId, LocalDate date, Long currentUserId) {
        checkHourlyRateAccess(currentUserId, userId, teamId);
        return hourlyRateRepository.findEffectiveRate(userId, teamId, date)
                .map(shiftMapper::toHourlyRateResponse)
                .orElse(null);
    }

    /**
     * 時給を設定する。
     *
     * @param teamId        チームID
     * @param req           設定リクエスト
     * @param currentUserId 操作ユーザーID（認可判定に使用）
     * @return 設定された時給
     */
    @Transactional
    public HourlyRateResponse createHourlyRate(Long teamId, CreateHourlyRateRequest req, Long currentUserId) {
        checkHourlyRateAccess(currentUserId, req.getUserId(), teamId);
        ShiftHourlyRateEntity entity = ShiftHourlyRateEntity.builder()
                .userId(req.getUserId())
                .teamId(teamId)
                .hourlyRate(req.getHourlyRate())
                .effectiveFrom(req.getEffectiveFrom())
                .build();

        entity = hourlyRateRepository.save(entity);
        log.info("時給設定: id={}, userId={}, teamId={}, rate={}", entity.getId(), req.getUserId(), teamId, req.getHourlyRate());
        return shiftMapper.toHourlyRateResponse(entity);
    }

    /**
     * 時給設定を削除する。
     *
     * @param rateId 時給設定ID
     */
    @Transactional
    public void deleteHourlyRate(Long rateId) {
        hourlyRateRepository.deleteById(rateId);
        log.info("時給設定削除: id={}", rateId);
    }

    /**
     * 時給（金銭情報）に対する per-scope 認可を強制する（認可根治 Wave6 追加戦）。
     *
     * <p>判定順:</p>
     * <ol>
     *   <li>SYSTEM_ADMIN は短絡的に許可（shift ドメインの既存金型
     *       {@code ShiftScheduleService#checkTeamAdminAccess} と同一）</li>
     *   <li>対象が本人 — 当該チームのメンバーであることを要求</li>
     *   <li>対象が他メンバー — 呼び出し元が当該チームの ADMIN/DEPUTY_ADMIN であること、
     *       かつ対象ユーザーも当該チームのメンバーであることを要求（対象側 BOLA 封鎖）</li>
     * </ol>
     *
     * <p>本メソッドは {@code AccessControlService} の呼び出しを直接含める（委譲を挟まない）。
     * 認可番人（ArchUnit）の委譲追跡が 2 ホップまでのため、公開エンドポイントから
     * 「コントローラ → サービスメソッド → 本ヘルパー」の 2 ホップに収める必要がある。</p>
     *
     * @param currentUserId 操作ユーザーID
     * @param targetUserId  時給の対象ユーザーID
     * @param teamId        チームID
     * @throws com.mannschaft.app.common.BusinessException 権限がない場合（COMMON_002 / 403）
     */
    private void checkHourlyRateAccess(Long currentUserId, Long targetUserId, Long teamId) {
        if (accessControlService.isSystemAdmin(currentUserId)) {
            return;
        }
        if (currentUserId.equals(targetUserId)) {
            accessControlService.checkMembership(currentUserId, teamId, "TEAM");
            return;
        }
        accessControlService.checkAdminOrAbove(currentUserId, teamId, "TEAM");
        accessControlService.checkMembership(targetUserId, teamId, "TEAM");
    }
}
