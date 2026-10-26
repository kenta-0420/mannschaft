package com.mannschaft.app.family.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.family.EventType;
import com.mannschaft.app.family.dto.PresenceIconRequest;
import com.mannschaft.app.family.dto.PresenceIconResponse;
import com.mannschaft.app.family.entity.TeamPresenceIconEntity;
import com.mannschaft.app.family.repository.TeamPresenceIconRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PresenceIconService {

    private static final String SCOPE_TYPE_TEAM = "TEAM";
    private final TeamPresenceIconRepository teamPresenceIconRepository;
    private final AccessControlService accessControlService;

    public ApiResponse<List<PresenceIconResponse>> getIcons(Long teamId, Long actorUserId) {
        // 認可根治 Wave2-2C: 閲覧=checkMembership。非メンバーは403
        accessControlService.checkMembership(actorUserId, teamId, SCOPE_TYPE_TEAM);
        List<TeamPresenceIconEntity> icons = teamPresenceIconRepository.findByTeamId(teamId);
        return ApiResponse.of(icons.stream().map(i -> new PresenceIconResponse(i.getEventType().name(), i.getIcon())).toList());
    }

    @Transactional
    public ApiResponse<List<PresenceIconResponse>> updateIcons(Long teamId, Long userId, PresenceIconRequest request) {
        // 認可根治 Wave2-2C: アイコン設定はADMIN用EPのためcheckAdminOrAbove
        accessControlService.checkAdminOrAbove(userId, teamId, SCOPE_TYPE_TEAM);
        for (PresenceIconRequest.IconEntry entry : request.getIcons()) {
            EventType eventType = EventType.valueOf(entry.getEventType().toUpperCase());
            teamPresenceIconRepository.findByTeamIdAndEventType(teamId, eventType)
                    .ifPresentOrElse(
                            existing -> existing.updateIcon(entry.getIcon(), userId),
                            () -> teamPresenceIconRepository.save(TeamPresenceIconEntity.builder()
                                    .teamId(teamId).eventType(eventType).icon(entry.getIcon()).updatedBy(userId).build()));
        }
        return getIcons(teamId, userId);
    }
}
