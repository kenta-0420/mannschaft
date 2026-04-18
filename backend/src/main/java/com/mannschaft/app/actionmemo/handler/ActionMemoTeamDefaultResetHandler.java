package com.mannschaft.app.actionmemo.handler;

import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.team.event.TeamMemberRemovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * F02.5 Phase 3: チームメンバー脱退時の行動メモ設定クリアハンドラー。
 *
 * <p>ユーザーがチームから脱退した場合、そのチームが {@code default_post_team_id} として
 * 設定されていれば NULL に自動リセットする（設計書 §4.3.4）。</p>
 *
 * <p>フロントエンドは設定画面再訪時に {@code ?default_team_reset=1} クエリパラメータで
 * 「デフォルトチームがリセットされた」バナーを1度だけ表示する（フロントエンド実装）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActionMemoTeamDefaultResetHandler {

    private final UserActionMemoSettingsRepository settingsRepository;

    /**
     * チームメンバー脱退イベントを受け取り、default_post_team_id を NULL にリセットする。
     *
     * @param event チームメンバー脱退イベント
     */
    @EventListener
    @Transactional
    public void onTeamMemberRemoved(TeamMemberRemovedEvent event) {
        Optional<UserActionMemoSettingsEntity> settingsOpt =
                settingsRepository.findById(event.getUserId());

        settingsOpt.ifPresent(settings -> {
            if (Objects.equals(settings.getDefaultPostTeamId(), event.getTeamId())) {
                settings.setDefaultPostTeamId(null);
                settingsRepository.save(settings);
                log.info("行動メモ設定 デフォルト投稿先チームをリセット: userId={}, teamId={}",
                        event.getUserId(), event.getTeamId());
            }
        });
    }
}
