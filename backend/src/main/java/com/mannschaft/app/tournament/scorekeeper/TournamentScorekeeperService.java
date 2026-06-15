package com.mannschaft.app.tournament.scorekeeper;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import com.mannschaft.app.tournament.scorekeeper.dto.ScorekeeperResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 大会スコアキーパー指名の管理サービス（F08.7 項目③）。
 *
 * <p>主催組織 ADMIN（または SYSTEM_ADMIN）が、当該大会のスコア入力を許可するユーザーを指名・解除・一覧する。
 * 指名されたユーザーは {@link TournamentFixtureAccessService#canEnterScore} の条件②として扱われ、
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

    /**
     * 退会済み・存在しないユーザーの表示名フォールバック。
     * バッチ解決 {@link NameResolverService#resolveUserDisplayNames} は該当なしの ID を map に含めないため、
     * 単一解決 {@link NameResolverService#resolveUserDisplayName}（"不明なユーザー" を返す）と同じ値で補う。
     */
    private static final String UNKNOWN_USER_NAME = "不明なユーザー";

    private final TournamentScorekeeperRepository scorekeeperRepository;
    private final TournamentRepository tournamentRepository;
    private final AccessControlService accessControlService;
    private final NameResolverService nameResolverService;

    /**
     * 大会の指名スコアキーパー一覧を取得する（主催組織 ADMIN）。
     *
     * @throws BusinessException TOURNAMENT_NOT_FOUND（404）／SCOREKEEPER_MANAGE_FORBIDDEN（403）
     */
    public List<ScorekeeperResponse> listScorekeepers(Long organizationId, Long tournamentId, Long actorUserId) {
        findTournamentInOrgOrThrow(organizationId, tournamentId);
        requireOrganizerAdmin(actorUserId, organizationId);

        List<TournamentScorekeeperEntity> entities =
                scorekeeperRepository.findByTournamentIdOrderByCreatedAtAsc(tournamentId);

        // userId 集合をバッチ解決（N+1 回避）。退会済み等は map に含まれないため後段でフォールバックする。
        Set<Long> userIds = entities.stream()
                .map(TournamentScorekeeperEntity::getUserId)
                .collect(Collectors.toSet());
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(userIds);

        return entities.stream()
                .map(e -> ScorekeeperResponse.of(e, resolveName(nameMap, e.getUserId())))
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
        TournamentScorekeeperEntity entity = scorekeeperRepository
                .findByTournamentIdAndUserId(tournamentId, targetUserId)
                .orElseGet(() -> scorekeeperRepository.save(TournamentScorekeeperEntity.builder()
                        .tournamentId(tournamentId)
                        .userId(targetUserId)
                        .createdBy(actorUserId)
                        .build()));

        // 1 件分の表示名を解決（退会済み等は既定フォールバック）。
        String displayName = nameResolverService.resolveUserDisplayName(targetUserId);
        return ScorekeeperResponse.of(entity, displayName);
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

    /** バッチ解決結果から表示名を取り出す。map に無い（退会済み等）場合は既定フォールバック。 */
    private String resolveName(Map<Long, String> nameMap, Long userId) {
        return nameMap.getOrDefault(userId, UNKNOWN_USER_NAME);
    }

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
