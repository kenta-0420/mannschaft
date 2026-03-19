package com.mannschaft.app.family;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.family.dto.PresenceIconRequest;
import com.mannschaft.app.family.dto.PresenceIconResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * プレゼンスアイコンサービス。チームごとのカスタムプレゼンスアイコン管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PresenceIconService {

    private final TeamPresenceIconRepository teamPresenceIconRepository;

    /**
     * チームのカスタムアイコン一覧を取得する。
     *
     * @param teamId チームID
     * @return アイコン一覧
     */
    public ApiResponse<List<PresenceIconResponse>> getIcons(Long teamId) {
        List<TeamPresenceIconEntity> icons = teamPresenceIconRepository.findByTeamId(teamId);
        List<PresenceIconResponse> responses = icons.stream()
                .map(i -> new PresenceIconResponse(i.getEventType().name(), i.getIcon()))
                .toList();
        return ApiResponse.of(responses);
    }

    /**
     * カスタムアイコンを一括設定する。
     *
     * @param teamId  チームID
     * @param userId  ユーザーID
     * @param request リクエスト
     * @return 更新後のアイコン一覧
     */
    @Transactional
    public ApiResponse<List<PresenceIconResponse>> updateIcons(Long teamId, Long userId, PresenceIconRequest request) {
        for (PresenceIconRequest.IconEntry entry : request.getIcons()) {
            EventType eventType = EventType.valueOf(entry.getEventType().toUpperCase());

            teamPresenceIconRepository.findByTeamIdAndEventType(teamId, eventType)
                    .ifPresentOrElse(
                            existing -> existing.updateIcon(entry.getIcon(), userId),
                            () -> teamPresenceIconRepository.save(TeamPresenceIconEntity.builder()
                                    .teamId(teamId)
                                    .eventType(eventType)
                                    .icon(entry.getIcon())
                                    .updatedBy(userId)
                                    .build())
                    );
        }

        return getIcons(teamId);
    }
}
