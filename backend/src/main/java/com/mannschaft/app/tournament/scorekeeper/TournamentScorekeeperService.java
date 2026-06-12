package com.mannschaft.app.tournament.scorekeeper;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.scorekeeper.dto.ScorekeeperResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 大会スコアキーパー指名の管理サービス（F08.7 項目③）。
 *
 * <p>主催組織 ADMIN（または SYSTEM_ADMIN）が、当該大会のスコア入力を許可するユーザーを指名・解除・一覧する。
 * 指名されたユーザーは {@link TournamentMatchAccessService#canEnterScore} の条件②として扱われ、
 * 当該大会のスコア入力系 EP を操作できるようになる。</p>
 *
 * <h2>認可</h2>
 * <ul>
 *   <li>指名の一覧／追加／削除はすべて主催組織 ADMIN / SYSTEM_ADMIN のみ。それ以外は 403
 *       （{@code SCOREKEEPER_MANAGE_FORBIDDEN}）。</li>
 *   <li>存在しない／他組織の大会・指名は一律 404（IDOR 対策）。</li>
 * </ul>
 *
 * <p>Controller 側の {@code @PreAuthorize("@accessGuard.isScopeAdmin(...,'ORGANIZATION')")} に加えて、
 * 本サービスでも大会の組織帰属と主催組織 ADMIN を再検証する二重防御とする（per-scope ロールは JWT に無い）。</p>
 *
 * <p>設計: docs/features/F08.7_standings_ui（項目③ スコア入力編集権限の細分化）</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TournamentScorekeeperService {

    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";

    private final TournamentScorekeeperRepository scorekeeperRepository;
    private final TournamentRepository tournamentRepository;
    private final AccessControlService accessControlService;

    /**
     * 大会の指名スコアキーパー一覧を取得する（主催組織 ADMIN）。
     *
     * @throws BusinessException TOURNAMENT_NOT_FOUND（404）／SCOREKEEPER_MANAGE_FORBIDDEN（403）
     */
    public List<ScorekeeperResponse> listScorekeepers(Long organizationId, Long tournamentId, Long actorUserId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(actorUserId, organizationId);

        return scorekeeperRepository.findByTournamentIdOrderByCreatedAtAsc(tournamentId).stream()
                .map(ScorekeeperResponse::of)
                .toList();
    }

    /**
     * スコアキーパーを指名する（主催組織 ADMIN）。既に指名済みなら冪等に既存を返す。
     *
     * @throws BusinessException TOURNAMENT_NOT_FOUND（404）／SCOREKEEPER_MANAGE_FORBIDDEN（403）
     */
    @Transactional
    public ScorekeeperResponse addScorekeeper(Long organizationId, Long tournamentId,
                                              Long actorUserId, Long targetUserId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(actorUserId, organizationId);

        // 冪等: 既に指名済みなら既存を返す（UNIQUE(tournament_id,user_id) 制約違反による 500 化を防ぐ）
        return scorekeeperRepository.findByTournamentIdAndUserId(tournamentId, targetUserId)
                .map(ScorekeeperResponse::of)
                .orElseGet(() -> {
                    TournamentScorekeeperEntity entity = TournamentScorekeeperEntity.builder()
                            .tournamentId(tournamentId)
                            .userId(targetUserId)
                            .createdBy(actorUserId)
                            .build();
                    return ScorekeeperResponse.of(scorekeeperRepository.save(entity));
                });
    }

    /**
     * スコアキーパー指名を解除する（主催組織 ADMIN・物理削除）。
     *
     * @throws BusinessException TOURNAMENT_NOT_FOUND（404）／SCOREKEEPER_MANAGE_FORBIDDEN（403）／
     *                           SCOREKEEPER_NOT_FOUND（404）
     */
    @Transactional
    public void removeScorekeeper(Long organizationId, Long tournamentId, Long actorUserId, UUID scorekeeperId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(actorUserId, organizationId);

        TournamentScorekeeperEntity entity = scorekeeperRepository
                .findByIdAndTournamentId(scorekeeperId, tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.SCOREKEEPER_NOT_FOUND));
        scorekeeperRepository.delete(entity);
    }

    // ========================================================================
    // ヘルパー
    // ========================================================================

    /** 大会が指定組織に属することを確認して返す。属さない／存在しない場合は 404（IDOR 対策）。 */
    private TournamentEntity findTournamentInOrgOrThrow(Long organizationId, Long tournamentId) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
        if (!organizationId.equals(tournament.getOrganizationId())) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
        return tournament;
    }

    /** 主催組織 ADMIN（または SYSTEM_ADMIN）であることを要求する（第一防御）。 */
    private void requireOrganizerAdmin(Long actorUserId, Long organizationId) {
        if (accessControlService.isSystemAdmin(actorUserId)) {
            return;
        }
        if (!accessControlService.isAdminOrAbove(actorUserId, organizationId, SCOPE_ORGANIZATION)) {
            throw new BusinessException(TournamentErrorCode.SCOREKEEPER_MANAGE_FORBIDDEN);
        }
    }
}
