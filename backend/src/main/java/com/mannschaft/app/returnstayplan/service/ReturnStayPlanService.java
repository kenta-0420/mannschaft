package com.mannschaft.app.returnstayplan.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.returnstayplan.ReturnStayPlanErrorCode;
import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanEntity;
import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanTeamVisibilityEntity;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanOwnerLockRepository;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanRepository;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanTeamVisibilityRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** F02.11 return/stay plan business boundary. */
@Service
public class ReturnStayPlanService {

    private static final String JST = "Asia/Tokyo";
    private static final int MAX_ACTIVE = 30;
    private static final int MAX_TEAM_IDS = 20;
    private static final int MAX_MEMBER_IDS = 400;

    private final Clock clock;
    private final ReturnStayPlanRepository plans;
    private final ReturnStayPlanTeamVisibilityRepository visibilities;
    private final ReturnStayPlanOwnerLockRepository locks;
    private final ReturnStayPlanAccessGuard accessGuard;

    public ReturnStayPlanService(
            Clock clock,
            ReturnStayPlanRepository plans,
            ReturnStayPlanTeamVisibilityRepository visibilities,
            ReturnStayPlanOwnerLockRepository locks,
            ReturnStayPlanAccessGuard accessGuard) {
        this.clock = clock;
        this.plans = plans;
        this.visibilities = visibilities;
        this.locks = locks;
        this.accessGuard = accessGuard;
    }

    @Transactional
    public ReturnStayPlanEntity create(Long ownerUserId, ReturnStayPlanCreateRequest request) {
        validate(request);
        validateTeamIds(ownerUserId, request.teamIds());
        lockOwner(ownerUserId);
        if (countActive(ownerUserId) >= MAX_ACTIVE) {
            throw error(ReturnStayPlanErrorCode.LIMIT_EXCEEDED);
        }
        ReturnStayPlanEntity saved = plans.saveAndFlush(toEntity(ownerUserId, request));
        replaceVisibility(saved, request.teamIds());
        return saved;
    }

    @Transactional(readOnly = true)
    public ReturnStayPlanEntity getForOwner(Long ownerUserId, UUID planId) {
        return accessGuard.findByIdAndOwnerUserId(planId, ownerUserId);
    }

    @Transactional
    public ReturnStayPlanEntity update(
            Long ownerUserId,
            UUID planId,
            Long version,
            ReturnStayPlanCreateRequest request) {
        if (version == null || version < 0) {
            throw invalid();
        }
        ReturnStayPlanEntity current = accessGuard.findByIdAndOwnerUserId(planId, ownerUserId);
        if (!version.equals(current.getVersion())) {
            throw error(ReturnStayPlanErrorCode.VERSION_CONFLICT);
        }
        validate(request);
        validateTeamIds(ownerUserId, request.teamIds());
        DisplayStatus currentStatus = resolveStatus(
                current.getStartDate(), current.getEndDate(), current.getTimezone());
        if (currentStatus == DisplayStatus.ACTIVE) {
            validateActiveUpdate(current, request);
        }
        if (currentStatus == DisplayStatus.ENDED
                && !request.endDate().isBefore(today(JST))) {
            lockOwner(ownerUserId);
            if (countActive(ownerUserId) >= MAX_ACTIVE) {
                throw error(ReturnStayPlanErrorCode.LIMIT_EXCEEDED);
            }
        }

        current.setPlanType(type(request.planType()));
        current.setPublished(request.isPublished());
        current.setCountryCode("JP");
        current.setPrefectureCode(request.location().prefectureCode().trim());
        current.setRegionName(null);
        current.setTimezone(JST);
        current.setStartDate(request.startDate());
        current.setEndDate(request.endDate());
        try {
            ReturnStayPlanEntity saved = plans.saveAndFlush(current);
            replaceVisibility(saved, request.teamIds());
            return saved;
        } catch (OptimisticLockingFailureException exception) {
            throw new BusinessException(ReturnStayPlanErrorCode.VERSION_CONFLICT, exception);
        }
    }

    @Transactional
    public void delete(Long ownerUserId, UUID planId) {
        plans.delete(accessGuard.findByIdAndOwnerUserId(planId, ownerUserId));
    }

