package com.mannschaft.app.gallery.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.gallery.AlbumVisibility;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * F00 Phase D-β — {@link PhotoAlbumVisibilityResolver} 単体テスト。
 *
 * <p>Repository と {@link MembershipBatchQueryService} をモック化し、
 * AlbumVisibility の各値が正しく StandardVisibility に正規化されて
 * 可視性判定に反映されることを検証する。</p>
 *
 * <p>抽象基底側の挙動（status × visibility 合成・SystemAdmin 高速パス・親 ORG 連鎖）は
 * {@code AbstractContentVisibilityResolverTest} で網羅済のため、本テストでは
 * PhotoAlbum 固有の正規化に焦点を当てる。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PhotoAlbumVisibilityResolver — 単体テスト")
class PhotoAlbumVisibilityResolverTest {

    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;

    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PhotoAlbumRepository photoAlbumRepository;

    private VisibilityMetrics visibilityMetrics;
    private PhotoAlbumVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new PhotoAlbumVisibilityResolver(
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                null,       // FollowBatchService 不要
                auditLogService,
                photoAlbumRepository);
    }

    /**
     * テスト用 Projection を生成するヘルパ。
     */
    private PhotoAlbumVisibilityProjection projection(
            Long id, Long teamId, Long organizationId, Long authorUserId,
            AlbumVisibility visibility) {
        return new PhotoAlbumVisibilityProjection(id, teamId, organizationId, authorUserId, visibility);
    }

    @Test
    @DisplayName("referenceType() は PHOTO_ALBUM を返す")
    void referenceType_is_PHOTO_ALBUM() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.PHOTO_ALBUM);
    }

    @Test
    @DisplayName("団員のみ閲覧可アルバムはスコープメンバーが見られる")
    void canView_団員のみ閲覧可アルバムはメンバーが見られる() {
        PhotoAlbumVisibilityProjection p = projection(
                1L, 100L, null, 99L, AlbumVisibility.ALL_MEMBERS);
        when(photoAlbumRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                .thenReturn(new UserScopeRoleSnapshot(false,
                        Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                        Map.of(), Set.of(), Set.of()));

        assertThat(resolver.canView(1L, 5L)).isTrue();
    }

    @Test
    @DisplayName("管理者のみアルバムは非管理者（MEMBERロール）が見られない")
    void canView_管理者のみアルバムは非管理者が見られない() {
        PhotoAlbumVisibilityProjection p = projection(
                2L, 100L, null, 99L, AlbumVisibility.ADMIN_ONLY);
        when(photoAlbumRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(p));
        when(membershipBatchQueryService.snapshotForUser(eq(5L), anySet(), anySet()))
                .thenReturn(new UserScopeRoleSnapshot(false,
                        Map.of(new ScopeKey("TEAM", 100L), "MEMBER"),
                        Map.of(), Set.of(), Set.of()));

        assertThat(resolver.canView(2L, 5L)).isFalse();
    }

    @Test
    @DisplayName("不存在IDはリポジトリが空リストを返し false")
    void canView_不存在IDはfalse() {
        when(photoAlbumRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of());

        assertThat(resolver.canView(999L, 5L)).isFalse();
    }
}
