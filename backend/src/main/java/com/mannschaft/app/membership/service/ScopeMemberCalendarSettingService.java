package com.mannschaft.app.membership.service;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.dto.MemberCalendarColorResponse;
import com.mannschaft.app.membership.entity.ScopeMemberCalendarSettingEntity;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.repository.ScopeMemberCalendarSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 色のoverride/reset。認可はcontrollerが既存のcheckAdminOrAboveで行う。 */
@Service
@RequiredArgsConstructor
public class ScopeMemberCalendarSettingService {
    private static final List<String> PALETTE = List.of(
            "#2563EB", "#DC2626", "#16A34A", "#9333EA", "#EA580C", "#0891B2", "#BE123C", "#4F46E5");

    private final ScopeMemberCalendarSettingRepository repository;
    private final MemberQueryDispatcher memberQueryDispatcher;

    @Transactional
    public MemberCalendarColorResponse override(ScopeType scopeType, Long scopeId, Long userId, String color) {
        assertActiveMember(scopeType, scopeId, userId);
        var setting = repository.findByScopeTypeAndScopeIdAndUserId(scopeType, scopeId, userId)
                .orElseGet(() -> ScopeMemberCalendarSettingEntity.builder()
                        .scopeType(scopeType).scopeId(scopeId).userId(userId).calendarColor(color).build());
        setting.updateCalendarColor(color);
        repository.save(setting);
        return new MemberCalendarColorResponse(userId, color, true);
    }

    @Transactional
    public MemberCalendarColorResponse reset(ScopeType scopeType, Long scopeId, Long userId) {
        assertActiveMember(scopeType, scopeId, userId);
        repository.deleteByScopeTypeAndScopeIdAndUserId(scopeType, scopeId, userId);
        return new MemberCalendarColorResponse(userId, fallback(scopeType, scopeId, userId), false);
    }

    public static String fallback(ScopeType scopeType, Long scopeId, Long userId) {
        int index = Math.floorMod(java.util.Objects.hash(scopeType, scopeId, userId), PALETTE.size());
        return PALETTE.get(index);
    }

    private void assertActiveMember(ScopeType scopeType, Long scopeId, Long userId) {
        boolean present = memberQueryDispatcher.queryMembers(scopeId, scopeType, null).stream()
                .anyMatch(member -> member.userId().equals(userId));
        if (!present) throw new IllegalArgumentException("対象者は有効なスコープメンバーである必要があります");
    }
}
