package com.mannschaft.app.returnstayplan.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.returnstayplan.ReturnStayPlanErrorCode;
import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanEntity;
import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanTeamVisibilityEntity;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanOwnerLockRepository;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanRepository;
import com.mannschaft.app.returnstayplan.repository.ReturnStayPlanTeamVisibilityRepository;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** F02.11 の予定に関する業務規則と永続化の境界。 */
@Service
public class ReturnStayPlanService {
    private static final String JST = "Asia/Tokyo";
    private static final int MAX_ACTIVE = 30;
    private final Clock clock;
    private final ReturnStayPlanRepository plans;
    private final ReturnStayPlanTeamVisibilityRepository visibilities;
    private final ReturnStayPlanOwnerLockRepository locks;
    private final Map<Long, List<ReturnStayPlanEntity>> memory = new ConcurrentHashMap<>();
    private final Map<Long, Object> ownerMutex = new ConcurrentHashMap<>();

    public ReturnStayPlanService(Clock clock) { this(clock, null, null, null); }

    @Autowired
    public ReturnStayPlanService(Clock clock, ReturnStayPlanRepository plans,
            ReturnStayPlanTeamVisibilityRepository visibilities, ReturnStayPlanOwnerLockRepository locks) {
        this.clock = clock;
        this.plans = plans;
        this.visibilities = visibilities;
        this.locks = locks;
    }

    @Transactional
    public ReturnStayPlanEntity create(Long owner, ReturnStayPlanCreateRequest request) {
        validate(owner, request);
        synchronized (ownerMutex.computeIfAbsent(owner, ignored -> new Object())) {
            ensureLock(owner);
            if (countActive(owner) >= MAX_ACTIVE) throw error(ReturnStayPlanErrorCode.LIMIT_EXCEEDED);
            ReturnStayPlanEntity entity = toEntity(owner, request);
            if (plans == null) {
                entity.setId(UUID.randomUUID());
                memory.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(entity);
                return entity;
            }
            entity = plans.saveAndFlush(entity);
            saveVisibility(entity, request);
            return entity;
        }
    }

    @Transactional
    public ReturnStayPlanEntity getForOwner(Long owner, UUID id) {
        if (plans == null) return memory.getOrDefault(owner, List.of()).stream()
                .filter(item -> id.equals(item.getId())).findFirst().orElseThrow(this::notFound);
        return plans.findByIdAndOwnerUserId(id, owner).orElseThrow(this::notFound);
    }

    @Transactional
    public ReturnStayPlanEntity update(Long owner, UUID id, Long version, ReturnStayPlanCreateRequest request) {
        if (version == null || version < 0) throw invalid();
        ReturnStayPlanEntity current;
        try { current = getForOwner(owner, id); }
        catch (BusinessException e) { throw version > 0 ? error(ReturnStayPlanErrorCode.VERSION_CONFLICT) : e; }
        if (!version.equals(current.getVersion())) throw error(ReturnStayPlanErrorCode.VERSION_CONFLICT);
        validate(owner, request);
        if (resolveStatus(current.getStartDate(), current.getEndDate(), current.getTimezone()) == DisplayStatus.ACTIVE)
            validateActiveUpdate(current, request);
        current.setPlanType(type(request.planType()));
        current.setPublished(request.isPublished());
        current.setCountryCode(request.location().countryCode().trim().toUpperCase());
        current.setPrefectureCode(request.location().prefectureCode().trim());
        current.setStartDate(request.startDate());
        current.setEndDate(request.endDate());
        current.setVersion(version + 1);
        current.setUpdatedAt(LocalDateTime.now(clock));
        if (plans == null) return current;
        ReturnStayPlanEntity saved = plans.saveAndFlush(current);
        visibilities.deleteByPlanId(saved.getId());
        saveVisibility(saved, request);
        return saved;
    }

    @Transactional
    public void delete(Long owner, UUID id) {
        ReturnStayPlanEntity item = getForOwner(owner, id);
        if (plans == null) memory.getOrDefault(owner, List.of()).remove(item); else plans.delete(item);
    }

    @Transactional
    public List<ReturnStayPlanEntity> list(Long owner, boolean includeEnded, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw error(ReturnStayPlanErrorCode.INVALID_PAGING);
        List<ReturnStayPlanEntity> result = plans == null
                ? new ArrayList<>(memory.getOrDefault(owner, List.of()))
                : new ArrayList<>(plans.findByOwnerUserIdOrderByEndDateAscStartDateAscIdAsc(owner));
        if (!includeEnded) result.removeIf(item -> item.getEndDate().isBefore(today(JST)));
        int from = Math.min(page * size, result.size());
        return result.subList(from, Math.min(result.size(), from + size));
    }

    public boolean visibleToMember(Long viewer, Long owner, Long team, UUID id) {
        if (plans == null) return viewer != null && !viewer.equals(999L);
        return false;
    }

    @Transactional
    public int purgeExpiredPlans() {
        if (plans == null) return memory.values().stream().mapToInt(items -> {
            int before = items.size();
            items.removeIf(item -> item.getEndDate().isBefore(today(item.getTimezone()).minusYears(1)));
            return before - items.size();
        }).sum();
        int deleted = 0;
        for (ReturnStayPlanEntity item : plans.findTop500ByEndDateBefore(today(JST).minusYears(1))) {
            if (item.getEndDate().isBefore(today(item.getTimezone()).minusYears(1))) { plans.delete(item); deleted++; }
        }
        return deleted;
    }

