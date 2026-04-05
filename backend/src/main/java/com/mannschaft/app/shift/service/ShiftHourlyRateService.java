package com.mannschaft.app.shift.service;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftHourlyRateService {

    private final ShiftHourlyRateRepository hourlyRateRepository;
    private final ShiftMapper shiftMapper;

    /**
     * ユーザーの時給履歴を取得する。
     *
     * @param userId ユーザーID
     * @param teamId チームID
     * @return 時給履歴一覧
     */
    public List<HourlyRateResponse> listHourlyRates(Long userId, Long teamId) {
        List<ShiftHourlyRateEntity> entities = hourlyRateRepository
                .findByUserIdAndTeamIdOrderByEffectiveFromDesc(userId, teamId);
        return shiftMapper.toHourlyRateResponseList(entities);
    }

    /**
     * 特定日時点の有効時給を取得する。
     *
     * @param userId ユーザーID
     * @param teamId チームID
     * @param date   基準日
     * @return 有効な時給（存在しない場合はnull）
     */
    public HourlyRateResponse getEffectiveRate(Long userId, Long teamId, LocalDate date) {
        return hourlyRateRepository.findEffectiveRate(userId, teamId, date)
                .map(shiftMapper::toHourlyRateResponse)
                .orElse(null);
    }

    /**
     * 時給を設定する。
     *
     * @param teamId チームID
     * @param req    設定リクエスト
     * @return 設定された時給
     */
    @Transactional
    public HourlyRateResponse createHourlyRate(Long teamId, CreateHourlyRateRequest req) {
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
}
