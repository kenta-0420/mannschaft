package com.mannschaft.app.team.service;

import com.mannschaft.app.team.dto.TeamShiftSettingsResponse;
import com.mannschaft.app.team.dto.UpdateTeamShiftSettingsRequest;
import com.mannschaft.app.team.entity.TeamShiftSettingsEntity;
import com.mannschaft.app.team.repository.TeamShiftSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チームシフト設定サービス。リマインド間隔カスタマイズの取得・更新・初期化を担う。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TeamShiftSettingsService {

    private final TeamShiftSettingsRepository settingsRepository;

    /**
     * チームシフト設定を取得する。
     * 設定が存在しない場合はデフォルト値で自動作成する。
     */
    @Transactional
    public TeamShiftSettingsResponse getSettings(Long teamId) {
        TeamShiftSettingsEntity settings = settingsRepository.findByTeamId(teamId)
                .orElseGet(() -> {
                    log.info("チームシフト設定が存在しないためデフォルト作成: teamId={}", teamId);
                    return settingsRepository.save(TeamShiftSettingsEntity.createDefault(teamId));
                });
        return TeamShiftSettingsResponse.from(settings);
    }

    /**
     * チームシフト設定を更新する。
     * 設定が存在しない場合は作成してから更新する。
     */
    @Transactional
    public TeamShiftSettingsResponse updateSettings(Long teamId, UpdateTeamShiftSettingsRequest request) {
        TeamShiftSettingsEntity settings = settingsRepository.findByTeamId(teamId)
                .orElseGet(() -> TeamShiftSettingsEntity.createDefault(teamId));
        settings.update(
                request.isReminder48hEnabled(),
                request.isReminder24hEnabled(),
                request.isReminder12hEnabled());
        return TeamShiftSettingsResponse.from(settingsRepository.save(settings));
    }

    /**
     * チーム作成時にデフォルト設定を自動生成する。
     * TeamService のチーム作成フローから呼ばれる。
     */
    @Transactional
    public void initializeDefaultSettings(Long teamId) {
        if (settingsRepository.findByTeamId(teamId).isEmpty()) {
            settingsRepository.save(TeamShiftSettingsEntity.createDefault(teamId));
            log.info("チームシフト設定を初期化: teamId={}", teamId);
        }
    }
}
