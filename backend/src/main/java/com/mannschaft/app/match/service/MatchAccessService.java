package com.mannschaft.app.match.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.entity.MatchEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * F08.10 試合ドメインの認可集約サービス（03 §C・最重要）。
 *
 * <p>権限ロジックを本サービスに集約し、Controller / 各 Service から委譲する（03 §C.3.1）。
 * 二重防御の<b>第一防御（Service 層）</b>として {@link AccessControlService} を明示呼出しする
 * （per-scope ロールは JWT に無いため SpEL ベースの判定だけに頼らない）。</p>
 *
 * <p><b>閲覧可視性は独自述語を書かず F00 正準（{@link ContentVisibilityChecker}・
 * {@code canViewUuid(MATCH, ...)}）へ委譲</b>する（03 §C.3.2・メモリ教訓「可視性は F00 経由」）。</p>
 *
 * <h3>権限分界（03 §C.2）</h3>
 * <ul>
 *   <li><b>メタ編集（{@link #canEditMeta}）</b>: 作成者（{@code created_by}）／記録係（{@code scorekeeper_user_id}）
 *       ／主体チーム ADMIN/DEPUTY。最終スコア・status・モード切替・記録係変更を含む。</li>
 *   <li><b>タイムライン記録（{@link #canRecordTimeline}）</b>: 公式戦（{@code has_scorekeeper=true}）＝記録係のみ／
 *       共同記録＝両チーム ADMIN/DEPUTY。</li>
 *   <li><b>自チームデータ編集（{@link #canEditTeamData}）</b>: 当該チーム ADMIN/DEPUTY かつ
 *       {@code owningTeamId == 自チーム}（相手チーム分は不可・403）。</li>
 * </ul>
 *
 * <p>設計: docs/features/F08.10_match_record_analytics/03_permissions_and_recording_modes.md §C</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MatchAccessService {

    private static final String SCOPE_TEAM = "TEAM";

    private final AccessControlService accessControlService;
    private final ContentVisibilityChecker visibilityChecker;

    // ─────────────────────────────────────────────
    // 閲覧（F00 可視性へ委譲・独自述語禁止）
    // ─────────────────────────────────────────────

    /**
     * 試合の閲覧可否を判定する。F00 {@link ContentVisibilityChecker#canViewUuid} へ委譲する。
     *
     * @param userId  閲覧者ユーザー ID
     * @param matchId 試合 ID（UUIDv7）
     * @return 閲覧可能なら true
     */
    public boolean canView(Long userId, UUID matchId) {
        return visibilityChecker.canViewUuid(ReferenceType.MATCH, matchId, userId);
    }

    /** 閲覧不可なら 404（IDOR 秘匿・存在を漏らさない）でスローする。 */
    public void assertCanView(Long userId, UUID matchId) {
        if (!canView(userId, matchId)) {
            throw new BusinessException(MatchErrorCode.MATCH_001);
        }
    }

    // ─────────────────────────────────────────────
    // メタ編集（作成者 / 記録係 / 主体チーム ADMIN）
    // ─────────────────────────────────────────────

    /**
     * 試合メタ情報（日時・会場・最終スコア・status・モード切替・記録係変更）の編集可否（03 §C.2）。
     *
     * @param userId 操作者ユーザー ID
     * @param match  対象試合
     * @return 編集可能なら true
     */
    public boolean canEditMeta(Long userId, MatchEntity match) {
        if (userId == null || match == null) {
            return false;
        }
        // 作成者本人
        if (userId.equals(match.getCreatedBy())) {
            return true;
        }
        // 記録係本人（公式戦）
        if (match.getScorekeeperUserId() != null && userId.equals(match.getScorekeeperUserId())) {
            return true;
        }
        // 主体チーム ADMIN/DEPUTY
        return match.getTeamId() != null
                && accessControlService.isAdminOrAbove(userId, match.getTeamId(), SCOPE_TEAM);
    }

    /** メタ編集不可なら 403 でスローする。 */
    public void assertCanEditMeta(Long userId, MatchEntity match) {
        if (!canEditMeta(userId, match)) {
            throw new BusinessException(MatchErrorCode.MATCH_010);
        }
    }

    // ─────────────────────────────────────────────
    // タイムライン記録（公式戦＝記録係 / 共同記録＝両チーム ADMIN）
    // ─────────────────────────────────────────────

    /**
     * タイムラインイベントの記録可否（03 §C.2）。
     *
     * <ul>
     *   <li>公式戦（{@code has_scorekeeper=true}）: 記録係（{@code scorekeeper_user_id}）のみ。</li>
     *   <li>共同記録（{@code has_scorekeeper=false}）: 主体チーム or 相手チームの ADMIN/DEPUTY。</li>
     * </ul>
     *
     * @param userId 操作者ユーザー ID
     * @param match  対象試合
     * @return 記録可能なら true
     */
    public boolean canRecordTimeline(Long userId, MatchEntity match) {
        if (userId == null || match == null) {
            return false;
        }
        if (match.isHasScorekeeper()) {
            // 公式戦: 記録係のみ
            return match.getScorekeeperUserId() != null && userId.equals(match.getScorekeeperUserId());
        }
        // 共同記録: 主体チーム / 相手チームの ADMIN/DEPUTY
        if (match.getTeamId() != null
                && accessControlService.isAdminOrAbove(userId, match.getTeamId(), SCOPE_TEAM)) {
            return true;
        }
        return match.getOpponentTeamId() != null
                && accessControlService.isAdminOrAbove(userId, match.getOpponentTeamId(), SCOPE_TEAM);
    }

    /** タイムライン記録不可なら 403 でスローする。 */
    public void assertCanRecordTimeline(Long userId, MatchEntity match) {
        if (!canRecordTimeline(userId, match)) {
            throw new BusinessException(MatchErrorCode.MATCH_010);
        }
    }

    // ─────────────────────────────────────────────
    // 自チームデータ編集（自チーム ADMIN かつ owning==自チーム）
    // ─────────────────────────────────────────────

    /**
     * 自チームの選手データ（出場・交代・スタッツ）の訂正可否（03 §C.2）。
     *
     * <p>{@code owningTeamId} は呼び出し側（Service）が認証主体の所属から<b>サーバー導出</b>した値であること
     * （クライアントの詐称を信頼しない・マスアサインメント防止・03 §C.4a）。本判定は「当該チームの ADMIN/DEPUTY か」
     * を検証する。相手チーム分（owningTeamId が自分の管理外）は false（403）になる。</p>
     *
     * @param userId       操作者ユーザー ID
     * @param match        対象試合（帰属確認済みであること）
     * @param owningTeamId 編集対象データの所有チーム ID（サーバー導出値）
     * @return 編集可能なら true
     */
    public boolean canEditTeamData(Long userId, MatchEntity match, Long owningTeamId) {
        if (userId == null || match == null || owningTeamId == null) {
            return false;
        }
        // 当該試合に関係するチーム（主体 or 相手）であることを確認したうえで ADMIN/DEPUTY を要求する。
        boolean relatedTeam = owningTeamId.equals(match.getTeamId())
                || owningTeamId.equals(match.getOpponentTeamId());
        if (!relatedTeam) {
            return false;
        }
        return accessControlService.isAdminOrAbove(userId, owningTeamId, SCOPE_TEAM);
    }

    /** 自チームデータ編集不可なら 403 でスローする。 */
    public void assertCanEditTeamData(Long userId, MatchEntity match, Long owningTeamId) {
        if (!canEditTeamData(userId, match, owningTeamId)) {
            throw new BusinessException(MatchErrorCode.MATCH_010);
        }
    }
}
