package com.mannschaft.app.property.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.property.WorkPackageStatus;
import com.mannschaft.app.property.WorkPackageVisibility;
import com.mannschaft.app.property.WorkType;
import com.mannschaft.app.property.entity.PropertyWorkPackageEntity;
import com.mannschaft.app.property.repository.PropertyWorkPackageRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;

/**
 * {@link PropertyWorkPackageVisibilityResolver} 単体テスト（F09.13 Phase 1-ζ-A）。
 *
 * <p>本クラスは Resolver の機能固有部分を検証する:</p>
 * <ul>
 *   <li>{@link PropertyWorkPackageVisibilityResolver#referenceType()} = {@code PROPERTY_WORK_PACKAGE}</li>
 *   <li>{@code loadProjections(ids)}: id 群で Repository から Entity 取得 → Projection 詰め替え</li>
 *   <li>{@code toStandard(...)}: WorkPackageVisibility → StandardVisibility 写像
 *       （MEMBERS_MASKED → MEMBERS_ONLY、PUBLIC_MASKED → SUPPORTERS_AND_ABOVE、null → ADMINS_ONLY fail-closed）</li>
 *   <li>{@code toContentStatus(...)}: WorkPackageStatus → ContentStatus 写像
 *       （CANCELLED → ARCHIVED、その他 → PUBLISHED、null → ARCHIVED fail-closed）</li>
 * </ul>
 *
 * <p>{@code AbstractContentVisibilityResolver} 全体の判定パイプラインは
 * {@code AbstractContentVisibilityResolverTest} で網羅済のため本クラスでは扱わない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PropertyWorkPackageVisibilityResolver 単体テスト（F09.13 Phase 1-ζ-A）")
class PropertyWorkPackageVisibilityResolverTest {

    @Mock
    private PropertyWorkPackageRepository packageRepository;
    @Mock
    private MembershipBatchQueryService membershipBatchQueryService;
    @Mock
    private VisibilityTemplateEvaluator templateEvaluator;
    @Mock
    private FollowBatchService followBatchService;
    @Mock
    private AuditLogService auditLogService;

    private PropertyWorkPackageVisibilityResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PropertyWorkPackageVisibilityResolver(
                membershipBatchQueryService,
                templateEvaluator,
                new VisibilityMetrics(new SimpleMeterRegistry()),
                followBatchService,
                auditLogService,
                packageRepository);
    }

    private PropertyWorkPackageEntity entity(Long id, WorkPackageVisibility v, WorkPackageStatus s) {
        PropertyWorkPackageEntity e = PropertyWorkPackageEntity.builder()
                .scopeType("TEAM")
                .scopeId(100L)
                .workType(WorkType.RENOVATION)
                .title("ok")
                .currency("JPY")
                .visibility(v)
                .status(s)
                .attachmentCount(0)
                .commentCount(0)
                .isDisclosable(true)
                .createdBy(7L)
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    // =========================================================================
    // referenceType
    // =========================================================================

    @Test
    @DisplayName("referenceType() は PROPERTY_WORK_PACKAGE を返す")
    void referenceType_propertyWorkPackage() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.PROPERTY_WORK_PACKAGE);
    }

    // =========================================================================
    // loadProjections
    // =========================================================================

    @Test
    @DisplayName("loadProjections: 取得した Entity 群を Projection に詰め替えて返す")
    @SuppressWarnings("unchecked")
    void loadProjections_mapsEntitiesToProjections() throws Exception {
        PropertyWorkPackageEntity e1 = entity(1L,
                WorkPackageVisibility.MEMBERS_ONLY, WorkPackageStatus.IN_PROGRESS);
        PropertyWorkPackageEntity e2 = entity(2L,
                WorkPackageVisibility.PUBLIC_MASKED, WorkPackageStatus.COMPLETED);
        given(packageRepository.findAllById(anyList())).willReturn(List.of(e1, e2));

        Method m = PropertyWorkPackageVisibilityResolver.class
                .getDeclaredMethod("loadProjections", java.util.Collection.class);
        m.setAccessible(true);
        List<PropertyWorkPackageVisibilityProjection> projections =
                (List<PropertyWorkPackageVisibilityProjection>) m.invoke(resolver, List.of(1L, 2L));

        assertThat(projections).hasSize(2);
        assertThat(projections.get(0).id()).isEqualTo(1L);
        assertThat(projections.get(0).scopeType()).isEqualTo("TEAM");
        assertThat(projections.get(0).scopeId()).isEqualTo(100L);
        assertThat(projections.get(0).authorUserId()).isEqualTo(7L);
        assertThat(projections.get(0).visibilityValue()).isEqualTo(WorkPackageVisibility.MEMBERS_ONLY);
        assertThat(projections.get(1).visibilityValue()).isEqualTo(WorkPackageVisibility.PUBLIC_MASKED);
        assertThat(projections.get(1).status()).isEqualTo(WorkPackageStatus.COMPLETED);
    }

    // =========================================================================
    // toStandard 写像
    // =========================================================================

    @Test
    @DisplayName("toStandard: ADMINS_ONLY → ADMINS_AND_ABOVE（挙動不変・名称正準化 W4）")
    void toStandard_adminsOnly() throws Exception {
        // 左辺は機能 enum WorkPackageVisibility.ADMINS_ONLY（DB/CHECK 据置）。出力 Std 値のみ改名。
        // 挙動不変: ADMINS_AND_ABOVE = hasRoleOrAbove("ADMIN") = 旧 ADMINS_ONLY と同一判定。
        assertThat(invokeToStandard(WorkPackageVisibility.ADMINS_ONLY))
                .isEqualTo(StandardVisibility.ADMINS_AND_ABOVE);
    }

    @Test
    @DisplayName("toStandard: MEMBERS_ONLY → MEMBERS_AND_ABOVE（W2: 内輪=応援者除外。機能 enum 名は据え置き）")
    void toStandard_membersOnly() throws Exception {
        // W2: MaskingService が「SUPPORTER は MEMBERS_ONLY 不可視」と明記＝内輪(i)確証あり。
        // Mapper の出力先のみ正準ラダー MEMBERS_AND_ABOVE へ変更（機能 enum 値・DB 値は据え置き＝④A）。
        assertThat(invokeToStandard(WorkPackageVisibility.MEMBERS_ONLY))
                .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }

    @Test
    @DisplayName("toStandard: MEMBERS_MASKED → MEMBERS_AND_ABOVE（マスクは Resolver で扱わない。閲覧範囲は内輪）")
    void toStandard_membersMasked() throws Exception {
        assertThat(invokeToStandard(WorkPackageVisibility.MEMBERS_MASKED))
                .isEqualTo(StandardVisibility.MEMBERS_AND_ABOVE);
    }

    @Test
    @DisplayName("toStandard: PUBLIC_MASKED → SUPPORTERS_AND_ABOVE")
    void toStandard_publicMasked() throws Exception {
        assertThat(invokeToStandard(WorkPackageVisibility.PUBLIC_MASKED))
                .isEqualTo(StandardVisibility.SUPPORTERS_AND_ABOVE);
    }

    @Test
    @DisplayName("toStandard: null は ADMINS_AND_ABOVE（fail-closed / 挙動不変・名称正準化 W4）")
    void toStandard_null_failsClosed() throws Exception {
        // 挙動不変: ADMINS_AND_ABOVE = hasRoleOrAbove("ADMIN") = 旧 ADMINS_ONLY と同一判定。
        assertThat(invokeToStandard(null)).isEqualTo(StandardVisibility.ADMINS_AND_ABOVE);
    }

    private StandardVisibility invokeToStandard(WorkPackageVisibility v) throws Exception {
        Method m = PropertyWorkPackageVisibilityResolver.class
                .getDeclaredMethod("toStandard", Enum.class);
        m.setAccessible(true);
        return (StandardVisibility) m.invoke(resolver, v);
    }

    // =========================================================================
    // toContentStatus 写像（status 軸正規化）
    // =========================================================================

    @Test
    @DisplayName("toContentStatus: PLANNED/IN_PROGRESS/COMPLETED/CLOSED → PUBLISHED")
    void toContentStatus_activeStatuses() throws Exception {
        for (WorkPackageStatus s : new WorkPackageStatus[]{
                WorkPackageStatus.PLANNED, WorkPackageStatus.IN_PROGRESS,
                WorkPackageStatus.COMPLETED, WorkPackageStatus.CLOSED}) {
            assertThat(invokeToContentStatus(s)).as("status=%s", s)
                    .isEqualTo(ContentStatus.PUBLISHED);
        }
    }

    @Test
    @DisplayName("toContentStatus: CANCELLED → ARCHIVED")
    void toContentStatus_cancelled() throws Exception {
        assertThat(invokeToContentStatus(WorkPackageStatus.CANCELLED))
                .isEqualTo(ContentStatus.ARCHIVED);
    }

    @Test
    @DisplayName("toContentStatus: null は ARCHIVED（fail-closed）")
    void toContentStatus_null_failsClosed() throws Exception {
        assertThat(invokeToContentStatus(null)).isEqualTo(ContentStatus.ARCHIVED);
    }

    private ContentStatus invokeToContentStatus(WorkPackageStatus s) throws Exception {
        PropertyWorkPackageVisibilityProjection p = new PropertyWorkPackageVisibilityProjection(
                1L, "TEAM", 100L, 7L, s, WorkPackageVisibility.ADMINS_ONLY);
        Method m = PropertyWorkPackageVisibilityResolver.class
                .getDeclaredMethod("toContentStatus",
                        com.mannschaft.app.common.visibility.VisibilityProjection.class);
        m.setAccessible(true);
        return (ContentStatus) m.invoke(resolver, p);
    }
}
