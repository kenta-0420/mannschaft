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
 * プロジェクトアクセス認可ガード（F02.3 プロジェクト API / IDOR・BOLA 対策）。
 *
 * <p>個人・チーム・組織スコープのプロジェクトに対する所有権／メンバーシップ検証を一元化する
 * コンポーネント。{@link com.mannschaft.app.todo.controller.UserProjectController} /
 * {@link com.mannschaft.app.todo.controller.TeamProjectController} /
 * {@link com.mannschaft.app.todo.controller.OrgProjectController} /
 * {@link com.mannschaft.app.todo.controller.MilestoneGateController} の各 EP 入口から呼び出す。</p>
 *
 * <p><b>共通の保証</b>: 対象プロジェクトを必ず取得し、<b>entity 由来のスコープ</b>が path のスコープと
 * 一致することを照合する（リクエストのスコープ ID をそのまま信頼しない）。不一致・不存在はいずれも
 * {@link TodoErrorCode#PROJECT_NOT_FOUND}（404）にまとめ、他スコープでのプロジェクト ID の存在有無を
 * 漏らさない。スコープのメンバーシップ違反は 403（{@code COMMON_002}）。</p>
 *
 * <ul>
 *   <li>個人スコープ: プロジェクト所有者本人に限定（{@code scopeId == userId}）。</li>
 *   <li>チーム・組織スコープ: 当該スコープのメンバーに限定（ADMIN は要求せず一般メンバーの CRUD を許可）。</li>
 * </ul>
 *
 * <p>ガードは共有 Service ではなく <b>public な入口（Controller）</b>から呼ぶこと。共有 Service 内部に
 *置くと、同メソッドを使うバッチ・他ドメイン連携が巻き添えで 404/403 になる。</p>
 */
@Component
@RequiredArgsConstructor
public class ProjectAccessGuard {

    private final ProjectRepository projectRepository;
    private final AccessControlService accessControlService;

    /**
     * 個人プロジェクトへのアクセスを検証する。
     *
     * <p>プロジェクト所有者本人（PERSONAL スコープ かつ {@code scopeId == userId}）に限定する。
     * 他ユーザーのプロジェクト ID・他スコープのプロジェクト ID・不存在はいずれも
     * {@link TodoErrorCode#PROJECT_NOT_FOUND}（404）にまとめて存在を秘匿する。</p>
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
     * <p>TEAM スコープ かつ {@code scopeId == teamId} を照合し、不一致・不存在は
     * {@link TodoErrorCode#PROJECT_NOT_FOUND}（404 秘匿）。さらに当該チームのメンバーに限定する
     * （非メンバーは 403 / {@code COMMON_002}）。</p>
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
     * <p>当該チームのメンバーに限定する（非メンバーは 403 / {@code COMMON_002}）。
     * 一覧・作成 EP には projectId が無いため
     * {@link #validateTeamProjectAccess(Long, Long, Long)} とは分けて提供する。</p>
     *
     * @param userId 現在ユーザー ID
     * @param teamId パス上のチーム内部 ID（resolveTeamId 済み）
     */
    public void validateTeamMembership(Long userId, Long teamId) {
        // メンバーシップ検証（一覧・作成 EP 用・projectId なし）
        accessControlService.checkMembership(userId, teamId, "TEAM");
    }

    /**
     * 組織プロジェクトへのアクセスを検証する（IDOR / 認可ゲート）。
     *
     * <p>ORGANIZATION スコープ かつ {@code scopeId == orgId} を照合し、不一致・不存在は
     * {@link TodoErrorCode#PROJECT_NOT_FOUND}（404 秘匿）。さらに当該組織のメンバーに限定する
     * （非メンバーは 403 / {@code COMMON_002}）。</p>
     *
     * @param userId    現在ユーザー ID
     * @param orgId     パス上の組織内部 ID（resolveOrgId 済み）
     * @param projectId パス上のプロジェクト ID
     */
    public void validateOrgProjectAccess(Long userId, Long orgId, Long projectId) {
        // プロジェクトが存在し、組織スコープ・スコープ ID が一致することを検証
        ProjectEntity project = projectRepository.findByIdAndDeletedAtIsNull(projectId)
                .orElseThrow(() -> new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND));
        if (project.getScopeType() != TodoScopeType.ORGANIZATION || !project.getScopeId().equals(orgId)) {
            // IDOR 防御: 他スコープ ID を推測して叩くケースを NOT_FOUND にまとめる
            throw new BusinessException(TodoErrorCode.PROJECT_NOT_FOUND);
        }

        // メンバーシップ検証（adminは要求しない — 一般メンバーの CRUD を許可）
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");
    }

    /**
     * 組織スコープのメンバーシップのみを検証する（一覧／作成 EP 用）。
     *
     * <p>当該組織のメンバーに限定する（非メンバーは 403 / {@code COMMON_002}）。
     * 一覧・作成 EP には projectId が無いため
     * {@link #validateOrgProjectAccess(Long, Long, Long)} とは分けて提供する。</p>
     *
     * @param userId 現在ユーザー ID
     * @param orgId  パス上の組織内部 ID（resolveOrgId 済み）
     */
    public void validateOrgMembership(Long userId, Long orgId) {
        // メンバーシップ検証（一覧・作成 EP 用・projectId なし）
        accessControlService.checkMembership(userId, orgId, "ORGANIZATION");
    }
}
