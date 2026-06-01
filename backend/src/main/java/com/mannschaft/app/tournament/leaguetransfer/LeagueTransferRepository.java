package com.mannschaft.app.tournament.leaguetransfer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * リーグ移籍リポジトリ（F08.7.1 / 03）。
 *
 * <p>from / to の 2 組織をまたぐため単一 {@code organization_id} でテナント絞りできない。
 * よって {@code AbstractTenantAwareRepository} ではなく素の {@link JpaRepository} を継承し、
 * 用途別の index（from_org / to_org / team）に対応する派生クエリで引く（§3.1）。
 * IDOR 対策のスコープ検証は Service 層で from/to org・team の帰属を都度検証する。</p>
 */
public interface LeagueTransferRepository extends JpaRepository<LeagueTransferEntity, UUID> {

    /**
     * 受信箱（受け入れ側 org が {@code to_organization_id} の DISPATCHED を引く・§6）。
     */
    List<LeagueTransferEntity> findByToOrganizationIdAndStatusOrderByCreatedAtDesc(
            Long toOrganizationId, LeagueTransferStatus status);

    /**
     * 受信箱（direction 絞り込みつき・§6）。
     */
    List<LeagueTransferEntity> findByToOrganizationIdAndDirectionAndStatusOrderByCreatedAtDesc(
            Long toOrganizationId, LeagueTransferDirection direction, LeagueTransferStatus status);

    /**
     * 送り出し側の進捗一覧（{@code from_organization_id}）。
     */
    List<LeagueTransferEntity> findByFromOrganizationIdOrderByCreatedAtDesc(Long fromOrganizationId);

    /**
     * チーム側の状況閲覧（{@code team_id}・閲覧のみ・§6）。
     */
    List<LeagueTransferEntity> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    /**
     * 二重起票チェック（UNIQUE(team_id, season, direction) のアプリ側事前判定・§7）。
     */
    Optional<LeagueTransferEntity> findByTeamIdAndSeasonAndDirection(
            Long teamId, String season, LeagueTransferDirection direction);
}
