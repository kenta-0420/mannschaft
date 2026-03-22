package com.mannschaft.app.timetable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.TimetableErrorCode;
import com.mannschaft.app.timetable.entity.TimetablePeriodTemplateEntity;
import com.mannschaft.app.timetable.repository.TimetablePeriodTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 時限テンプレートサービス。組織単位の時限マスタを管理する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetablePeriodTemplateService {

    private static final int MAX_PERIODS = 15;

    private final TimetablePeriodTemplateRepository periodTemplateRepository;

    /**
     * 組織の時限テンプレート一覧を取得する。
     */
    public List<TimetablePeriodTemplateEntity> getByOrganization(Long orgId) {
        return periodTemplateRepository.findByOrganizationIdOrderByPeriodNumber(orgId);
    }

    /**
     * 組織の時限テンプレートを全置換する。
     * 既存レコードを全削除し、新しいテンプレートを一括登録する。
     */
    @Transactional
    public List<TimetablePeriodTemplateEntity> replaceAll(Long orgId, List<PeriodTemplateData> periods) {
        validatePeriods(periods);

        periodTemplateRepository.deleteByOrganizationId(orgId);

        List<TimetablePeriodTemplateEntity> entities = periods.stream()
                .map(p -> {
                    var entity = new TimetablePeriodTemplateEntity();
                    entity.setOrganizationId(orgId);
                    entity.setPeriodNumber(p.periodNumber());
                    entity.setLabel(p.label());
                    entity.setStartTime(p.startTime());
                    entity.setEndTime(p.endTime());
                    entity.setIsBreak(p.isBreak());
                    return entity;
                })
                .toList();

        return periodTemplateRepository.saveAll(entities);
    }

    private void validatePeriods(List<PeriodTemplateData> periods) {
        if (periods.size() > MAX_PERIODS) {
            throw new BusinessException(TimetableErrorCode.INVALID_PERIOD_OVERRIDE);
        }

        Set<Integer> periodNumbers = new HashSet<>();
        for (PeriodTemplateData period : periods) {
            // period_number 重複チェック
            if (!periodNumbers.add(period.periodNumber())) {
                throw new BusinessException(TimetableErrorCode.INVALID_PERIOD_OVERRIDE);
            }
            // start < end チェック
            if (!period.startTime().isBefore(period.endTime())) {
                throw new BusinessException(TimetableErrorCode.INVALID_PERIOD_OVERRIDE);
            }
        }
    }

    /**
     * 時限テンプレートのデータ。
     */
    public record PeriodTemplateData(
            Integer periodNumber,
            String label,
            LocalTime startTime,
            LocalTime endTime,
            Boolean isBreak
    ) {}
}
