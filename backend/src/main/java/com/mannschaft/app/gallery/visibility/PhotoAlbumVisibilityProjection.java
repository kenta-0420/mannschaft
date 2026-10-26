package com.mannschaft.app.gallery.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;
import com.mannschaft.app.gallery.AlbumVisibility;

/**
 * F00 Phase D-β — {@link com.mannschaft.app.gallery.entity.PhotoAlbumEntity} 用 Projection。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §5.2。</p>
 *
 * <p>{@code PhotoAlbumRepository#findVisibilityProjectionsByIdIn} が JPQL のコンストラクタ式
 * 1 SQL で {@code id, team_id, organization_id, created_by, visibility}
 * を取得し、本 record にバインドする。</p>
 *
 * <p>本機能は CUSTOM_TEMPLATE / FOLLOWERS_ONLY / status 軸を持たないため:
 * <ul>
 *   <li>{@link #visibilityTemplateId()} は常に {@code null} を返す</li>
 *   <li>{@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver#toContentStatus}
 *       はデフォルト {@link com.mannschaft.app.common.visibility.ContentStatus#PUBLISHED} を使用する</li>
 * </ul>
 * </p>
 *
 * @param id              photo_album_id
 * @param teamId          photo_albums.team_id ({@code null} 可）
 * @param organizationId  photo_albums.organization_id ({@code null} 可）
 * @param authorUserId    photo_albums.created_by ({@code null} 可）
 * @param albumVisibility photo_albums.visibility（AlbumVisibility → StandardVisibility 正規化に利用）
 */
public record PhotoAlbumVisibilityProjection(
        Long id,
        Long teamId,
        Long organizationId,
        Long authorUserId,
        AlbumVisibility albumVisibility) implements VisibilityProjection {

    @Override
    public String scopeType() {
        if (teamId != null) {
            return "TEAM";
        }
        if (organizationId != null) {
            return "ORGANIZATION";
        }
        return null;
    }

    @Override
    public Long scopeId() {
        if (teamId != null) {
            return teamId;
        }
        return organizationId;
    }

    @Override
    public Object visibility() {
        return albumVisibility;
    }

    @Override
    public Long visibilityTemplateId() {
        return null;
    }
}
