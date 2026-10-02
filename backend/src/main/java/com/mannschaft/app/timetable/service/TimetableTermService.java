package com.mannschaft.app.timetable.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
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
    private final AccessControlService accessControlService;
    private final TeamOrgMembershipQueryService teamOrgMembershipQueryService;

    /** 認可根治Wave2: F00.5 メンバーシップ・ロール判定のスコープ種別。 */
    private static final String SCOPE_TEAM = "TEAM";
    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    /**
     * 組織の学期一覧を取得する。
     */
    public List<TimetableTermEntity> getOrganizationTerms(Long orgId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, orgId, SCOPE_ORGANIZATION);
        return termRepository.findByOrganizationIdOrderByAcademicYearDescSortOrder(orgId);
    }

    /**
     * チームの学期一覧を取得する。
     * チーム固有の学期と親組織の学期をマージし、チーム固有が優先される。
     *
     * <p>★BOLA厳禁★: {@code organizationId} はクライアント供給の request param であり、
     * teamId のチームメンバーであっても無関係な組織の学期情報を読める余地があるため鵜呑みにしない。
     * {@link TeamOrgMembershipQueryService} でチームの実際の ACTIVE 所属組織一覧と照合し、
     * 一致しない場合は無関係組織の学期を返さず 404 とする（scope 混同防止）。</p>
     */
    public List<TimetableTermEntity> getTeamTerms(Long teamId, Long organizationId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TEAM);

        List<Long> activeOrgIds = teamOrgMembershipQueryService.findActiveOrganizationIds(teamId);
        if (!activeOrgIds.contains(organizationId)) {
            throw new BusinessException(TimetableErrorCode.TERM_NOT_FOUND);
        }

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
     * @param actorUserId 操作者ユーザーID
     */
    @Transactional
    public TimetableTermEntity createTerm(Long scopeId, boolean isTeam, CreateTermData data, Long actorUserId) {
        // createは作成先スコープ（path scopeId）で checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, isTeam ? SCOPE_TEAM : SCOPE_ORGANIZATION);

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
    public TimetableTermEntity updateTerm(Long termId, UpdateTermData data, Long actorUserId) {
        TimetableTermEntity entity = getByTermId(termId);

        boolean isTeam = entity.getTeamId() != null;
        Long scopeId = isTeam ? entity.getTeamId() : entity.getOrganizationId();

        // 変更系は entity 由来 scope（team/organization どちらに属するかも entity 由来）で checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, isTeam ? SCOPE_TEAM : SCOPE_ORGANIZATION);

        validateTermUniqueness(scopeId, isTeam, entity.getAcademicYear(), data.name(), termId);
        validateTermDateRange(scopeId, isTeam, entity.getAcademicYear(),
                data.startDate(), data.endDate(), termId);

        // toBuilder().build() で作り直すと id=null の新インスタンスになり INSERT 化するため、
        // managed entity を直接ミューテートして UPDATE に固定する（#1643 同型バグ根治）。
        entity.applyUpdate(data.name(), data.startDate(), data.endDate(), data.sortOrder());
        return termRepository.save(entity);
    }

    /**
     * 学期を削除する。紐づく時間割がある場合は削除不可。
     */
    @Transactional
    public void deleteTerm(Long termId, Long actorUserId) {
        TimetableTermEntity entity = getByTermId(termId);

        boolean isTeam = entity.getTeamId() != null;
        Long scopeId = isTeam ? entity.getTeamId() : entity.getOrganizationId();
        // 変更系は entity 由来 scope で checkAdminOrAbove。
        accessControlService.checkAdminOrAbove(actorUserId, scopeId, isTeam ? SCOPE_TEAM : SCOPE_ORGANIZATION);

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
