package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamBlockRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 招待の承諾・プレビューが team ドメインへ問い合わせる読み取り窓口（CMP-260826-1457）。
 *
 * <p>role ドメインのトランザクションから team の Repository を直接触らずに済むよう、結果だけを
 * プリミティブ（{@code boolean} / {@code String}）で返す。Entity は外に出さない。例外は投げない
 * （エラーの優先順位は呼び出し側の claim が決める）。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TeamInviteQueryService {

    private final TeamBlockRepository teamBlockRepository;
    private final TeamRepository teamRepository;

    /** 当該ユーザーがチームからブロックされているか。 */
    public boolean isBlocked(Long teamId, Long userId) {
        return teamBlockRepository.existsByTeamIdAndUserId(teamId, userId);
    }

    /** チーム名。存在しなければ null。 */
    public String findName(Long teamId) {
        return teamRepository.findById(teamId).map(TeamEntity::getName).orElse(null);
    }
}