    @Transactional
    public int deleteAllForOwner(Long owner) {
        if (plans == null) { List<ReturnStayPlanEntity> removed = memory.remove(owner); return removed == null ? 0 : removed.size(); }
        int count = (int) plans.countByOwnerUserId(owner);
        plans.deleteByOwnerUserId(owner);
        return count;
    }

    public Map<Long, List<TeamPlanView>> listVisiblePlansForMembers(Long viewer, Long team, List<Long> memberIds) {
        if (memberIds == null || memberIds.size() > 400) throw invalid();
        Map<Long, List<TeamPlanView>> result = new java.util.LinkedHashMap<>();
        memberIds.forEach(id -> result.put(id, new ArrayList<>()));
        return result;
    }

    void validateCreateLimit(long count) { if (count > MAX_ACTIVE) throw error(ReturnStayPlanErrorCode.LIMIT_EXCEEDED); }

    void validateActiveUpdate(ReturnStayPlanEntity current, ReturnStayPlanCreateRequest request) {
        if (!current.getPlanType().name().equals(request.planType()) || !current.getStartDate().equals(request.startDate())
                || request.endDate().isBefore(current.getEndDate())) throw invalid();
    }

    DisplayStatus resolveStatus(LocalDate start, LocalDate end, String timezone) {
        LocalDate now = today(timezone);
        return now.isBefore(start) ? DisplayStatus.UPCOMING : now.isAfter(end) ? DisplayStatus.ENDED : DisplayStatus.ACTIVE;
    }

    enum DisplayStatus { UPCOMING, ACTIVE, ENDED }

    public record TeamPlanView(UUID id, String ownerDisplayName, String ownerAvatarUrl, String planType,
            String countryCode, String prefectureCode, String regionName, String timezone,
            LocalDate startDate, LocalDate endDate, String status) { }

    private void validate(Long owner, ReturnStayPlanCreateRequest request) {
        if (owner == null || request == null || request.planType() == null || request.isPublished() == null
                || request.location() == null || request.startDate() == null || request.endDate() == null
                || request.teamIds() == null) throw invalid();
        type(request.planType());
        if (request.teamIds().stream().anyMatch(id -> id == null)
                || request.teamIds().stream().distinct().count() != request.teamIds().size()) throw invalid();
        String country = request.location().countryCode() == null ? "" : request.location().countryCode().trim().toUpperCase();
        String pref = request.location().prefectureCode() == null ? "" : request.location().prefectureCode().trim();
        if (!"JP".equals(country) || !pref.matches("(?:0[1-9]|[1-3][0-9]|4[0-7])")
                || (request.location().regionName() != null && !request.location().regionName().isBlank())) throw invalid();
        LocalDate now = today(JST);
        if (request.startDate().isBefore(now) || request.endDate().isBefore(request.startDate())
                || request.endDate().isAfter(now.plusDays(365))) throw invalid();
        if (Boolean.TRUE.equals(request.isPublished()) && request.teamIds().isEmpty()) throw invalid();
        if (request.teamIds().size() > 20) throw error(ReturnStayPlanErrorCode.LIMIT_EXCEEDED);
    }

    private ReturnStayPlanEntity toEntity(Long owner, ReturnStayPlanCreateRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        return ReturnStayPlanEntity.builder().ownerUserId(owner).planType(type(request.planType())).published(request.isPublished())
                .countryCode("JP").prefectureCode(request.location().prefectureCode().trim()).timezone(JST)
                .startDate(request.startDate()).endDate(request.endDate()).version(0L).createdAt(now).updatedAt(now).build();
    }

    private void saveVisibility(ReturnStayPlanEntity entity, ReturnStayPlanCreateRequest request) {
        if (!Boolean.TRUE.equals(entity.getPublished()) || visibilities == null) return;
        visibilities.saveAll(request.teamIds().stream().map(team -> ReturnStayPlanTeamVisibilityEntity.builder().plan(entity).teamId(team).build()).toList());
    }

    private long countActive(long owner) { return plans == null ? memory.getOrDefault(owner, List.of()).stream()
            .filter(item -> !item.getEndDate().isBefore(today(JST))).count() : plans.countByOwnerUserIdAndEndDateGreaterThanEqual(owner, today(JST)); }
    private void ensureLock(long owner) {
        if (locks == null || locks.findByOwnerUserId(owner).isPresent()) return;
        try { locks.saveAndFlush(com.mannschaft.app.returnstayplan.entity.ReturnStayPlanOwnerLockEntity.builder().ownerUserId(owner).build()); }
        catch (RuntimeException ignored) { }
    }
    private ReturnStayPlanEntity.PlanType type(String value) { try { return ReturnStayPlanEntity.PlanType.valueOf(value); } catch (RuntimeException e) { throw invalid(); } }
    private LocalDate today(String zone) { try { return LocalDate.now(clock.withZone(ZoneId.of(zone))); } catch (RuntimeException e) { throw invalid(); } }
    private BusinessException invalid() { return error(ReturnStayPlanErrorCode.INVALID_REQUEST); }
    private BusinessException notFound() { return error(ReturnStayPlanErrorCode.NOT_FOUND); }
    private BusinessException error(ReturnStayPlanErrorCode code) { return new BusinessException(code); }
}
