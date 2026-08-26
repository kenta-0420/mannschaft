package com.mannschaft.app.tournament.scorekeeper;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.entity.TournamentFixtureEntity;
import com.mannschaft.app.tournament.entity.TournamentParticipantEntity;
import com.mannschaft.app.tournament.repository.TournamentFixtureRepository;
import com.mannschaft.app.tournament.repository.TournamentParticipantRepository;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * F08.7 順位UI 項目③ — スコア入力の編集権限を細分化する認可集約サービス。
 *
 * <p>従来スコア入力系 EP（{@code FixtureController} の updateScore / player-stats / status /
 * batch / import）は主催組織 ADMIN（{@code @accessGuard.isScopeAdmin(...,'ORGANIZATION')}）のみが
 * 操作可能だった。本サービスはこれを次の <strong>3-way</strong> に拡張する（殿の確定案）:</p>
 *
 * <ol>
 *   <li><strong>ORG 管理者</strong>: 大会の主催組織で ADMIN/DEPUTY_ADMIN（または SYSTEM_ADMIN）。</li>
 *   <li><strong>指名スコアキーパー</strong>: 当該大会の {@code tournament_scorekeepers} に登録されたユーザー。</li>
 *   <li><strong>参加チーム ADMIN</strong>: その試合の参加チーム（home/away participant の teamId）の
 *       いずれかで ADMIN/DEPUTY_ADMIN（自チームが関与する試合のみ）。</li>
 * </ol>
 *
 * <p>per-scope ロールは JWT に無いため、SpEL（{@code @PreAuthorize}）の SpEL 単独では
 * 「matchId → participant → teamId」の解決ができない。よって判定本体を本サービスに集約し、
 * {@code @accessGuard.canEnterTournamentScore(...)} から委譲する（method-security 維持）。
 * SYSTEM_ADMIN の無条件許可は {@link AccessControlService#isSystemAdmin} に従う。</p>
 *
 * <h3>batch / import の混在方針</h3>
 * <p>節（matchday）一括入力は複数試合を横断するため、参加チーム ADMIN だと「自チーム関与分のみ可・他試合不可」
 * という混在が生じ、部分適用なしの一括トランザクション（{@code FixtureService.batchUpdateScores}）と整合しない。
 * よって <strong>batch / import は ORG 管理者 or 指名スコアキーパーのみ</strong>に限定する
 * （{@link #canEnterScoreTournamentWide}）。参加チーム ADMIN は単発の {@code updateScore} で自チーム関与試合を入力する。</p>
 *
 * <p>設計: docs/features/F08.7_standings_ui（項目③ スコア入力編集権限の細分化）</p>
 */
@Service("tournamentScoreGuard")
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TournamentFixtureAccessService {

    private static final String SCOPE_ORGANIZATION = "ORGANIZATION";
    private static final String SCOPE_TEAM = "TEAM";

    private final AccessControlService accessControlService;
    private final TournamentRepository tournamentRepository;
    private final TournamentScorekeeperRepository scorekeeperRepository;
    private final TournamentFixtureRepository matchRepository;
    private final TournamentParticipantRepository participantRepository;

    /**
     * 特定の試合に対するスコア入力可否を判定する（3-way・項目③）。
     *
     * <ol>
     *   <li>大会の主催組織で ADMIN 以上（SYSTEM_ADMIN 含む）なら true。</li>
     *   <li>当該大会の指名スコアキーパーなら true。</li>
     *   <li>当該試合の参加チーム（home/away）の teamId のいずれかで ADMIN 以上なら true。</li>
     * </ol>
     *
     * <p>大会が存在しない／指定組織に属さない、または試合が当該大会に属さない場合は false（権限なし）。</p>
     *
     * @param actorUserId    操作者ユーザー ID（未認証なら null）
     * @param organizationId 主催組織 ID（path 由来）
     * @param tournamentId   大会 ID（path 由来）
     * @param matchId        対象試合 ID（path 由来）
     * @return スコア入力可能なら true
     */
    public boolean canEnterScore(Long actorUserId, Long organizationId, Long tournamentId, Long matchId) {
        if (actorUserId == null || organizationId == null || tournamentId == null || matchId == null) {
            return false;
        }

        TournamentEntity tournament = tournamentRepository.findById(tournamentId).orElse(null);
        if (tournament == null || !organizationId.equals(tournament.getOrganizationId())) {
            // 大会が存在しない / 指定組織に属さない（IDOR）→ 権限なし
            return false;
        }

        // 条件①・②: 大会全体に効く権限（ORG ADMIN / 指名スコアキーパー）
        if (hasTournamentWidePermission(actorUserId, tournament, tournamentId)) {
            return true;
        }

        // 条件③: 当該試合の参加チーム ADMIN（自チーム関与試合のみ）
        return isParticipatingTeamAdmin(actorUserId, tournamentId, matchId);
    }

    /**
     * 大会全体に効くスコア入力可否を判定する（batch / import 用・条件①②のみ）。
     *
     * <p>節一括入力・CSV インポートは複数試合を横断するため、参加チーム ADMIN（条件③）は対象外とする。
     * ORG 管理者または指名スコアキーパーのみに限定する。</p>
     *
     * @param actorUserId    操作者ユーザー ID（未認証なら null）
     * @param organizationId 主催組織 ID（path 由来）
     * @param tournamentId   大会 ID（path 由来）
     * @return 大会全体のスコア入力可能なら true
     */
    public boolean canEnterScoreTournamentWide(Long actorUserId, Long organizationId, Long tournamentId) {
        if (actorUserId == null || organizationId == null || tournamentId == null) {
            return false;
        }
        TournamentEntity tournament = tournamentRepository.findById(tournamentId).orElse(null);
        if (tournament == null || !organizationId.equals(tournament.getOrganizationId())) {
            return false;
        }
        return hasTournamentWidePermission(actorUserId, tournament, tournamentId);
    }

    /** 条件①（ORG ADMIN / SYSTEM_ADMIN）または条件②（指名スコアキーパー）を満たすか。 */
    private boolean hasTournamentWidePermission(Long actorUserId, TournamentEntity tournament, Long tournamentId) {
        // ① SYSTEM_ADMIN は無条件許可
        if (accessControlService.isSystemAdmin(actorUserId)) {
            return true;
        }
        // ① 主催組織 ADMIN/DEPUTY_ADMIN
        if (accessControlService.isAdminOrAbove(actorUserId, tournament.getOrganizationId(), SCOPE_ORGANIZATION)) {
            return true;
        }
        // ② 当該大会の指名スコアキーパー
        return scorekeeperRepository.existsByTournamentIdAndUserId(tournamentId, actorUserId);
    }

    /**
     * 条件③: 当該試合の参加チーム（home/away participant の teamId）のいずれかで ADMIN 以上か。
     *
     * <p>試合が当該大会に属することを確認したうえで、participant → teamId を解決して判定する
     * （クライアントの teamId 詐称を信頼しない・サーバー導出）。</p>
     */
    private boolean isParticipatingTeamAdmin(Long actorUserId, Long tournamentId, Long matchId) {
        TournamentFixtureEntity match = matchRepository.findById(matchId).orElse(null);
        if (match == null) {
            return false;
        }
        // 試合が当該大会に属することを検証（他大会の matchId 詐称を弾く・IDOR 対策）
        if (!isMatchInTournament(matchId, tournamentId)) {
            return false;
        }

        List<Long> participantIds = new ArrayList<>(2);
        if (match.getHomeParticipantId() != null) {
            participantIds.add(match.getHomeParticipantId());
        }
        if (match.getAwayParticipantId() != null) {
            participantIds.add(match.getAwayParticipantId());
        }
        if (participantIds.isEmpty()) {
            return false;
        }

        for (Long participantId : participantIds) {
            Optional<TournamentParticipantEntity> participant = participantRepository.findById(participantId);
            if (participant.isEmpty()) {
                continue;
            }
            Long teamId = participant.get().getTeamId();
            if (teamId != null
                    && accessControlService.isAdminOrAbove(actorUserId, teamId, SCOPE_TEAM)) {
                return true;
            }
        }
        return false;
    }

    /** matchId が tournamentId 配下に属するか（match → matchday → division → tournament）。 */
    private boolean isMatchInTournament(Long matchId, Long tournamentId) {
        return matchRepository.countByIdAndTournamentId(matchId, tournamentId) > 0;
    }

    // ─────────────────────────────────────────────
    // SpEL（@PreAuthorize）ファサード
    //   method-security を維持しつつ、SpEL では解決できない matchId→participant→team を
    //   サービス層で解決する。bean 名 "tournamentScoreGuard" で参照する。
    // ─────────────────────────────────────────────

    /**
     * {@code @PreAuthorize} 用: 特定試合のスコア入力可否（3-way・項目③）。
     *
     * <p>使用例:
     * <pre>{@code @PreAuthorize("@tournamentScoreGuard.canEnterScore(authentication, #orgId, #tId, #matchId)")}</pre>
     * </p>
     *
     * @param authentication 現在の認証情報
     * @param organizationId 主催組織 ID（path 由来）
     * @param tournamentId   大会 ID（path 由来）
     * @param matchId        対象試合 ID（path 由来）
     * @return スコア入力可能なら true
     */
    public boolean canEnterScore(Authentication authentication, Long organizationId,
                                 Long tournamentId, Long matchId) {
        return canEnterScore(resolveUserId(authentication), organizationId, tournamentId, matchId);
    }

    /**
     * {@code @PreAuthorize} 用: 大会全体のスコア入力可否（batch / import・条件①②のみ）。
     *
     * <p>使用例:
     * <pre>{@code @PreAuthorize("@tournamentScoreGuard.canEnterScoreTournamentWide(authentication, #orgId, #tId)")}</pre>
     * </p>
     *
     * @param authentication 現在の認証情報
     * @param organizationId 主催組織 ID（path 由来）
     * @param tournamentId   大会 ID（path 由来）
     * @return 大会全体のスコア入力可能なら true
     */
    public boolean canEnterScoreTournamentWide(Authentication authentication, Long organizationId,
                                               Long tournamentId) {
        return canEnterScoreTournamentWide(resolveUserId(authentication), organizationId, tournamentId);
    }

    /**
     * {@code Authentication} からユーザー ID を解決する。
     * null / 非認証 / 数値パース失敗の場合は {@code null} を返す（呼出側で false 扱い）。
     */
    private Long resolveUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        try {
            return Long.parseLong(authentication.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
