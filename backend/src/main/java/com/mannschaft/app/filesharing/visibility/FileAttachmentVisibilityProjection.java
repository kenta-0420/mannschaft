package com.mannschaft.app.filesharing.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;
import com.mannschaft.app.filesharing.FileScopeType;

/**
 * F00 共通可視性基盤 — {@code shared_files} + {@code shared_folders} テーブルの軽量射影。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §5.2。
 * {@link VisibilityProjection} を実装し、
 * {@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver}
 * の判定パイプラインへ受け渡す。</p>
 *
 * <p>SharedFile 自体には visibility/status フィールドがないため、
 * 親フォルダ（{@code SharedFolderEntity}）の {@link FileScopeType} から
 * {@link com.mannschaft.app.common.visibility.StandardVisibility} にマッピングする。</p>
 *
 * <p><strong>スコープ → StandardVisibility マッピング</strong>:</p>
 * <ul>
 *   <li>{@link FileScopeType#TEAM} → {@link com.mannschaft.app.common.visibility.StandardVisibility#SCOPE_AFFILIATED}
 *       — チームメンバーのみ可視</li>
 *   <li>{@link FileScopeType#ORGANIZATION} → {@link com.mannschaft.app.common.visibility.StandardVisibility#ORGANIZATION_WIDE}
 *       — 組織メンバー全員可視</li>
 *   <li>{@link FileScopeType#PERSONAL} → {@link com.mannschaft.app.common.visibility.StandardVisibility#PRIVATE}
 *       — フォルダ所有者のみ可視</li>
 * </ul>
 *
 * <p><strong>scopeType / scopeId の決定規約</strong>:</p>
 * <ul>
 *   <li>{@link FileScopeType#TEAM} → {@code scopeType = "TEAM"}, {@code scopeId = teamId}</li>
 *   <li>{@link FileScopeType#ORGANIZATION} → {@code scopeType = "ORGANIZATION"}, {@code scopeId = organizationId}</li>
 *   <li>{@link FileScopeType#PERSONAL} → {@code scopeType = null}, {@code scopeId = null}
 *       （PRIVATE 判定は {@link #authorUserId()} = {@code folderUserId} で行われる）</li>
 * </ul>
 *
 * @param id             shared_files.id
 * @param fileScopeType  shared_folders.scope_type（親フォルダのスコープ種別）
 * @param teamId         shared_folders.team_id（TEAM スコープのみ非 null）
 * @param organizationId shared_folders.organization_id（ORGANIZATION スコープのみ非 null）
 * @param folderUserId   shared_folders.user_id（PERSONAL スコープのフォルダ所有者）
 */
public record FileAttachmentVisibilityProjection(
        Long id,
        FileScopeType fileScopeType,
        Long teamId,
        Long organizationId,
        Long folderUserId
) implements VisibilityProjection {

    /**
     * {@inheritDoc}
     *
     * <p>TEAM → {@code "TEAM"}、ORGANIZATION → {@code "ORGANIZATION"}、
     * PERSONAL → {@code null}（スコープ概念なし、PRIVATE で判定）。</p>
     */
    @Override
    public String scopeType() {
        if (fileScopeType == null) {
            return null;
        }
        return switch (fileScopeType) {
            case TEAM -> "TEAM";
            // F08.7.1 / 04: 大会・ディビジョンは主催組織の可視性に集約（§6・organizationId で判定）。
            case ORGANIZATION, TOURNAMENT, TOURNAMENT_DIVISION -> "ORGANIZATION";
            case PERSONAL -> null;
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>TEAM → {@code teamId}、ORGANIZATION → {@code organizationId}、
     * PERSONAL → {@code null}。</p>
     */
    @Override
    public Long scopeId() {
        if (fileScopeType == null) {
            return null;
        }
        return switch (fileScopeType) {
            case TEAM -> teamId;
            // F08.7.1 / 04: 大会・ディビジョンは主催組織 ID（organizationId）で可視性判定（§6）。
            case ORGANIZATION, TOURNAMENT, TOURNAMENT_DIVISION -> organizationId;
            case PERSONAL -> null;
        };
    }

    /**
     * {@inheritDoc}
     *
     * <p>PERSONAL フォルダの所有者 ({@code shared_folders.user_id})。
     * TEAM / ORGANIZATION スコープでも設定されるが、PRIVATE 判定では PERSONAL 時のみ使用される。</p>
     */
    @Override
    public Long authorUserId() {
        return folderUserId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>機能側 enum として {@link FileScopeType} をそのまま返す。
     * Resolver 側で {@link com.mannschaft.app.filesharing.visibility.FileAttachmentVisibilityResolver#toStandard(FileScopeType)}
     * に渡され {@link com.mannschaft.app.common.visibility.StandardVisibility} に正規化される。</p>
     */
    @Override
    public Object visibility() {
        return fileScopeType;
    }

    /**
     * {@inheritDoc}
     *
     * <p>SharedFile には CUSTOM_TEMPLATE 機能がないため常に {@code null}。</p>
     */
    @Override
    public Long visibilityTemplateId() {
        return null;
    }
}
