package com.mannschaft.app.publicview.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.UpdatePublicSettingsRequest;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F19.1 Phase 7: チーム / 組織の公開設定（タイムライン投稿 / イベント）更新サービス。
 *
 * <p>設計書: docs/features/F19.1_public_pages_identity_disclosure.md §6.8 Phase 7</p>
 *
 * <p><strong>クロスドメイン参照について:</strong>
 * 本サービスは publicview → team / organization のクロスドメイン参照を行う。
 * CLAUDE.md 原則5 に基づき、将来はイベント駆動化候補として記録する。</p>
 *
 * <p><strong>権限チェック:</strong>
 * Controller の {@code @PreAuthorize("hasRole('ADMIN') or hasRole('SYSTEM_ADMIN')")} に委ねる。
 * Service 層ではスコープ内のチーム / 組織の存在確認のみ実施する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPublicSettingsService {

    // TODO: publicview → team のクロスドメイン参照。将来はイベント駆動化候補。
    private final TeamRepository teamRepository;
    // TODO: publicview → organization のクロスドメイン参照。将来はイベント駆動化候補。
    private final OrganizationRepository organizationRepository;

    /**
     * チームの公開設定（タイムライン投稿 / イベント）を更新する。
     *
     * @param teamId      対象チーム ID
     * @param operatorId  操作ユーザー ID（ログ記録用）
     * @param req         更新リクエスト
     * @throws BusinessException チームが存在しないか論理削除済み（PUBLIC_001、404）
     */
    @Transactional
    public void updateTeamPublicSettings(Long teamId, Long operatorId, UpdatePublicSettingsRequest req) {
        // TODO: publicview → team クロスドメイン参照
        TeamEntity team = teamRepository.findById(teamId)
                .filter(t -> t.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));

        team.updateTimelinePostsPublic(req.getTimelinePostsPublic());
        team.updatePublicEventsEnabled(req.getPublicEventsEnabled());
        teamRepository.save(team);

        log.info("チーム公開設定更新: teamId={}, timelinePostsPublic={}, publicEventsEnabled={}, operatorId={}",
                teamId, req.getTimelinePostsPublic(), req.getPublicEventsEnabled(), operatorId);
    }

    /**
     * 組織の公開設定（タイムライン投稿 / イベント）を更新する。
     *
     * @param organizationId 対象組織 ID
     * @param operatorId     操作ユーザー ID（ログ記録用）
     * @param req            更新リクエスト
     * @throws BusinessException 組織が存在しないか論理削除済み（PUBLIC_001、404）
     */
    @Transactional
    public void updateOrganizationPublicSettings(Long organizationId, Long operatorId, UpdatePublicSettingsRequest req) {
        // TODO: publicview → organization クロスドメイン参照
        OrganizationEntity org = organizationRepository.findById(organizationId)
                .filter(o -> o.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(PublicViewErrorCode.PUBLIC_001));

        org.updatePublicEventsEnabled(req.getPublicEventsEnabled());
        org.updateTimelinePostsPublic(req.getTimelinePostsPublic());
        organizationRepository.save(org);

        log.info("組織公開設定更新: organizationId={}, timelinePostsPublic={}, publicEventsEnabled={}, operatorId={}",
                organizationId, req.getTimelinePostsPublic(), req.getPublicEventsEnabled(), operatorId);
    }
}
