package com.mannschaft.app.match.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.match.MatchErrorCode;
import com.mannschaft.app.match.domain.StateModel;
import com.mannschaft.app.match.entity.MatchEntity;
import com.mannschaft.app.match.repository.MatchEventRepository;
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
    /** ターン制個人戦の「対局者本人」判定（player_user_id 突合）に用いる（03 §C.2a）。 */
    private final MatchEventRepository matchEventRepository;

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
    // 一覧（チームメンバー以上・Phase2C）
    // ─────────────────────────────────────────────

    /**
     * チーム試合一覧の閲覧可否（メンバー以上・Phase2C・02 §F のチーム統計と同水準）。
     *
     * <p>per-scope ロールは JWT に無いため、SpEL（{@code @PreAuthorize}）の第二防御に加えて
     * 本サービスを第一防御として明示呼出しする（03 §C.3.1）。</p>
     *
     * @param userId 操作者ユーザー ID
     * @param teamId 対象チーム ID（パス由来）
     * @return 当該チームのメンバー以上なら true
     */
    public boolean canListTeamMatches(Long userId, Long teamId) {
        return userId != null && teamId != null
                && accessControlService.isMember(userId, teamId, SCOPE_TEAM);
    }

    /** チーム試合一覧の閲覧不可（非メンバー）なら 403 でスローする。 */
    public void assertCanListTeamMatches(Long userId, Long teamId) {
        if (!canListTeamMatches(userId, teamId)) {
            throw new BusinessException(MatchErrorCode.MATCH_010);
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
     *   <li><b>ターン制個人戦</b>（{@code state_model=TURN_BASED} かつ {@code parent_match_id=NULL}・03 §C.2a）:
     *       (a) 対局者本人（{@link #isParticipant}）／(b) 主体チーム ADMIN/DEPUTY／(c) 記録係。
     *       <b>団体戦の子ボード（parent_match_id 設定済）も各ボードが個人戦</b>のため同じ類型分岐に乗せる
     *       （当該ボードの対局者本人 or 主体チーム ADMIN or 記録係・§C.2a 末尾）。</li>
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
        // ターン制（将棋/囲碁）は対局者本人も自分の結果を記録/訂正できる（§C.2a・類型分岐を書き込み側に上乗せ）。
        // 個人戦（parent_match_id=NULL）も団体戦の子ボードも各ボードが 1 対 1 の対局ゆえ同じ判定に乗せる。
        if (resolveStateModel(match) == StateModel.TURN_BASED) {
            // (a) 対局者本人
            if (match.getId() != null && isParticipant(userId, match)) {
                return true;
            }
            // (b) 主体チーム ADMIN/DEPUTY
            if (match.getTeamId() != null
                    && accessControlService.isAdminOrAbove(userId, match.getTeamId(), SCOPE_TEAM)) {
                return true;
            }
            // (c) 記録係（公式戦）
            return match.isHasScorekeeper()
                    && match.getScorekeeperUserId() != null
                    && userId.equals(match.getScorekeeperUserId());
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

    /**
     * ターン制個人戦の「対局者本人」判定（03 §C.2a・先手/後手の player_user_id 突合）。
     *
     * <p>当該 match に、認証ユーザーが {@code player_user_id} として登録されたイベント（対局者）が存在すれば true。
     * 個人戦は 1 局＝1 match で対局者が明確なため、本人が自分の結果を記録・訂正できる（チーム ADMIN 経路に上乗せ）。
     * match_id スコープで判定するため二段アクセス（01 §A.4）を侵さない。</p>
     *
     * @param userId 認証ユーザー ID
     * @param match  対象試合
     * @return 当該 match の対局者本人なら true
     */
    public boolean isParticipant(Long userId, MatchEntity match) {
        if (userId == null || match == null || match.getId() == null) {
            return false;
        }
        return matchEventRepository.existsByMatchIdAndPlayerUserId(match.getId(), userId);
    }

    /** {@code state_model} 列が未設定（古いレコード等）の場合は sport から導出してフォールバックする。 */
    private StateModel resolveStateModel(MatchEntity match) {
        if (match.getStateModel() != null) {
            return match.getStateModel();
        }
        return match.getSport() != null ? match.getSport().stateModel() : StateModel.CONTINUOUS_TIME;
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
