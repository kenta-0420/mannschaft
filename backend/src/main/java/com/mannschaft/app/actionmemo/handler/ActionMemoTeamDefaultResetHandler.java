package com.mannschaft.app.actionmemo.handler;

import com.mannschaft.app.actionmemo.entity.UserActionMemoSettingsEntity;
import com.mannschaft.app.actionmemo.repository.UserActionMemoSettingsRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
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
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。チーム脱退に伴う行動メモ既定値のリセット。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
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
