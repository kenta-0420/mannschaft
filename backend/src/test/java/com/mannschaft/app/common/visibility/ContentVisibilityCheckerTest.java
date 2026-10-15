package com.mannschaft.app.common.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ContentVisibilityChecker} ファサードの単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.4 / §7.1〜§7.4 / §9.4。
 *
 * <p>本テストは Resolver を Mockito ではなく手書きスタブで差し込み、
 * 「ファサードのディスパッチ責務」と Phase A-5b で組み込んだメトリクス記録を
 * 純粋に検証する。
 */
@DisplayName("ContentVisibilityChecker ファサード")
class ContentVisibilityCheckerTest {

    private MeterRegistry meterRegistry;
    private VisibilityMetrics metrics;

    @BeforeEach
    void setUp() {
        this.meterRegistry = new SimpleMeterRegistry();
        this.metrics = new VisibilityMetrics(meterRegistry);
    }

    @Nested
    @DisplayName("構築 (constructor)")
    class Construction {

        @Test
        @DisplayName("Resolver 0 個でもファサードを構築できる (Smoke)")
        void smoke_emptyResolverList() {
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(), metrics);

            assertThat(checker).isNotNull();
            // 未対応扱いとして fail-closed で false が返る
            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 100L)).isFalse();
        }

        @Test
        @DisplayName("複数の異なる referenceType の Resolver は正しく登録される")
        void registers_multipleResolversForDifferentTypes() {
            StubResolver blog = new StubResolver(ReferenceType.BLOG_POST, Set.of(1L));
            StubResolver event = new StubResolver(ReferenceType.EVENT, Set.of(2L));

            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(blog, event), metrics);

            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 99L)).isTrue();
            assertThat(checker.canView(ReferenceType.EVENT, 2L, 99L)).isTrue();
        }

        @Test
        @DisplayName("同一 referenceType の重複登録は IllegalStateException")
        void throws_whenDuplicateReferenceType() {
            StubResolver a = new StubResolver(ReferenceType.BLOG_POST, Set.of(1L));
            StubResolver b = new StubResolver(ReferenceType.BLOG_POST, Set.of(2L));

            assertThatThrownBy(() -> new ContentVisibilityChecker(List.of(a, b), metrics))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate")
                .hasMessageContaining("BLOG_POST");
        }
    }

    @Nested
    @DisplayName("未対応 ReferenceType の fail-closed 挙動")
    class FailClosed {

        private ContentVisibilityChecker checker;

        @BeforeEach
        void initChecker() {
            checker = new ContentVisibilityChecker(List.of(), metrics);
        }

        @Test
        @DisplayName("canView は false を返す")
        void canView_returnsFalseForUnsupportedType() {
            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 100L)).isFalse();
        }

        @Test
        @DisplayName("filterAccessible は空 Set を返す")
        void filterAccessible_returnsEmptySetForUnsupportedType() {
            Set<Long> result = checker.filterAccessible(
                ReferenceType.BLOG_POST, List.of(1L, 2L, 3L), 100L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("decide は UNSUPPORTED_REFERENCE_TYPE で deny を返す")
        void decide_returnsDenyWithUnsupportedReason() {
            VisibilityDecision decision =
                checker.decide(ReferenceType.PERSONAL_TIMETABLE, 7L, 100L);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason())
                .isEqualTo(DenyReason.UNSUPPORTED_REFERENCE_TYPE);
            assertThat(decision.referenceType())
                .isEqualTo(ReferenceType.PERSONAL_TIMETABLE);
            assertThat(decision.contentId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("assertCanView は VISIBILITY_001 で BusinessException をスローする")
        void assertCanView_throwsBusinessExceptionForUnsupportedType() {
            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.FOLLOW_LIST, 1L, 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getErrorCode())
                        .isEqualTo(VisibilityErrorCode.VISIBILITY_001);
                });
        }

        @Test
        @DisplayName("未対応 type は unsupported_reference_type メトリクスに記録される (§9.4)")
        void records_unsupportedMetricOnFailClosed() {
            checker.canView(ReferenceType.BLOG_POST, 1L, 100L);
            checker.filterAccessible(ReferenceType.EVENT, List.of(1L), 100L);

            double total = meterRegistry
                .find("content_visibility.unsupported_reference_type")
                .counters()
                .stream()
                .mapToDouble(c -> c.count())
                .sum();
            assertThat(total).isGreaterThanOrEqualTo(2.0);
        }
    }

    @Nested
    @DisplayName("ディスパッチ (登録された Resolver への委譲)")
    class Dispatch {

        @Test
        @DisplayName("canView が登録 Resolver の判定結果をそのまま返す")
        void canView_dispatchesToResolver() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L, 3L, 5L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver), metrics);

            assertThat(checker.canView(ReferenceType.BLOG_POST, 1L, 100L)).isTrue();
            assertThat(checker.canView(ReferenceType.BLOG_POST, 2L, 100L)).isFalse();
            assertThat(checker.canView(ReferenceType.BLOG_POST, 5L, 100L)).isTrue();
        }

        @Test
        @DisplayName("canView 呼び出しで check.latency Timer が記録される (§9.4)")
        void canView_recordsLatencyMetric() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver), metrics);

            checker.canView(ReferenceType.BLOG_POST, 1L, 100L);

            long count = meterRegistry
                .find("content_visibility.check.latency")
                .tag("referenceType", "BLOG_POST")
                .tag("op", "canView")
                .timers()
                .stream()
                .mapToLong(t -> t.count())
                .sum();
            assertThat(count).isEqualTo(1L);
        }

        @Test
        @DisplayName("filterAccessible が許可 ID のみを残して返す")
        void filterAccessible_dispatchesToResolver() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L, 3L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver), metrics);

            Set<Long> result = checker.filterAccessible(
                ReferenceType.BLOG_POST, List.of(1L, 2L, 3L, 4L), 100L);

            assertThat(result).containsExactlyInAnyOrder(1L, 3L);
        }

        @Test
        @DisplayName("filterAccessible で batch_size と access_ratio が記録される (§9.4)")
        void filterAccessible_recordsBatchSizeAndAccessRatio() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L, 3L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver), metrics);

            checker.filterAccessible(
                ReferenceType.BLOG_POST, List.of(1L, 2L, 3L, 4L), 100L);

            assertThat(meterRegistry
                .find("content_visibility.check.batch_size")
                .tag("referenceType", "BLOG_POST")
                .summary())
                .isNotNull();
            // 入力 4 件、許可 2 件 → ratio 0.5
            assertThat(meterRegistry
                .find("content_visibility.check.access_ratio")
                .tag("referenceType", "BLOG_POST")
                .summary()
                .mean())
                .isEqualTo(0.5);
        }

        @Test
        @DisplayName("decide はデフォルト実装で allow/deny を返す")
        void decide_returnsAllowOrDenyViaDefaultImpl() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver), metrics);

            VisibilityDecision allowed =
                checker.decide(ReferenceType.BLOG_POST, 1L, 100L);
            VisibilityDecision denied =
                checker.decide(ReferenceType.BLOG_POST, 2L, 100L);

            assertThat(allowed.allowed()).isTrue();
            assertThat(allowed.denyReason()).isNull();
            assertThat(denied.allowed()).isFalse();
            // デフォルト実装は UNSPECIFIED で deny する
            assertThat(denied.denyReason()).isEqualTo(DenyReason.UNSPECIFIED);
        }

        @Test
        @DisplayName("decide deny 時に check.denied Counter が記録される (§9.4)")
        void decide_recordsDeniedCounterOnDeny() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver), metrics);

            checker.decide(ReferenceType.BLOG_POST, 999L, 100L);

            double count = meterRegistry
                .find("content_visibility.check.denied")
                .tag("referenceType", "BLOG_POST")
                .tag("denyReason", "UNSPECIFIED")
                .counter()
                .count();
            assertThat(count).isEqualTo(1.0);
        }

        @Test
        @DisplayName("assertCanView は許可なら例外を投げない")
        void assertCanView_doesNotThrowWhenAllowed() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver), metrics);

            // 例外が出ないことを確認
            checker.assertCanView(ReferenceType.BLOG_POST, 1L, 100L);
        }

        @Test
        @DisplayName("assertCanView は拒否 (UNSPECIFIED) なら VISIBILITY_001 を投げる")
        void assertCanView_throwsVisibility001WhenDenied() {
            StubResolver resolver = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(resolver), metrics);

            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.BLOG_POST, 999L, 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getErrorCode())
                        .isEqualTo(VisibilityErrorCode.VISIBILITY_001);
                });
        }

        @Test
        @DisplayName("assertCanView は NOT_FOUND なら VISIBILITY_004 を投げる")
        void assertCanView_throwsVisibility004WhenNotFound() {
            // NOT_FOUND を返す Resolver を仕込む
            StubResolver notFoundResolver = new StubResolver(
                    ReferenceType.BLOG_POST, Set.of()) {
                @Override
                public VisibilityDecision decide(Long contentId, Long viewerUserId) {
                    return VisibilityDecision.deny(
                        ReferenceType.BLOG_POST, contentId, DenyReason.NOT_FOUND);
                }
            };
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(notFoundResolver), metrics);

            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.BLOG_POST, 999L, 100L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException ex = (BusinessException) e;
                    assertThat(ex.getErrorCode())
                        .isEqualTo(VisibilityErrorCode.VISIBILITY_004);
                });
        }
    }

    @Nested
    @DisplayName("filterAccessibleByType — 複数 type 同時判定")
    class MultiTypeDispatch {

        @Test
        @DisplayName("登録済み複数 type の混在判定が type ごとに正しく分配される")
        void filterAccessibleByType_dispatchesEachType() {
            StubResolver blog = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L, 2L));
            StubResolver event = new StubResolver(
                ReferenceType.EVENT, Set.of(20L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(blog, event), metrics);

            Map<ReferenceType, Collection<Long>> input =
                new EnumMap<>(ReferenceType.class);
            input.put(ReferenceType.BLOG_POST, List.of(1L, 2L, 3L));
            input.put(ReferenceType.EVENT, List.of(10L, 20L));

            Map<ReferenceType, Set<Long>> result =
                checker.filterAccessibleByType(input, 100L);

            assertThat(result)
                .containsEntry(ReferenceType.BLOG_POST, Set.of(1L, 2L))
                .containsEntry(ReferenceType.EVENT, Set.of(20L));
        }

        @Test
        @DisplayName("未対応 type が混じっていても、その type は空 Set にマップされる")
        void filterAccessibleByType_unsupportedTypeYieldsEmpty() {
            StubResolver blog = new StubResolver(
                ReferenceType.BLOG_POST, Set.of(1L));
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(blog), metrics);

            Map<ReferenceType, Collection<Long>> input =
                new EnumMap<>(ReferenceType.class);
            input.put(ReferenceType.BLOG_POST, List.of(1L, 2L));
            input.put(ReferenceType.SCHEDULE, List.of(50L));

            Map<ReferenceType, Set<Long>> result =
                checker.filterAccessibleByType(input, 100L);

            assertThat(result).containsEntry(ReferenceType.BLOG_POST, Set.of(1L));
            assertThat(result.get(ReferenceType.SCHEDULE)).isEmpty();
        }
    }

    @Nested
    @DisplayName("AuditLogService 連携 (Phase A-6 / マスター裁可 C-1)")
    class AuditIntegration {

        /**
         * 指定した {@link VisibilityDecision} をそのまま返す Resolver スタブ。
         * resolvedLevel を含む詳細な Decision を仕込むために用いる。
         */
        private ContentVisibilityResolver<String> fixedDecisionResolver(
                ReferenceType type, VisibilityDecision decision) {
            return new ContentVisibilityResolver<>() {
                @Override
                public ReferenceType referenceType() {
                    return type;
                }
                @Override
                public boolean canView(Long contentId, Long viewerUserId) {
                    return decision.allowed();
                }
                @Override
                public Set<Long> filterAccessible(
                        Collection<Long> contentIds, Long viewerUserId) {
                    return decision.allowed() ? Set.copyOf(contentIds) : Set.of();
                }
                @Override
                public VisibilityDecision decide(Long contentId, Long viewerUserId) {
                    return decision;
                }
            };
        }

        @Test
        @DisplayName("PRIVATE deny 時は VISIBILITY_DENIED を AuditLogService に記録する")
        void deniesPrivate_recordsAuditLog() {
            VisibilityDecision deny = new VisibilityDecision(
                    ReferenceType.BLOG_POST, 42L, false,
                    DenyReason.NOT_OWNER, StandardVisibility.PRIVATE,
                    "not the owner");
            AuditLogService auditLogService = mock(AuditLogService.class);
            ContentVisibilityChecker checker = new ContentVisibilityChecker(
                    List.of(fixedDecisionResolver(ReferenceType.BLOG_POST, deny)),
                    metrics, auditLogService);

            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.BLOG_POST, 42L, 100L))
                .isInstanceOf(BusinessException.class);

            ArgumentCaptor<String> metadataCaptor = ArgumentCaptor.forClass(String.class);
            verify(auditLogService, times(1)).record(
                eq("VISIBILITY_DENIED"),
                eq(100L),
                any(), any(), any(), any(), any(), any(),
                metadataCaptor.capture());
            String metadata = metadataCaptor.getValue();
            assertThat(metadata)
                .contains("\"referenceType\":\"BLOG_POST\"")
                .contains("\"contentId\":42")
                .contains("\"denyReason\":\"NOT_OWNER\"")
                .contains("\"resolvedLevel\":\"PRIVATE\"");
        }

        @Test
        @DisplayName("CUSTOM_TEMPLATE deny 時も VISIBILITY_DENIED を記録する")
        void deniesCustomTemplate_recordsAuditLog() {
            VisibilityDecision deny = new VisibilityDecision(
                    ReferenceType.EVENT, 7L, false,
                    DenyReason.TEMPLATE_RULE_NO_MATCH,
                    StandardVisibility.CUSTOM_TEMPLATE, null);
            AuditLogService auditLogService = mock(AuditLogService.class);
            ContentVisibilityChecker checker = new ContentVisibilityChecker(
                    List.of(fixedDecisionResolver(ReferenceType.EVENT, deny)),
                    metrics, auditLogService);

            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.EVENT, 7L, 50L))
                .isInstanceOf(BusinessException.class);

            verify(auditLogService, times(1)).record(
                eq("VISIBILITY_DENIED"),
                eq(50L),
                any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("ADMINS_AND_ABOVE deny 時も VISIBILITY_DENIED を記録する（旧 ADMINS_ONLY 相当）")
        void deniesAdminsOnly_recordsAuditLog() {
            VisibilityDecision deny = new VisibilityDecision(
                    ReferenceType.BLOG_POST, 9L, false,
                    DenyReason.INSUFFICIENT_ROLE,
                    StandardVisibility.ADMINS_AND_ABOVE, null);
            AuditLogService auditLogService = mock(AuditLogService.class);
            ContentVisibilityChecker checker = new ContentVisibilityChecker(
                    List.of(fixedDecisionResolver(ReferenceType.BLOG_POST, deny)),
                    metrics, auditLogService);

            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.BLOG_POST, 9L, 88L))
                .isInstanceOf(BusinessException.class);

            verify(auditLogService, times(1)).record(
                eq("VISIBILITY_DENIED"),
                eq(88L),
                any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("SCOPE_AFFILIATED deny 時は AuditLogService に記録しない (cardinality 抑制・旧 MEMBERS_ONLY 相当)")
        void deniesMembersOnly_doesNotRecord() {
            VisibilityDecision deny = new VisibilityDecision(
                    ReferenceType.BLOG_POST, 10L, false,
                    DenyReason.NOT_A_MEMBER,
                    StandardVisibility.SCOPE_AFFILIATED, null);
            AuditLogService auditLogService = mock(AuditLogService.class);
            ContentVisibilityChecker checker = new ContentVisibilityChecker(
                    List.of(fixedDecisionResolver(ReferenceType.BLOG_POST, deny)),
                    metrics, auditLogService);

            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.BLOG_POST, 10L, 100L))
                .isInstanceOf(BusinessException.class);

            verify(auditLogService, never()).record(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("UNSUPPORTED_REFERENCE_TYPE (resolvedLevel=null) は AuditLogService に記録しない")
        void unsupportedReferenceType_doesNotRecord() {
            AuditLogService auditLogService = mock(AuditLogService.class);
            ContentVisibilityChecker checker = new ContentVisibilityChecker(
                    List.of(), metrics, auditLogService);

            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.BLOG_POST, 1L, 100L))
                .isInstanceOf(BusinessException.class);

            verify(auditLogService, never()).record(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("allow 時は (現時点で) AuditLogService に記録しない (Phase A-1c の責務)")
        void allow_doesNotRecord() {
            VisibilityDecision allow = VisibilityDecision.allow(
                    ReferenceType.BLOG_POST, 1L);
            AuditLogService auditLogService = mock(AuditLogService.class);
            ContentVisibilityChecker checker = new ContentVisibilityChecker(
                    List.of(fixedDecisionResolver(ReferenceType.BLOG_POST, allow)),
                    metrics, auditLogService);

            checker.assertCanView(ReferenceType.BLOG_POST, 1L, 100L);

            verify(auditLogService, never()).record(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("AuditLogService 未配線 (null) でも例外なく動作する")
        void noAuditLogService_doesNotFail() {
            VisibilityDecision deny = new VisibilityDecision(
                    ReferenceType.BLOG_POST, 1L, false,
                    DenyReason.NOT_OWNER, StandardVisibility.PRIVATE, null);
            ContentVisibilityChecker checker = new ContentVisibilityChecker(
                    List.of(fixedDecisionResolver(ReferenceType.BLOG_POST, deny)),
                    metrics);

            assertThatThrownBy(() ->
                checker.assertCanView(ReferenceType.BLOG_POST, 1L, 100L))
                .isInstanceOf(BusinessException.class);
            // AuditLogService=null でも NPE なしに完走することのみ確認
        }
    }

    @Nested
    @DisplayName("UUIDv7 reference 経路 (F09.15/16 S0)")
    class UuidPath {

        private final UUID UUID_ALLOWED = UUID.fromString("00000000-0000-7000-8000-000000000001");
        private final UUID UUID_DENIED = UUID.fromString("00000000-0000-7000-8000-000000000002");

        @Test
        @DisplayName("UUID 系 type に Resolver が登録されている場合、canView(UUID) で許可される")
        void canView_uuid_returnsTrueWhenResolverAllows() {
            UuidStubResolver resolver = new UuidStubResolver(
                ReferenceType.SUCCESSION_PRE_REGISTRATION, Set.of(UUID_ALLOWED));
            ContentVisibilityChecker checker = new ContentVisibilityChecker(
                List.of(resolver), metrics);

            assertThat(checker.canViewUuid(
                ReferenceType.SUCCESSION_PRE_REGISTRATION, UUID_ALLOWED, 100L))
                .isTrue();
            assertThat(checker.canViewUuid(
                ReferenceType.SUCCESSION_PRE_REGISTRATION, UUID_DENIED, 100L))
                .isFalse();
        }

        @Test
        @DisplayName("UUID 系 type の Resolver 未登録は fail-closed (false)")
        void canView_uuid_failClosedWhenResolverMissing() {
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(), metrics);

            assertThat(checker.canViewUuid(
                ReferenceType.SUCCESSION_PRE_REGISTRATION, UUID_ALLOWED, 100L))
                .isFalse();
        }

        @Test
        @DisplayName("BIGINT 系 type に UUID API を呼ぶと fail-closed (経路不一致)")
        void canView_uuid_failClosedOnBigintType() {
            // BLOG_POST は BIGINT 経路。UUID API で呼ぶこと自体が誤用。
            UuidStubResolver wrongResolver = new UuidStubResolver(
                ReferenceType.SUCCESSION_PRE_REGISTRATION, Set.of(UUID_ALLOWED));
            ContentVisibilityChecker checker = new ContentVisibilityChecker(
                List.of(wrongResolver), metrics);

            assertThat(checker.canViewUuid(
                ReferenceType.BLOG_POST, UUID_ALLOWED, 100L))
                .isFalse();
        }

        @Test
        @DisplayName("filterAccessibleUuid は許可された UUID のみ返す")
        void filterAccessibleUuid_returnsAllowedOnly() {
            UuidStubResolver resolver = new UuidStubResolver(
                ReferenceType.SUCCESSION_COVENANTS, Set.of(UUID_ALLOWED));
            ContentVisibilityChecker checker = new ContentVisibilityChecker(
                List.of(resolver), metrics);

            Set<UUID> result = checker.filterAccessibleUuid(
                ReferenceType.SUCCESSION_COVENANTS,
                List.of(UUID_ALLOWED, UUID_DENIED), 100L);

            assertThat(result).containsExactly(UUID_ALLOWED);
        }

        @Test
        @DisplayName("filterAccessibleUuid は BIGINT 系 type で空 Set を返す")
        void filterAccessibleUuid_failClosedOnBigintType() {
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(), metrics);

            Set<UUID> result = checker.filterAccessibleUuid(
                ReferenceType.BLOG_POST,
                List.of(UUID_ALLOWED, UUID_DENIED), 100L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("decide(UUID) は Resolver 未登録時に UNSUPPORTED_REFERENCE_TYPE を返す")
        void decide_uuid_returnsUnsupportedWhenResolverMissing() {
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(), metrics);

            VisibilityDecision decision = checker.decideUuid(
                ReferenceType.RESIDENT_ACTIVITY_SNAPSHOT, UUID_ALLOWED, 100L);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason())
                .isEqualTo(DenyReason.UNSUPPORTED_REFERENCE_TYPE);
            assertThat(decision.referenceType())
                .isEqualTo(ReferenceType.RESIDENT_ACTIVITY_SNAPSHOT);
        }

        @Test
        @DisplayName("decide(UUID) を BIGINT 系 type で呼ぶと UNSUPPORTED_REFERENCE_TYPE")
        void decide_uuid_failClosedOnBigintType() {
            ContentVisibilityChecker checker =
                new ContentVisibilityChecker(List.of(), metrics);

            VisibilityDecision decision = checker.decideUuid(
                ReferenceType.EVENT, UUID_ALLOWED, 100L);

            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason())
                .isEqualTo(DenyReason.UNSUPPORTED_REFERENCE_TYPE);
        }

        @Test
        @DisplayName("Long 経路の Resolver が UUID API を呼ばれると UnsupportedOperationException")
        void resolver_defaultUuidPathThrows() {
            // BIGINT 経路の Resolver が誤って UUID API を呼ばれた場合の防御。
            // 仕様: ContentVisibilityResolver のデフォルト実装が UOE を投げる。
            StubResolver resolver =
                new StubResolver(ReferenceType.BLOG_POST, Set.of(1L));

            assertThatThrownBy(() -> resolver.canViewUuid(UUID_ALLOWED, 100L))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("does not implement UUID-based canView");

            assertThatThrownBy(() -> resolver.filterAccessibleUuid(List.of(UUID_ALLOWED), 100L))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ---------------------------------------------------------------------
    // テスト用 Resolver スタブ — 「許可される ID 集合」を渡して挙動を制御
    // ---------------------------------------------------------------------

    /**
     * 指定された contentId だけを許可し、それ以外を拒否する単純な Resolver スタブ。
     * Mockito を使わず、ファサードのディスパッチ責務を純粋に検証する目的で利用する。
     */
    private static class StubResolver
            implements ContentVisibilityResolver<String> {

        private final ReferenceType type;
        private final Set<Long> allowedIds;

        StubResolver(ReferenceType type, Set<Long> allowedIds) {
            this.type = type;
            this.allowedIds = allowedIds;
        }

        @Override
        public ReferenceType referenceType() {
            return type;
        }

        @Override
        public boolean canView(Long contentId, Long viewerUserId) {
            return allowedIds.contains(contentId);
        }

        @Override
        public Set<Long> filterAccessible(
                Collection<Long> contentIds, Long viewerUserId) {
            return contentIds.stream()
                .filter(allowedIds::contains)
                .collect(java.util.stream.Collectors.toSet());
        }
    }

    /**
     * UUIDv7 reference 経路の Resolver スタブ (F09.15/16 S0)。
     *
     * <p>本スタブが受け持つ {@link ReferenceType} は UUID 経路前提のため、
     * Long 経路の {@link #canView(Long, Long)} / {@link #filterAccessible(Collection, Long)}
     * はデフォルト挙動 (空集合 / false) を返す。テスト目的で UUID 経路のみ実装する。</p>
     */
    private static class UuidStubResolver
            implements ContentVisibilityResolver<String> {

        private final ReferenceType type;
        private final Set<UUID> allowedUuids;

        UuidStubResolver(ReferenceType type, Set<UUID> allowedUuids) {
            this.type = type;
            this.allowedUuids = allowedUuids;
        }

        @Override
        public ReferenceType referenceType() {
            return type;
        }

        @Override
        public boolean canView(Long contentId, Long viewerUserId) {
            // UUID 経路 Resolver のためデフォルトでは false (誤用検知)
            return false;
        }

        @Override
        public Set<Long> filterAccessible(
                Collection<Long> contentIds, Long viewerUserId) {
            return Set.of();
        }

        @Override
        public boolean canViewUuid(UUID contentId, Long viewerUserId) {
            return allowedUuids.contains(contentId);
        }

        @Override
        public Set<UUID> filterAccessibleUuid(
                Collection<UUID> contentIds, Long viewerUserId) {
            return contentIds.stream()
                .filter(allowedUuids::contains)
                .collect(java.util.stream.Collectors.toSet());
        }
    }
}
