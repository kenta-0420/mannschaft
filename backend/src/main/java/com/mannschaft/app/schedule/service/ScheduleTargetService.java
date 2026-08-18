package com.mannschaft.app.schedule.service;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.schedule.ScheduleTargetMode;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleTargetEntity;
import com.mannschaft.app.schedule.repository.ScheduleTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 予定対象者の構文・所属検証と置換を一つのトランザクションで行う。 */
@Service
@RequiredArgsConstructor
public class ScheduleTargetService {

    private static final int MAX_TARGETS = 500;

    private final ScheduleTargetRepository targetRepository;
    private final MemberQueryDispatcher memberQueryDispatcher;

    @Transactional
    public void replaceForCreate(ScheduleEntity schedule, String scopeType, Long scopeId,
                                 String requestedMode, List<Long> requestedUserIds) {
        ScheduleTargetMode mode = parseMode(requestedMode, ScheduleTargetMode.ALL_MEMBERS);
        replace(schedule, scopeType, scopeId, mode, requestedUserIds);
    }

    @Transactional
    public void replaceForUpdate(ScheduleEntity schedule, String scopeType, Long scopeId,
                                 String requestedMode, List<Long> requestedUserIds) {
        if (requestedMode == null && requestedUserIds == null) {
            return;
        }
        ScheduleTargetMode mode = requestedMode == null ? schedule.getTargetMode()
                : parseMode(requestedMode, null);
        replace(schedule, scopeType, scopeId, mode, requestedUserIds);
    }

    private void replace(ScheduleEntity schedule, String scopeTypeText, Long scopeId,
                         ScheduleTargetMode mode, List<Long> requestedUserIds) {
        if (schedule.isPersonal()) {
            if (mode != ScheduleTargetMode.ALL_MEMBERS || (requestedUserIds != null && !requestedUserIds.isEmpty())) {
                throw new IllegalArgumentException("個人予定には対象者を指定できません");
            }
            schedule.updateTargetMode(ScheduleTargetMode.ALL_MEMBERS);
            return;
        }

        List<Long> ids = requestedUserIds == null ? List.of() : requestedUserIds;
        if (mode == ScheduleTargetMode.ALL_MEMBERS) {
            if (!ids.isEmpty()) {
                throw new IllegalArgumentException("ALL_MEMBERS では対象者を指定できません");
            }
            targetRepository.deleteByScheduleId(schedule.getId());
            schedule.updateTargetMode(mode);
            return;
        }

        Set<Long> uniqueIds = new LinkedHashSet<>(ids);
        if (uniqueIds.size() != ids.size() || uniqueIds.isEmpty() || uniqueIds.size() > MAX_TARGETS
                || uniqueIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("対象者は重複なしで1〜500名を指定してください");
        }
        ScopeType scopeType = parseScopeType(scopeTypeText);
        Set<Long> activeUserIds = memberQueryDispatcher.queryMembers(scopeId, scopeType, null).stream()
                .map(com.mannschaft.app.membership.dto.MemberDto::userId)
                .collect(java.util.stream.Collectors.toSet());
        if (!activeUserIds.containsAll(uniqueIds)) {
            // 所属外・退会済みのどちらかを詳細化せず、スコープの名簿を漏らさない。
            throw new IllegalArgumentException("対象者は有効なスコープメンバーである必要があります");
        }

        targetRepository.deleteByScheduleId(schedule.getId());
        targetRepository.saveAll(uniqueIds.stream()
                .map(userId -> ScheduleTargetEntity.builder().scheduleId(schedule.getId()).userId(userId).build())
                .toList());
        schedule.updateTargetMode(mode);
    }

    private static ScheduleTargetMode parseMode(String value, ScheduleTargetMode defaultValue) {
        if (value == null) {
            if (defaultValue == null) throw new IllegalArgumentException("対象モードを指定してください");
            return defaultValue;
        }
        try {
            return ScheduleTargetMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("不正な対象モードです");
        }
    }

    private static ScopeType parseScopeType(String scopeType) {
        try {
            return ScopeType.valueOf(scopeType);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("共有予定のスコープが不正です");
        }
    }
}
