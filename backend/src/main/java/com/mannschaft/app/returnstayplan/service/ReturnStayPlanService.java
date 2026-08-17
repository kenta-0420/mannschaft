package com.mannschaft.app.returnstayplan.service;

import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanEntity;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * F02.11 Service の契約骨格。
 *
 * <p>試練段階では業務振る舞いを実装しない。各メソッドは出陣で green 化する。</p>
 */
@Service
public class ReturnStayPlanService {

    private final Clock clock;

    public ReturnStayPlanService() {
        this(Clock.systemUTC());
    }

    public ReturnStayPlanService(Clock clock) {
        this.clock = clock;
    }

    public ReturnStayPlanEntity create(Long ownerUserId, ReturnStayPlanCreateRequest request) {
        throw notImplemented("create");
    }

    public ReturnStayPlanEntity getForOwner(Long ownerUserId, UUID planId) {
        throw notImplemented("getForOwner");
    }

    public ReturnStayPlanEntity update(
            Long ownerUserId, UUID planId, Long version, ReturnStayPlanCreateRequest request) {
        throw notImplemented("update");
    }

    public void delete(Long ownerUserId, UUID planId) {
        throw notImplemented("delete");
    }

    public List<ReturnStayPlanEntity> list(Long ownerUserId, boolean includeEnded, int page, int size) {
        throw notImplemented("list");
    }

    public boolean visibleToMember(Long viewerId, Long ownerId, Long teamId, UUID planId) {
        throw notImplemented("visibleToMember");
    }

    public int purgeExpiredPlans() {
        throw notImplemented("purgeExpiredPlans");
    }

    public int deleteAllForOwner(Long ownerUserId) {
        throw notImplemented("deleteAllForOwner");
    }

    public Map<Long, List<TeamPlanView>> listVisiblePlansForMembers(
            Long viewerId, Long teamId, List<Long> memberIds) {
        throw notImplemented("listVisiblePlansForMembers");
    }

    void validateCreateLimit(long activeAndUpcomingCount) {
        throw notImplemented("validateCreateLimit");
    }

    void validateActiveUpdate(
            ReturnStayPlanEntity current, ReturnStayPlanCreateRequest requested) {
        throw notImplemented("validateActiveUpdate");
    }

    DisplayStatus resolveStatus(LocalDate startDate, LocalDate endDate, String timezone) {
        throw notImplemented("resolveStatus");
    }

    enum DisplayStatus {
        UPCOMING,
        ACTIVE,
        ENDED
    }

    /** TEAM向け公開DTOの契約骨格。公開先とversionは意図的に含めない。 */
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
            String status) {
    }

    private UnsupportedOperationException notImplemented(String operation) {
        return new UnsupportedOperationException(
                "F02.11 は試練設置中で未実装です: " + operation + " / " + clock);
    }
}
