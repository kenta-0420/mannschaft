package com.mannschaft.app.tournament.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.tournament.TournamentErrorCode;
import com.mannschaft.app.tournament.entity.TournamentEntity;
import com.mannschaft.app.tournament.repository.TournamentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 大会の<b>閲覧可否ゲート</b>（閲覧系 API 共通の認可判定の単一正本）。
 *
 * <p>主催組織の ADMIN / DEPUTY_ADMIN と SYSTEM_ADMIN は、他ユーザーが作成した DRAFT 大会を含め閲覧できる
 * （F00 Resolver は作成者・参加者視点のため管理者を通さない）。それ以外は F00 共通可視性 Resolver
 * （{@link ContentVisibilityChecker}）に委譲する。
 *
 * <h2>なぜ {@code @Transactional} でない独立コンポーネントか</h2>
 * <p>{@code TournamentService} / {@code DivisionService} はクラス単位 {@code @Transactional} のため、
 * そこへ認可判定（{@link AccessControlService} 経由で role ドメインの Repository へ到達）を足すと、
 * 新規の公開メソッドがすべて D-3T（推移的クロスドメイン {@code @Transactional}）の新規違反になる。
 * 認可ゲートは公開入口（Controller）に置く原則（{@code AuthzControllerGuardArchTest} は
 * {@code *AccessGate} への直接呼び出しを認可シグナルとして認識する）に従い、トランザクション境界を
 * 持たない本コンポーネントへ切り出した。不可視・不在は IDOR 秘匿のため 404 に統一する。</p>
 */
@Component
@RequiredArgsConstructor
public class TournamentViewAccessGate {

    private final TournamentRepository tournamentRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final AccessControlService accessControlService;

    /**
     * 閲覧者が大会を閲覧できるかを判定する。不可視時に 404 を投げるのは呼び出し側の責務。
     *
     * @param tournamentId   対象大会 ID
     * @param organizationId 大会の主催組織 ID（管理者例外の判定に使う）
     * @param viewerUserId   閲覧者（未認証は null）
     */
    public boolean isViewableBy(Long tournamentId, Long organizationId, Long viewerUserId) {
        boolean orgManager = viewerUserId != null
                && (accessControlService.isSystemAdmin(viewerUserId)
                    || accessControlService.isAdminOrAbove(
                            viewerUserId, organizationId, "ORGANIZATION"));
        return orgManager
                || contentVisibilityChecker.canView(
                        ReferenceType.TOURNAMENT, tournamentId, viewerUserId);
    }

    /**
     * 大会が閲覧可能であることを検証する。不在・不可視はどちらも 404（TOURNAMENT_NOT_FOUND）。
     *
     * @param viewerUserId 閲覧者 user_id（未認証は null）
     */
    public void verifyViewable(Long tournamentId, Long viewerUserId) {
        TournamentEntity tournament = tournamentRepository.findById(tournamentId)
                .orElseThrow(() -> new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND));
        if (!isViewableBy(tournamentId, tournament.getOrganizationId(), viewerUserId)) {
            throw new BusinessException(TournamentErrorCode.TOURNAMENT_NOT_FOUND);
        }
    }
}
