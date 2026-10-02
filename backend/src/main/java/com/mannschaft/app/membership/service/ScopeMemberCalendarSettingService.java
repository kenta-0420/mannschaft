package com.mannschaft.app.membership.service;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.MembershipErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.dto.MemberCalendarColorResponse;
import com.mannschaft.app.membership.entity.ScopeMemberCalendarSettingEntity;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.repository.ScopeMemberCalendarSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/** 色のoverride/reset。認可はcontrollerが既存のcheckAdminOrAboveで行う。 */
@Service
@RequiredArgsConstructor
public class ScopeMemberCalendarSettingService {
    private static final List<String> PALETTE = List.of(
            "#2563EB", "#7C3AED", "#DB2777", "#DC2626", "#EA580C", "#CA8A04",
            "#16A34A", "#0D9488", "#0891B2", "#4F46E5", "#64748B", "#9333EA");

    private final ScopeMemberCalendarSettingRepository repository;
    private final MemberQueryDispatcher memberQueryDispatcher;

    @Transactional
    public MemberCalendarColorResponse override(ScopeType scopeType, Long scopeId, Long userId, String color) {
        String normalizedColor = normalizePaletteColor(color);
        assertActiveMember(scopeType, scopeId, userId);
        var setting = repository.findByScopeTypeAndScopeIdAndUserId(scopeType, scopeId, userId)
                .orElseGet(() -> ScopeMemberCalendarSettingEntity.builder()
                        .scopeType(scopeType).scopeId(scopeId).userId(userId).calendarColor(normalizedColor).build());
        setting.updateCalendarColor(normalizedColor);
        repository.save(setting);
        return new MemberCalendarColorResponse(userId, normalizedColor, true);
    }

    @Transactional
    public MemberCalendarColorResponse reset(ScopeType scopeType, Long scopeId, Long userId) {
        assertActiveMember(scopeType, scopeId, userId);
        repository.deleteByScopeTypeAndScopeIdAndUserId(scopeType, scopeId, userId);
        return new MemberCalendarColorResponse(userId, fallback(scopeType, scopeId, userId), false);
    }

    public static String fallback(ScopeType scopeType, Long scopeId, Long userId) {
        int index = Math.floorMod(
                java.util.Objects.hash(scopeType.name(), scopeId, userId), PALETTE.size());
        return PALETTE.get(index);
    }

    @Transactional(readOnly = true)
    public Map<Long, String> resolveColors(
            ScopeType scopeType, Long scopeId, Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> overrides = repository
                .findByScopeTypeAndScopeIdAndUserIdIn(scopeType, scopeId, userIds)
                .stream()
                .collect(Collectors.toMap(
                        ScopeMemberCalendarSettingEntity::getUserId,
                        ScopeMemberCalendarSettingEntity::getCalendarColor));
        return userIds.stream().distinct().collect(Collectors.toMap(
                userId -> userId,
                userId -> overrides.getOrDefault(userId, fallback(scopeType, scopeId, userId))));
    }

    private void assertActiveMember(ScopeType scopeType, Long scopeId, Long userId) {
        boolean present = memberQueryDispatcher.queryMembers(scopeId, scopeType, null).stream()
                .anyMatch(member -> member.userId().equals(userId));
        if (!present) throw new BusinessException(MembershipErrorCode.MEMBERSHIP_024);
    }

    private static String normalizePaletteColor(String color) {
        String normalized = color == null ? null : color.toUpperCase(Locale.ROOT);
        if (!PALETTE.contains(normalized)) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_023);
        }
        return normalized;
    }
}
