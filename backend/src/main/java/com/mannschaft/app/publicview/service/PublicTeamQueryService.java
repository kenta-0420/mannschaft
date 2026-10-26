package com.mannschaft.app.publicview.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * F19.1 公開チームページ用クエリサービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.1 / §7.3</p>
 *
 * <p>F15.4 Phase 5-α の {@link com.mannschaft.app.team.service.TeamService#getPublicTeam(Long)}
 * は本クラスでは <strong>呼び出さない</strong>。既存メソッドは
 * {@link com.mannschaft.app.team.controller.PublicTeamController} 経由で
 * {@code GET /api/v1/public/teams/{id}} に紐づき動作不変で温存する（案 B 統合方針）。</p>
 *
 * <p>本サービスは F19.1 の posts/events 系 publicview Controller が「対象チームが PUBLIC か」を
 * 横断的に確認する用途で {@link TeamRepository#findPublicTeamById(Long)} のラッパーを提供する。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class PublicTeamQueryService {

    private final TeamRepository teamRepository;

    /**
     * 公開チームを取得する。PRIVATE / archived / 削除済 / 不在は
     * {@link PublicViewErrorCode#PUBLIC_001}（404 へ正規化）を投げる。
     *
     * @param teamId チーム ID
     * @return PUBLIC かつアクティブなチーム Entity
     */
    public TeamEntity requirePublicTeam(Long teamId) {
        return teamRepository.findPublicTeamById(teamId)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));
    }

    /**
     * 公開チームをオプショナルで取得する（404 を投げずに呼び出し側で分岐したい用途）。
     *
     * @param teamId チーム ID
     * @return PUBLIC かつアクティブなチーム Entity。条件不一致は空。
     */
    public Optional<TeamEntity> findPublicTeam(Long teamId) {
        return teamRepository.findPublicTeamById(teamId);
    }
}
