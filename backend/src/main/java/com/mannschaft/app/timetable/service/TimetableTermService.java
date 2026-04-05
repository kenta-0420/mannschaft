package com.mannschaft.app.timetable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timetable.TimetableErrorCode;
import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import com.mannschaft.app.timetable.repository.TimetableRepository;
import com.mannschaft.app.timetable.repository.TimetableTermRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学期サービス。学期のCRUDおよびチーム・組織スコープの学期管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TimetableTermService {

    private final TimetableTermRepository termRepository;
    private final TimetableRepository timetableRepository;

    /**
     * 組織の学期一覧を取得する。
     */
    public List<TimetableTermEntity> getOrganizationTerms(Long orgId) {
        return termRepository.findByOrganizationIdOrderByAcademicYearDescSortOrder(orgId);
    }

    /**
     * チームの学期一覧を取得する。
     * チーム固有の学期と親組織の学期をマージし、チーム固有が優先される。
     */
    public List<TimetableTermEntity> getTeamTerms(Long teamId, Long organizationId) {
        List<TimetableTermEntity> teamTerms =
                termRepository.findByTeamIdOrderByAcademicYearDescSortOrder(teamId);
        List<TimetableTermEntity> orgTerms =
                termRepository.findByOrganizationIdOrderByAcademicYearDescSortOrder(organizationId);

        // チーム固有学期を優先してマージ（年度×名前をキーとして重複排除）
        Map<String, TimetableTermEntity> merged = new LinkedHashMap<>();
        for (TimetableTermEntity term : teamTerms) {
            merged.put(termKey(term), term);
        }
        for (TimetableTermEntity term : orgTerms) {
            merged.putIfAbsent(termKey(term), term);
        }
        return List.copyOf(merged.values());
    }

    /**
     * 学期IDで学期を取得する。見つからない場合は例外をスローする。
     */
    public TimetableTermEntity getByTermId(Long termId) {
        return termRepository.findById(termId)
                .orElseThrow(() -> new BusinessException(TimetableErrorCode.TERM_NOT_FOUND));
    }

    /**
     * 学期を作成する。
     *
     * @param scopeId スコープID（チームIDまたは組織ID）
     * @param isTeam  true: チームスコープ、false: 組織スコープ
     * @param data    作成データ
     */
    @Transactional
    public TimetableTermEntity createTerm(Long scopeId, boolean isTeam, CreateTermData data) {
        validateTermUniqueness(scopeId, isTeam, data.academicYear(), data.name(), null);
        validateTermDateRange(scopeId, isTeam, data.academicYear(), data.startDate(), data.endDate(), null);

        TimetableTermEntity entity = TimetableTermEntity.builder()
                .teamId(isTeam ? scopeId : null)
                .organizationId(isTeam ? null : scopeId)
                .academicYear(data.academicYear())
                .name(data.name())
                .startDate(data.startDate())
                .endDate(data.endDate())
                .sortOrder(data.sortOrder())
                .build();

        return termRepository.save(entity);
    }

    /**
     * 学期を更新する。
     */
    @Transactional
    public TimetableTermEntity updateTerm(Long termId, UpdateTermData data) {
        TimetableTermEntity entity = getByTermId(termId);

        boolean isTeam = entity.getTeamId() != null;
        Long scopeId = isTeam ? entity.getTeamId() : entity.getOrganizationId();

        validateTermUniqueness(scopeId, isTeam, entity.getAcademicYear(), data.name(), termId);
        validateTermDateRange(scopeId, isTeam, entity.getAcademicYear(),
                data.startDate(), data.endDate(), termId);

        TimetableTermEntity updated = entity.toBuilder()
                .name(data.name())
                .startDate(data.startDate())
                .endDate(data.endDate())
                .sortOrder(data.sortOrder())
                .build();

        return termRepository.save(updated);
    }

    /**
     * 学期を削除する。紐づく時間割がある場合は削除不可。
     */
    @Transactional
    public void deleteTerm(Long termId) {
        TimetableTermEntity entity = getByTermId(termId);

        // 紐づく時間割の存在チェック
        List<?> timetables = timetableRepository.findByTeamIdOrderByEffectiveFromDesc(
                entity.getTeamId() != null ? entity.getTeamId() : 0L);
        boolean hasTimetables = timetables.stream()
                .anyMatch(t -> {
                    if (t instanceof com.mannschaft.app.timetable.entity.TimetableEntity tt) {
                        return termId.equals(tt.getTermId());
                    }
                    return false;
                });
        if (hasTimetables) {
            throw new BusinessException(TimetableErrorCode.TERM_HAS_TIMETABLES);
        }

        termRepository.delete(entity);
    }

    // ---- Validation Helpers ----

    private void validateTermUniqueness(Long scopeId, boolean isTeam, Integer academicYear,
                                        String name, Long excludeTermId) {
        List<TimetableTermEntity> existing = isTeam
                ? termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(scopeId, academicYear)
                : termRepository.findByOrganizationIdAndAcademicYearOrderBySortOrder(scopeId, academicYear);

        boolean duplicate = existing.stream()
                .filter(t -> !t.getId().equals(excludeTermId))
                .anyMatch(t -> t.getName().equals(name));

        if (duplicate) {
            throw new BusinessException(TimetableErrorCode.DUPLICATE_TERM_NAME);
        }
    }

    private void validateTermDateRange(Long scopeId, boolean isTeam, Integer academicYear,
                                       LocalDate startDate, LocalDate endDate, Long excludeTermId) {
        List<TimetableTermEntity> existing = isTeam
                ? termRepository.findByTeamIdAndAcademicYearOrderBySortOrder(scopeId, academicYear)
                : termRepository.findByOrganizationIdAndAcademicYearOrderBySortOrder(scopeId, academicYear);

        boolean overlap = existing.stream()
                .filter(t -> !t.getId().equals(excludeTermId))
                .anyMatch(t -> !t.getEndDate().isBefore(startDate) && !t.getStartDate().isAfter(endDate));

        if (overlap) {
            throw new BusinessException(TimetableErrorCode.TERM_DATE_OVERLAP);
        }
    }

    private String termKey(TimetableTermEntity term) {
        return term.getAcademicYear() + ":" + term.getName();
    }

    /**
     * 学期作成データ。
     */
    public record CreateTermData(
            Integer academicYear,
            String name,
            LocalDate startDate,
            LocalDate endDate,
            Integer sortOrder
    ) {}

    /**
     * 学期更新データ。
     */
    public record UpdateTermData(
            String name,
            LocalDate startDate,
            LocalDate endDate,
            Integer sortOrder
    ) {}
}
