package com.mannschaft.app.filesharing.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * {@link FileAttachmentVisibilityResolver} の単体テスト（Mockito 構成）。
 *
 * <p>F00 Phase D-β — FILE_ATTACHMENT 用 Resolver の判定パイプラインをサブクラス特化観点で検証する：</p>
 * <ul>
 *   <li>{@link ReferenceType#FILE_ATTACHMENT} 固定登録</li>
 *   <li>TEAM スコープ → {@link com.mannschaft.app.common.visibility.StandardVisibility#SCOPE_AFFILIATED}</li>
 *   <li>ORGANIZATION スコープ → {@link com.mannschaft.app.common.visibility.StandardVisibility#ORGANIZATION_WIDE}</li>
 *   <li>PERSONAL スコープ → {@link com.mannschaft.app.common.visibility.StandardVisibility#PRIVATE}（所有者のみ）</li>
 *   <li>不存在 ID → fail-closed（false）</li>
 * </ul>
 *
 * <p>判定パイプライン本体は {@code AbstractContentVisibilityResolverTest} で網羅検証済みのため、
 * 本テストは FileAttachment 固有の挙動（FileScopeType マッピング、所有者判定）に集中する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileAttachmentVisibilityResolver — FILE_ATTACHMENT 用 Resolver")
class FileAttachmentVisibilityResolverTest {

    @Mock
    private SharedFileRepository sharedFileRepository;
    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;
    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;
    @Mock
    private FollowBatchService followBatchService;
    @Mock
    private AuditLogService auditLogService;

    private VisibilityMetrics visibilityMetrics;
    private FileAttachmentVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        visibilityMetrics = new VisibilityMetrics(new SimpleMeterRegistry());
        resolver = new FileAttachmentVisibilityResolver(
                membershipBatchQueryService,
                templateEvaluator,
                visibilityMetrics,
                followBatchService,
                auditLogService,
                sharedFileRepository);
    }

    @Test
    @DisplayName("referenceType() は FILE_ATTACHMENT を返す")
    void referenceType_isFileAttachment() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.FILE_ATTACHMENT);
    }

    // ========================================================================
    // TEAM スコープ — チームメンバーのみ可視
    // ========================================================================

    @Nested
    @DisplayName("TEAM スコープ（チームメンバーのみ可視）")
    class TeamScope {

        @Test
        @DisplayName("canView_TEAMスコープファイルはメンバーが見られる")
        void canView_TEAMスコープファイルはメンバーが見られる() {
            FileAttachmentVisibilityProjection row = teamProjection(1L, 10L);
            stubProjection(row);
            ScopeKey scope = new ScopeKey("TEAM", 10L);
            when(membershipBatchQueryService.snapshotForUser(eq(200L), any(), any()))
                    .thenReturn(new UserScopeRoleSnapshot(
                            false,
                            Map.of(scope, "MEMBER"),
                            Map.of(),
                            Set.of(),
                            Set.of()));

            assertThat(resolver.canView(1L, 200L)).isTrue();
        }

        @Test
        @DisplayName("TEAM スコープ — 非メンバーは閲覧不可")
        void canView_TEAMスコープファイルは非メンバーが見られない() {
            FileAttachmentVisibilityProjection row = teamProjection(1L, 10L);
            stubProjection(row);
            stubSnapshotEmpty(300L);

            assertThat(resolver.canView(1L, 300L)).isFalse();
        }

        @Test
        @DisplayName("TEAM スコープ — 未認証ユーザーは閲覧不可")
        void canView_TEAMスコープファイルは未認証が見られない() {
            FileAttachmentVisibilityProjection row = teamProjection(1L, 10L);
            stubProjection(row);
            when(membershipBatchQueryService.snapshotForUser(eq(null), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.empty());

            assertThat(resolver.canView(1L, null)).isFalse();
        }

        @Test
        @DisplayName("TEAM スコープ — SystemAdmin は閲覧可")
        void canView_TEAMスコープファイルはSystemAdminが見られる() {
            FileAttachmentVisibilityProjection row = teamProjection(1L, 10L);
            stubProjection(row);
            when(membershipBatchQueryService.snapshotForUser(eq(999L), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 999L)).isTrue();
        }
    }

    // ========================================================================
    // PERSONAL スコープ — フォルダ所有者のみ可視
    // ========================================================================

    @Nested
    @DisplayName("PERSONAL スコープ（所有者のみ可視）")
    class PersonalScope {

        @Test
        @DisplayName("canView_PERSONALスコープファイルは所有者のみ")
        void canView_PERSONALスコープファイルは所有者のみ() {
            // フォルダ所有者 userId=100 のファイル
            FileAttachmentVisibilityProjection row = personalProjection(1L, 100L);
            stubProjection(row);
            stubSnapshotEmpty(100L);

            // 所有者は閲覧可
            assertThat(resolver.canView(1L, 100L)).isTrue();
        }

        @Test
        @DisplayName("PERSONAL スコープ — 他ユーザーは閲覧不可")
        void canView_PERSONALスコープファイルは他ユーザーが見られない() {
            FileAttachmentVisibilityProjection row = personalProjection(1L, 100L);
            stubProjection(row);
            stubSnapshotEmpty(200L);

            // 他ユーザーは閲覧不可
            assertThat(resolver.canView(1L, 200L)).isFalse();
        }

        @Test
        @DisplayName("PERSONAL スコープ — SystemAdmin は閲覧可")
        void canView_PERSONALスコープSystemAdminが見られる() {
            FileAttachmentVisibilityProjection row = personalProjection(1L, 100L);
            stubProjection(row);
            when(membershipBatchQueryService.snapshotForUser(eq(999L), any(), any()))
                    .thenReturn(UserScopeRoleSnapshot.forSystemAdmin());

            assertThat(resolver.canView(1L, 999L)).isTrue();
        }
    }

    // ========================================================================
    // ORGANIZATION スコープ — 組織メンバー全員可視
    // ========================================================================

    @Nested
    @DisplayName("ORGANIZATION スコープ（組織メンバー全員可視）")
    class OrganizationScope {

        @Test
        @DisplayName("ORGANIZATION スコープ — 親 ORG メンバーは閲覧可")
        void canView_ORGANIZATIONスコープは組織メンバーが見られる() {
            FileAttachmentVisibilityProjection row = organizationProjection(1L, 20L);
            stubProjection(row);
            ScopeKey orgScope = new ScopeKey("ORGANIZATION", 20L);
            when(membershipBatchQueryService.snapshotForUser(eq(300L), any(), any()))
                    .thenReturn(new UserScopeRoleSnapshot(
                            false,
                            Map.of(),
                            Map.of(orgScope, 20L),
                            Set.of(orgScope),
                            Set.of()));

            assertThat(resolver.canView(1L, 300L)).isTrue();
        }

        @Test
        @DisplayName("ORGANIZATION スコープ — 非メンバーは閲覧不可")
        void canView_ORGANIZATIONスコープは非メンバーが見られない() {
            FileAttachmentVisibilityProjection row = organizationProjection(1L, 20L);
            stubProjection(row);
            stubSnapshotEmpty(400L);

            assertThat(resolver.canView(1L, 400L)).isFalse();
        }
    }

    // ========================================================================
    // 不存在 / 異常系
    // ========================================================================

    @Nested
    @DisplayName("不存在 / 異常系")
    class NotFoundAndErrors {

        @Test
        @DisplayName("canView_不存在IDはfalse")
        void canView_不存在IDはfalse() {
            when(sharedFileRepository.findVisibilityProjectionsByIdIn(any()))
                    .thenReturn(List.of());

            assertThat(resolver.canView(999_999L, 100L)).isFalse();
        }

        @Test
        @DisplayName("null contentId は false（fail-closed）")
        void nullContentId_returnsFalse() {
            assertThat(resolver.canView(null, 100L)).isFalse();
        }
    }

    // ========================================================================
    // ヘルパ
    // ========================================================================

    private void stubProjection(FileAttachmentVisibilityProjection row) {
        when(sharedFileRepository.findVisibilityProjectionsByIdIn(any()))
                .thenReturn(List.of(row));
    }

    private void stubSnapshotEmpty(long viewerId) {
        when(membershipBatchQueryService.snapshotForUser(eq(viewerId), any(), any()))
                .thenReturn(UserScopeRoleSnapshot.empty());
    }

    private static FileAttachmentVisibilityProjection teamProjection(long id, Long teamId) {
        return new FileAttachmentVisibilityProjection(
                id, FileScopeType.TEAM, teamId, null, null);
    }

    private static FileAttachmentVisibilityProjection organizationProjection(long id, Long orgId) {
        return new FileAttachmentVisibilityProjection(
                id, FileScopeType.ORGANIZATION, null, orgId, null);
    }

    private static FileAttachmentVisibilityProjection personalProjection(long id, Long ownerUserId) {
        return new FileAttachmentVisibilityProjection(
                id, FileScopeType.PERSONAL, null, null, ownerUserId);
    }
}
