package com.mannschaft.app.filesharing.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.FileSharingErrorCode;
import com.mannschaft.app.filesharing.FileVisibilityRole;
import com.mannschaft.app.filesharing.entity.SharedFileEntity;
import com.mannschaft.app.filesharing.entity.SharedFolderEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * ファイル共有ドメインの認可判定を一元化するガード。
 *
 * <p>判定はすべて<b>取得済みのフォルダ／ファイルエンティティ</b>を入力に取り、そのエンティティが保持する
 * スコープ（{@code scope_type} / {@code team_id} / {@code organization_id} / {@code user_id}）に基づく。
 * リクエストが申告したスコープ種別・スコープ ID は判定材料に用いない。エンティティの解決は呼び出し側
 * （{@link SharedFolderQueryService} / {@link SharedFolderService}）が行い、本クラスは判定のみを担う。</p>
 *
 * <p>適用する階層は 3 段である。</p>
 * <ol>
 *   <li><b>基本認可</b> — スコープ所属（PERSONAL は所有者本人 / TEAM・ORGANIZATION はメンバー /
 *       大会系は {@link FolderScopeAccessGuard} へ委譲）</li>
 *   <li><b>B: 最低可視ロール</b> — {@code min_visible_role}（ファイル値優先・フォルダ継承）</li>
 *   <li><b>C: ダウンロード禁止フラグ</b> — フォルダ OR ファイルの {@code download_disabled}</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class SharedFolderAccessGuard {

    private final AccessControlService accessControlService;
    private final FolderScopeAccessGuard folderScopeAccessGuard;

    /**
     * フォルダ実体のスコープに応じて閲覧認可を当てる（基本認可 ＋ B: フォルダ最低可視ロール）。
     */
    public void authorizeView(SharedFolderEntity folder, Long userId) {
        authorizeBaseView(folder, userId);
        applyMinVisibleRole(folder, folder.getMinVisibleRole(), userId);
    }

    /**
     * ファイル単位の閲覧認可を当てる（基本認可 ＋ B: 実効最低可視ロール）。
     *
     * <p>実効最低可視ロールはファイル個別値を優先し、{@code null} ならフォルダ値を継承する。</p>
     */
    public void authorizeFileView(SharedFolderEntity folder, SharedFileEntity file, Long userId) {
        authorizeBaseView(folder, userId);
        applyMinVisibleRole(folder, effectiveMinRole(file, folder), userId);
    }

    /**
     * ファイル単位のダウンロード認可を当てる（閲覧認可 ＋ C: ダウンロード禁止フラグ）。
     *
     * <p>実効禁止 = フォルダ.{@code downloadDisabled} OR ファイル.{@code downloadDisabled}
     * （禁止は単調であり、ファイル側で解除できない）。SYSTEM_ADMIN は B/C を貫通する。</p>
     */
    public void authorizeDownload(SharedFolderEntity folder, SharedFileEntity file, Long userId) {
        authorizeFileView(folder, file, userId);
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        requireDownloadEnabled(folder, file);
    }

    /**
     * C: ダウンロード禁止フラグのみを評価する（公開リンク経由の貫通防御）。
     *
     * <p>公開リンクはトークンが capability であるためスコープ認可は通さないが、
     * 本フラグは公開リンクでも必ず評価し、「リンク側で DL 許可にしても、ファイル／フォルダが
     * DL 禁止なら DL 不可」という AND 評価を保証する。</p>
     */
    public void requireDownloadEnabled(SharedFolderEntity folder, SharedFileEntity file) {
        boolean effectiveDisabled = Boolean.TRUE.equals(folder.getDownloadDisabled())
                || Boolean.TRUE.equals(file.getDownloadDisabled());
        if (effectiveDisabled) {
            throw new BusinessException(FileSharingErrorCode.DOWNLOAD_DISABLED);
        }
    }

    /**
     * フォルダ実体のスコープに応じて<b>削除・公開リンク管理</b>の認可を当てる（閲覧より強い権限）。
     *
     * <ul>
     *   <li>PERSONAL: 所有者本人のみ。他人は {@code FOLDER_NOT_FOUND}（404・存在秘匿）。</li>
     *   <li>TEAM / ORGANIZATION: {@link AccessControlService#checkAdminOrAbove}（ADMIN / DEPUTY_ADMIN）。</li>
     *   <li>TOURNAMENT / TOURNAMENT_DIVISION: {@link FolderScopeAccessGuard#checkFolderPostByFolderId} へ委譲。</li>
     * </ul>
     */
    public void authorizeDelete(SharedFolderEntity folder, Long userId) {
        switch (folder.getScopeType()) {
            case PERSONAL -> {
                if (folder.getUserId() == null || !folder.getUserId().equals(userId)) {
                    throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
                }
            }
            case TEAM -> accessControlService.checkAdminOrAbove(userId, folder.getTeamId(), "TEAM");
            case ORGANIZATION ->
                    accessControlService.checkAdminOrAbove(userId, folder.getOrganizationId(), "ORGANIZATION");
            case TOURNAMENT, TOURNAMENT_DIVISION ->
                    folderScopeAccessGuard.checkFolderPostByFolderId(folder.getId(), userId);
        }
    }

    /**
     * スコープ別の<b>基本認可</b>（個人所有 / メンバーシップ / 大会連絡スペース認可）を当てる。
     * B: 最低可視ロールは含まない。
     */
    public void authorizeBaseView(SharedFolderEntity folder, Long userId) {
        switch (folder.getScopeType()) {
            case PERSONAL -> {
                if (folder.getUserId() == null || !folder.getUserId().equals(userId)) {
                    // 他人の個人フォルダは存在を漏らさず 404。
                    throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
                }
            }
            case TEAM -> accessControlService.checkMembership(userId, folder.getTeamId(), "TEAM");
            case ORGANIZATION ->
                    accessControlService.checkMembership(userId, folder.getOrganizationId(), "ORGANIZATION");
            case TOURNAMENT, TOURNAMENT_DIVISION ->
                    folderScopeAccessGuard.checkFolderViewByFolderId(folder.getId(), userId);
        }
    }

    /**
     * B: 最低可視ロール判定を当てる。{@code role} が {@code null} なら判定スキップ（所属者全員可視）。
     *
     * <p>SYSTEM_ADMIN は貫通する。PERSONAL は所有者のみ（基本認可で担保済み）ゆえ最低可視ロールを無視する。
     * TEAM / ORGANIZATION は当該スコープ、大会系は主催組織の ORGANIZATION ロールで判定する。</p>
     */
    public void applyMinVisibleRole(SharedFolderEntity folder, FileVisibilityRole role, Long userId) {
        if (role == null) {
            return;
        }
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return;
        }
        RoleScope scope = resolveRoleScope(folder);
        if (scope == null) {
            return;
        }
        boolean ok = accessControlService.hasRoleOrAbove(
                userId, scope.scopeId(), scope.scopeType(), role.toRequiredRoleName());
        if (!ok) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * B: 一覧経路向けに、ユーザーが満たすファイル最低可視ロールのレベル集合を解決する。
     *
     * <p>返り値をリポジトリのクエリ段階の絞り込みに用いることで、フォルダより厳しいファイル個別の
     * 最低可視ロールを持つファイルのメタ情報（ファイル名等）が下位ロールの一覧へ載らないようにする。</p>
     *
     * <ul>
     *   <li>{@code null} … 全許可（フィルタ不要）。PERSONAL スコープまたは SYSTEM_ADMIN。</li>
     *   <li>空集合 … 非 NULL レベルを 1 つも満たさない。{@code min_visible_role IS NULL} のみ可視。</li>
     *   <li>非空集合 … 満たすレベル群。{@code IS NULL} ＋ この集合で絞る。</li>
     * </ul>
     */
    public Set<FileVisibilityRole> resolveVisibleFileLevels(SharedFolderEntity folder, Long userId) {
        if (userId != null && accessControlService.isSystemAdmin(userId)) {
            return null;
        }
        RoleScope scope = resolveRoleScope(folder);
        if (scope == null) {
            return null;
        }
        Set<FileVisibilityRole> allowed = EnumSet.noneOf(FileVisibilityRole.class);
        for (FileVisibilityRole level : FileVisibilityRole.values()) {
            if (accessControlService.hasRoleOrAbove(
                    userId, scope.scopeId(), scope.scopeType(), level.toRequiredRoleName())) {
                allowed.add(level);
            }
        }
        return allowed;
    }

    /**
     * 取得済みの親フォルダが、作成先スコープと同一のスコープに属することを保証する（接ぎ木の封鎖）。
     *
     * <p>スコープ種別が違う／同種でもスコープ ID が違う場合は {@code FOLDER_NOT_FOUND}（404）とし、
     * 他スコープの folderId の存在有無を漏らさない。PERSONAL は操作者本人の所有であることを要求する。</p>
     *
     * @param parent          取得済みの親フォルダ
     * @param type            作成先スコープ種別
     * @param expectedScopeId 作成先スコープ ID（TEAM は teamId / ORGANIZATION は organizationId /
     *                        PERSONAL は所有者の userId / 大会系は scopeRefId）
     */
    public void requireParentWithinScope(SharedFolderEntity parent, FileScopeType type, Long expectedScopeId) {
        if (parent.getScopeType() != type) {
            throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
        }
        Long actual = switch (type) {
            case TEAM -> parent.getTeamId();
            case ORGANIZATION -> parent.getOrganizationId();
            case PERSONAL -> parent.getUserId();
            case TOURNAMENT, TOURNAMENT_DIVISION -> parent.getScopeRefId();
        };
        if (!Objects.equals(expectedScopeId, actual)) {
            throw new BusinessException(FileSharingErrorCode.FOLDER_NOT_FOUND);
        }
    }

    /**
     * 実効最低可視ロールを解決する（ファイル値優先 → フォルダ継承）。
     */
    public FileVisibilityRole effectiveMinRole(SharedFileEntity file, SharedFolderEntity folder) {
        return file.getMinVisibleRole() != null ? file.getMinVisibleRole() : folder.getMinVisibleRole();
    }

    /**
     * 最低可視ロール判定に使うスコープを解決する。
     * TEAM→(teamId,"TEAM") / ORGANIZATION・大会系→(organizationId,"ORGANIZATION") /
     * PERSONAL→{@code null}（所有者のみ・最低可視ロールは無視）。
     */
    private RoleScope resolveRoleScope(SharedFolderEntity folder) {
        return switch (folder.getScopeType()) {
            case TEAM -> new RoleScope(folder.getTeamId(), "TEAM");
            case ORGANIZATION, TOURNAMENT, TOURNAMENT_DIVISION ->
                    new RoleScope(folder.getOrganizationId(), "ORGANIZATION");
            case PERSONAL -> null;
        };
    }

    /** 最低可視ロール判定に使う (scopeId, scopeType) の組。PERSONAL では {@code null}。 */
    private record RoleScope(Long scopeId, String scopeType) {
    }
}
