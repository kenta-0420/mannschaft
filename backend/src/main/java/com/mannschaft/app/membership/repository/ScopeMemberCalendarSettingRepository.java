package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.ScopeMemberCalendarSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScopeMemberCalendarSettingRepository extends JpaRepository<ScopeMemberCalendarSettingEntity, UUID> {
    Optional<ScopeMemberCalendarSettingEntity> findByScopeTypeAndScopeIdAndUserId(
            ScopeType scopeType, Long scopeId, Long userId);

    List<ScopeMemberCalendarSettingEntity> findByScopeTypeAndScopeIdAndUserIdIn(
            ScopeType scopeType, Long scopeId, Collection<Long> userIds);

    void deleteByScopeTypeAndScopeIdAndUserId(ScopeType scopeType, Long scopeId, Long userId);

    void deleteByUserId(Long userId);
}
