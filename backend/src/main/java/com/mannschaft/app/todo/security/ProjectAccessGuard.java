package com.mannschaft.app.todo.security;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.todo.TodoErrorCode;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.entity.ProjectEntity;
import com.mannschaft.app.todo.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * プロジェクトアクセス認可ガード（F02.3 個人プロジェクト API / IDOR 対策）。
 *
 * <p>個人スコープ・チームスコープのプロジェクトに対する所有権／メンバーシップ検証を
 * 一元化するためのコンポーネント。{@link com.mannschaft.app.todo.controller.UserProjectController}
 * および {@link com.mannschaft.app.todo.controller.TeamProjectController} から呼び出される想定。</p>
 *
 * <p>本クラスは <b>試練フェーズの骨格</b> であり、検証ロジックは未実装（空実装）。
 * 出陣フェーズで以下を実装し、対応する red テストを green 化すること:</p>
 * <ul>
 *   <li>{@link #validatePersonalProjectAccess(Long, Long)}:
 *       {@code projectRepository.findByIdAndDeletedAtIsNull(projectId)} で取得し、
 *       スコープ種別 PERSONAL かつ scopeId == userId でなければ TODO_001（404）。</li>
 *   <li>{@link #validateTeamProjectAccess(Long, Long, Long)}:
 *       スコープ種別 TEAM かつ scopeId == teamId でなければ TODO_001（404）。
 *       さらに {@code accessControlService.checkMembership(userId, teamId, "TEAM")} で
 *       非メンバーを 403（COMMON_002）に。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProjectAccessGuard {

    private final ProjectRepository projectRepository;
    private final AccessControlService accessControlService;

    /**
     * 個人プロジェクトへのアクセスを検証する。
     *
     * <p>TODO（出陣）: プロジェクトを取得し、PERSONAL スコープ かつ scopeId == userId を検証。
     * 不一致なら {@code BusinessException(TodoErrorCode.PROJECT_NOT_FOUND)}（IDOR を 404 にまとめる）。
     * 現状は <b>空実装</b>（何もしない）のため IDOR テストが red になる。</p>
     *
     * @param userId    現在ユーザー ID
     * @param projectId パス上のプロジェクト ID
     */
    public void validatePersonalProjectAccess(Long userId, Long projectId) {
        ProjectEntity project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND));
        if (project.getScopeType() != TodoScopeType.PERSONAL
                || !project.getScopeId().equals(userId)) {
            // IDOR 防御: 他ユーザー ID を推測して叩くケースや他スコープを NOT_FOUND にまとめる
            throw new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND);
        }
    }

    /**
     * チームプロジェクトへのアクセスを検証する。
     *
     * <p>TODO（出陣）: プロジェクトを取得し、TEAM スコープ かつ scopeId == teamId を検証。
     * 不一致なら {@code BusinessException(TodoErrorCode.PROJECT_NOT_FOUND)}（404）。
     * さらに非メンバーを 403（COMMON_002）にする membership 検証を行う。
     * 現状は <b>空実装</b>（何もしない）のため IDOR / 非メンバーテストが red になる。</p>
     *
     * @param userId    現在ユーザー ID
     * @param teamId    パス上のチーム内部 ID（resolveTeamId 済み）
     * @param projectId パス上のプロジェクト ID
     */
    public void validateTeamProjectAccess(Long userId, Long teamId, Long projectId) {
        // プロジェクトが存在し、チームスコープ・スコープ ID が一致することを検証
        ProjectEntity project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND));
        if (project.getScopeType() != TodoScopeType.TEAM || !project.getScopeId().equals(teamId)) {
            // IDOR 防御: 他スコープ ID を推測して叩くケースを NOT_FOUND にまとめる
            throw new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND);
        }

        // メンバーシップ検証（adminは要求しない — 一般メンバーの CRUD を許可）
        accessControlService.checkMembership(userId, teamId, "TEAM");
    }

    /**
     * チームスコープのメンバーシップのみを検証する（一覧／作成 EP 用）。
     *
     * <p>TODO（出陣）: {@code accessControlService.checkMembership(userId, teamId, "TEAM")} を呼び、
     * 非メンバーを 403（COMMON_002）にする。一覧・作成 EP には projectId が無いため
     * {@link #validateTeamProjectAccess(Long, Long, Long)} とは分けて提供する。
     * 現状は <b>空実装</b>（何もしない）のため非メンバーテストが red になる。</p>
     *
     * @param userId 現在ユーザー ID
     * @param teamId パス上のチーム内部 ID（resolveTeamId 済み）
     */
    public void validateTeamMembership(Long userId, Long teamId) {
        // メンバーシップ検証（一覧・作成 EP 用・projectId なし）
        accessControlService.checkMembership(userId, teamId, "TEAM");
    }
}