    @Transactional(readOnly = true)
    public Page<ReturnStayPlanEntity> list(
            Long ownerUserId, boolean includeEnded, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw error(ReturnStayPlanErrorCode.INVALID_PAGING);
        }
        PageRequest pageable = PageRequest.of(page, size,
                Sort.by("endDate").ascending()
                        .and(Sort.by("startDate").ascending())
                        .and(Sort.by("id").ascending()));
        return includeEnded
                ? plans.findByOwnerUserId(ownerUserId, pageable)
                : plans.findByOwnerUserIdAndEndDateGreaterThanEqual(ownerUserId, today(JST), pageable);
    }

    @Transactional(readOnly = true)
    public boolean visibleToMember(Long viewerUserId, Long ownerUserId, Long teamId, UUID planId) {
        return visibilities.existsVisiblePlan(viewerUserId, ownerUserId, teamId, planId);
    }

    @Transactional(readOnly = true)
    public Map<Long, List<TeamPlanView>> listVisiblePlansForMembers(
            Long viewerUserId, String teamSlug, List<Long> memberIds) {
        validateMemberIds(memberIds);
        Long teamId = visibilities.findAuthorizedTeamId(teamSlug, viewerUserId)
                .orElseThrow(() -> error(ReturnStayPlanErrorCode.TEAM_ACCESS_DENIED));
        Map<Long, List<TeamPlanView>> result = new LinkedHashMap<>();
        memberIds.forEach(memberId -> result.put(memberId, new ArrayList<>()));
        visibilities.findVisiblePlans(viewerUserId, teamId, memberIds, today(JST))
                .forEach(row -> result.get(row.getOwnerUserId()).add(toTeamView(row)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<TeamPlanView> listVisiblePlansForMember(
            Long viewerUserId, String teamSlug, Long memberId) {
        return listVisiblePlansForMembers(viewerUserId, teamSlug, List.of(memberId)).get(memberId);
    }

    @Transactional
    public int purgeExpiredPlans() {
        int deleted = 0;
        LocalDate candidateCutoff = today(JST).minusYears(1);
        for (ReturnStayPlanEntity plan
                : plans.findTop500ByEndDateBeforeOrderByEndDateAscIdAsc(candidateCutoff)) {
            if (plan.getEndDate().isBefore(today(plan.getTimezone()).minusYears(1))) {
                plans.delete(plan);
                deleted++;
            }
        }
        return deleted;
    }

    @Transactional
    public int deleteAllForOwner(Long ownerUserId) {
        int count = Math.toIntExact(plans.countByOwnerUserId(ownerUserId));
        plans.deleteByOwnerUserId(ownerUserId);
        return count;
    }

    void validateActiveUpdate(
            ReturnStayPlanEntity current, ReturnStayPlanCreateRequest request) {
        if (!current.getPlanType().name().equals(request.planType())
                || !current.getStartDate().equals(request.startDate())
                || request.endDate().isBefore(current.getEndDate())) {
            throw invalid();
        }
    }

    DisplayStatus resolveStatus(LocalDate startDate, LocalDate endDate, String timezone) {
        LocalDate now = today(timezone);
        return now.isBefore(startDate)
                ? DisplayStatus.UPCOMING
                : now.isAfter(endDate) ? DisplayStatus.ENDED : DisplayStatus.ACTIVE;
    }

    enum DisplayStatus { UPCOMING, ACTIVE, ENDED }

    public record TeamPlanView(
            UUID id,
            String ownerDisplayName,
            String ownerAvatarUrl,
            String planType,
            String countryCode,
            String prefectureCode,
            String regionName,
            String timezone,
            LocalDate startDate,
            LocalDate endDate,
            String status) { }

    private void validate(ReturnStayPlanCreateRequest request) {
        if (request == null || request.planType() == null || request.isPublished() == null
                || request.location() == null || request.startDate() == null
                || request.endDate() == null || request.teamIds() == null) {
            throw invalid();
        }
        type(request.planType());
        if (request.teamIds().stream().anyMatch(java.util.Objects::isNull)
                || request.teamIds().stream().distinct().count() != request.teamIds().size()) {
            throw invalid();
        }
        String country = request.location().countryCode() == null
                ? "" : request.location().countryCode().trim().toUpperCase();
        String prefecture = request.location().prefectureCode() == null
                ? "" : request.location().prefectureCode().trim();
        if (!"JP".equals(country)
                || !prefecture.matches("(?:0[1-9]|[1-3][0-9]|4[0-7])")
                || (request.location().regionName() != null
                && !request.location().regionName().isBlank())) {
            throw invalid();
        }
        LocalDate ownerToday = today(JST);
        if (request.startDate().isBefore(ownerToday)
                || request.endDate().isBefore(request.startDate())
                || request.endDate().isAfter(ownerToday.plusDays(365))) {
            throw invalid();
        }
        if (Boolean.TRUE.equals(request.isPublished()) && request.teamIds().isEmpty()) {
            throw invalid();
        }
        if (request.teamIds().size() > MAX_TEAM_IDS) {
            throw error(ReturnStayPlanErrorCode.LIMIT_EXCEEDED);
        }
    }

    private void validateTeamIds(Long ownerUserId, List<Long> teamIds) {
        if (!teamIds.isEmpty()
                && visibilities.countSaveableTeams(ownerUserId, teamIds) != teamIds.size()) {
            throw error(ReturnStayPlanErrorCode.TEAM_ACCESS_DENIED);
        }
    }

    private void validateMemberIds(List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty() || memberIds.size() > MAX_MEMBER_IDS
                || memberIds.stream().anyMatch(java.util.Objects::isNull)
                || memberIds.stream().distinct().count() != memberIds.size()) {
            throw invalid();
        }
    }

    private void lockOwner(Long ownerUserId) {
        locks.insertIfAbsent(UuidV7.generate(clock), ownerUserId);
        locks.findByOwnerUserIdForUpdate(ownerUserId)
                .orElseThrow(() -> new IllegalStateException("owner lock row was not created"));
    }

    private long countActive(Long ownerUserId) {
        return plans.countByOwnerUserIdAndEndDateGreaterThanEqual(ownerUserId, today(JST));
    }

    private ReturnStayPlanEntity toEntity(
            Long ownerUserId, ReturnStayPlanCreateRequest request) {
        return ReturnStayPlanEntity.builder()
                .ownerUserId(ownerUserId)
                .planType(type(request.planType()))
                .published(request.isPublished())
                .countryCode("JP")
                .prefectureCode(request.location().prefectureCode().trim())
                .regionName(null)
                .timezone(JST)
                .startDate(request.startDate())
                .endDate(request.endDate())
                .build();
    }

    private void replaceVisibility(ReturnStayPlanEntity plan, List<Long> teamIds) {
        visibilities.deleteByPlanId(plan.getId());
        if (!teamIds.isEmpty()) {
            visibilities.saveAll(teamIds.stream()
                    .map(teamId -> ReturnStayPlanTeamVisibilityEntity.builder()
                            .plan(plan)
                            .teamId(teamId)
                            .build())
                    .toList());
        }
    }

    private TeamPlanView toTeamView(
            ReturnStayPlanTeamVisibilityRepository.VisiblePlanProjection row) {
        return new TeamPlanView(
                uuidFromHex(row.getPlanIdHex()),
                row.getOwnerDisplayName(),
                row.getOwnerAvatarUrl(),
                row.getPlanType(),
                row.getCountryCode(),
                row.getPrefectureCode(),
                row.getRegionName(),
                row.getTimezone(),
                row.getStartDate(),
                row.getEndDate(),
                resolveStatus(row.getStartDate(), row.getEndDate(), row.getTimezone()).name());
    }

    private UUID uuidFromHex(String hex) {
        String normalized = hex.toLowerCase(java.util.Locale.ROOT);
        return UUID.fromString(normalized.substring(0, 8) + "-"
                + normalized.substring(8, 12) + "-"
                + normalized.substring(12, 16) + "-"
                + normalized.substring(16, 20) + "-"
                + normalized.substring(20));
    }

    private ReturnStayPlanEntity.PlanType type(String value) {
        try {
            return ReturnStayPlanEntity.PlanType.valueOf(value);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private LocalDate today(String timezone) {
        try {
            return LocalDate.now(clock.withZone(ZoneId.of(timezone)));
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private BusinessException invalid() {
        return error(ReturnStayPlanErrorCode.INVALID_REQUEST);
    }

    private BusinessException error(ReturnStayPlanErrorCode code) {
        return new BusinessException(code);
    }
}
