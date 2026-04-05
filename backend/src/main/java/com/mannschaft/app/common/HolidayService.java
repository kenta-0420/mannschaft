package com.mannschaft.app.common;

import com.mannschaft.app.common.entity.HolidayMasterEntity;
import com.mannschaft.app.common.repository.HolidayMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 祝日判定サービス。祝日マスタを参照して祝日判定を行う。
 * 結果はValkeyキャッシュで高速化する。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HolidayService {

    private final HolidayMasterRepository holidayMasterRepository;

    /**
     * 指定日がシステム共通の祝日か判定する。
     */
    @Cacheable(value = "holidays", key = "'system:' + #date")
    public boolean isSystemHoliday(LocalDate date) {
        return holidayMasterRepository.existsByScopeTypeAndScopeIdAndHolidayDate(
                "SYSTEM", 0L, date);
    }

    /**
     * 指定日がシステム共通またはスコープ固有の祝日か判定する。
     */
    @Cacheable(value = "holidays", key = "#scopeType + ':' + #scopeId + ':' + #date")
    public boolean isHoliday(LocalDate date, String scopeType, Long scopeId) {
        return holidayMasterRepository.isHoliday(date, scopeType, scopeId);
    }

    /**
     * 期間内の祝日一覧を取得する。
     */
    public List<HolidayMasterEntity> getHolidaysInRange(LocalDate from, LocalDate to,
                                                         String scopeType, Long scopeId) {
        return holidayMasterRepository.findHolidaysInRange(from, to, scopeType, scopeId);
    }

    /**
     * システム共通の年間祝日一覧を取得する。
     */
    @Cacheable(value = "holidays", key = "'system-year:' + #year")
    public List<HolidayMasterEntity> getSystemHolidaysByYear(int year) {
        return holidayMasterRepository.findSystemHolidaysByYear(year);
    }
}
