package com.mannschaft.app.schedule.service;

import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.query.MemberQueryDispatcher;
import com.mannschaft.app.membership.repository.ScopeMemberCalendarSettingRepository;
import com.mannschaft.app.schedule.ScheduleTargetMode;
import com.mannschaft.app.schedule.dto.ScheduleTargetResponse;
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
import java.util.Map;
import java.util.HashMap;
import java.util.Collection;
import java.util.function.Function;
import com.mannschaft.app.membership.dto.MemberDto;
import com.mannschaft.app.membership.entity.ScopeMemberCalendarSettingEntity;
import com.mannschaft.app.membership.service.ScopeMemberCalendarSettingService;

/** 予定対象者の構文・所属検証と置換を一つのトランザクションで行う。 */
@Service
@RequiredArgsConstructor
public class ScheduleTargetService {

    private static final int MAX_TARGETS = 500;

    private final ScheduleTargetRepository targetRepository;
    private final MemberQueryDispatcher memberQueryDispatcher;
    private final ScopeMemberCalendarSettingRepository calendarSettingRepository;

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

    /** 同一スコープの一覧を2本のバッチqueryで表示用対象者へ変換する。 */
    @Transactional(readOnly = true)
    public Map<Long, ScheduleTargetResponse> responsesForSchedules(
            Collection<ScheduleEntity> schedules, boolean revealMembers) {
        if (schedules.isEmpty()) return Map.of();
        List<Long> scheduleIds = schedules.stream().map(ScheduleEntity::getId).toList();
        Map<Long, List<Long>> targetIds = targetRepository.findByScheduleIdInOrderByScheduleIdAscUserIdAsc(scheduleIds)
                .stream().collect(java.util.stream.Collectors.groupingBy(
                        ScheduleTargetEntity::getScheduleId,
                        java.util.stream.Collectors.mapping(ScheduleTargetEntity::getUserId,
                                java.util.stream.Collectors.toList())));
        Map<ScopeKey, Map<Long, MemberDto>> membersByScope = new HashMap<>();
        for (ScheduleEntity schedule : schedules) {
            if (schedule.isPersonal()) continue;
            ScopeKey key = scopeKey(schedule);
            membersByScope.computeIfAbsent(key, ignored -> memberQueryDispatcher
                    .queryMembers(key.scopeId(), key.scopeType(), null).stream()
                    .collect(java.util.stream.Collectors.toMap(MemberDto::userId, Function.identity(), (a, b) -> a)));
        }
        Map<ScopeKey, Map<Long, String>> colorsByScope = new HashMap<>();
        for (Map.Entry<ScopeKey, Map<Long, MemberDto>> entry : membersByScope.entrySet()) {
            ScopeKey key = entry.getKey();
            Set<Long> requestedIds = schedules.stream()
                    .filter(schedule -> !schedule.isPersonal() && scopeKey(schedule).equals(key))
                    .filter(schedule -> schedule.getTargetMode() == ScheduleTargetMode.SELECTED_MEMBERS)
                    .flatMap(schedule -> targetIds.getOrDefault(schedule.getId(), List.of()).stream())
                    .collect(java.util.stream.Collectors.toSet());
            if (requestedIds.isEmpty()) continue;
            colorsByScope.put(key, calendarSettingRepository
                    .findByScopeTypeAndScopeIdAndUserIdIn(key.scopeType(), key.scopeId(), requestedIds).stream()
                    .collect(java.util.stream.Collectors.toMap(
                            ScopeMemberCalendarSettingEntity::getUserId,
                            ScopeMemberCalendarSettingEntity::getCalendarColor)));
        }

        Map<Long, ScheduleTargetResponse> result = new HashMap<>();
        for (ScheduleEntity schedule : schedules) {
            List<Long> ids = targetIds.getOrDefault(schedule.getId(), List.of());
            if (schedule.isPersonal()) {
                result.put(schedule.getId(), new ScheduleTargetResponse(
                        ScheduleTargetMode.ALL_MEMBERS.name(), 0, List.of()));
                continue;
            }
            ScopeKey key = scopeKey(schedule);
            Map<Long, MemberDto> members = membersByScope.getOrDefault(key, Map.of());
            int targetCount = schedule.getTargetMode() == ScheduleTargetMode.ALL_MEMBERS
                    ? members.size() : ids.size();
            if (!revealMembers || schedule.getTargetMode() == ScheduleTargetMode.ALL_MEMBERS) {
                result.put(schedule.getId(), new ScheduleTargetResponse(
                        schedule.getTargetMode().name(), targetCount, List.of()));
                continue;
            }
            Map<Long, String> colors = colorsByScope.getOrDefault(key, Map.of());
            List<ScheduleTargetResponse.TargetMember> targets = ids.stream().map(id -> {
                MemberDto member = members.get(id);
                return new ScheduleTargetResponse.TargetMember(id,
                        member == null ? null : member.displayName(),
                        member == null ? null : member.avatarUrl(),
                        colors.getOrDefault(id, ScopeMemberCalendarSettingService.fallback(
                                key.scopeType(), key.scopeId(), id)));
            }).toList();
            result.put(schedule.getId(), new ScheduleTargetResponse(
                    schedule.getTargetMode().name(), targetCount, targets));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public boolean isActiveScopeMember(ScheduleEntity schedule, Long userId) {
        if (schedule.isPersonal()) return java.util.Objects.equals(schedule.getUserId(), userId);
        ScopeKey key = scopeKey(schedule);
        return memberQueryDispatcher.queryMembers(key.scopeId(), key.scopeType(), null).stream()
                .anyMatch(member -> java.util.Objects.equals(member.userId(), userId));
    }

    /** 一覧の一括問い合わせで、指定ユーザーに割り当てられた予定だけを残す。 */
    @Transactional(readOnly = true)
    public Set<Long> assignedScheduleIds(Collection<ScheduleEntity> schedules, Long userId) {
        if (schedules.isEmpty()) return Set.of();
        Map<Long, Set<Long>> targets = targetRepository
                .findByScheduleIdInOrderByScheduleIdAscUserIdAsc(
                        schedules.stream().map(ScheduleEntity::getId).toList())
                .stream().collect(java.util.stream.Collectors.groupingBy(
                        ScheduleTargetEntity::getScheduleId,
                        java.util.stream.Collectors.mapping(ScheduleTargetEntity::getUserId,
                                java.util.stream.Collectors.toSet())));
        return schedules.stream()
                .filter(schedule -> schedule.getTargetMode() == ScheduleTargetMode.ALL_MEMBERS
                        || targets.getOrDefault(schedule.getId(), Set.of()).contains(userId))
                .map(ScheduleEntity::getId)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Transactional
    public void copyTargets(Long sourceScheduleId, Long targetScheduleId) {
        List<ScheduleTargetEntity> targets = targetRepository.findByScheduleIdOrderByUserIdAsc(sourceScheduleId);
        if (!targets.isEmpty()) {
            targetRepository.saveAll(targets.stream().map(target -> ScheduleTargetEntity.builder()
                    .scheduleId(targetScheduleId).userId(target.getUserId()).build()).toList());
        }
    }

    private static String resolveScopeType(ScheduleEntity schedule) {
        return schedule.isTeamScope() ? "TEAM" : "ORGANIZATION";
    }

    private static ScopeKey scopeKey(ScheduleEntity schedule) {
        return new ScopeKey(parseScopeType(resolveScopeType(schedule)),
                schedule.isTeamScope() ? schedule.getTeamId() : schedule.getOrganizationId());
    }

    private record ScopeKey(ScopeType scopeType, Long scopeId) {
    }
}
