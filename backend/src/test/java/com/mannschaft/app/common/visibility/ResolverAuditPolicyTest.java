package com.mannschaft.app.common.visibility;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResolverAuditPolicy} の単体テスト。
 *
 * <p>マスター裁可 C-1 (2026-05-04 / メモ {@code project_f00_phase_a_decisions.md}):
 * <ul>
 *   <li>deny で永続化対象: PRIVATE / CUSTOM_TEMPLATE / ADMINS_ONLY のみ
 *   <li>allow で永続化対象: PRIVATE / CUSTOM_TEMPLATE のみ
 * </ul>
 *
 * <p>{@link StandardVisibility} の全 9 値を網羅して確認する。
 */
@DisplayName("ResolverAuditPolicy")
class ResolverAuditPolicyTest {

    @Nested
    @DisplayName("shouldAuditDeny — deny 時の永続化判定")
    class ShouldAuditDeny {

        @Test
        @DisplayName("PRIVATE は true")
        void privateLevel_returnsTrue() {
            assertThat(ResolverAuditPolicy.shouldAuditDeny(StandardVisibility.PRIVATE))
                .isTrue();
        }

        @Test
        @DisplayName("CUSTOM_TEMPLATE は true")
        void customTemplate_returnsTrue() {
            assertThat(ResolverAuditPolicy.shouldAuditDeny(StandardVisibility.CUSTOM_TEMPLATE))
                .isTrue();
        }

        @Test
        @DisplayName("ADMINS_ONLY は true")
        void adminsOnly_returnsTrue() {
            assertThat(ResolverAuditPolicy.shouldAuditDeny(StandardVisibility.ADMINS_ONLY))
                .isTrue();
        }

        @Test
        @DisplayName("PUBLIC は false")
        void publicLevel_returnsFalse() {
            assertThat(ResolverAuditPolicy.shouldAuditDeny(StandardVisibility.PUBLIC))
                .isFalse();
        }

        @Test
        @DisplayName("MEMBERS_ONLY は false")
        void membersOnly_returnsFalse() {
            assertThat(ResolverAuditPolicy.shouldAuditDeny(StandardVisibility.MEMBERS_ONLY))
                .isFalse();
        }

        @Test
        @DisplayName("SUPPORTERS_AND_ABOVE は false")
        void supportersAndAbove_returnsFalse() {
            assertThat(ResolverAuditPolicy.shouldAuditDeny(StandardVisibility.SUPPORTERS_AND_ABOVE))
                .isFalse();
        }

        @Test
        @DisplayName("FOLLOWERS_ONLY は false")
        void followersOnly_returnsFalse() {
            assertThat(ResolverAuditPolicy.shouldAuditDeny(StandardVisibility.FOLLOWERS_ONLY))
                .isFalse();
        }

        @Test
        @DisplayName("ORGANIZATION_WIDE は false")
        void organizationWide_returnsFalse() {
            assertThat(ResolverAuditPolicy.shouldAuditDeny(StandardVisibility.ORGANIZATION_WIDE))
                .isFalse();
        }

        @Test
        @DisplayName("CUSTOM は false")
        void customLevel_returnsFalse() {
            assertThat(ResolverAuditPolicy.shouldAuditDeny(StandardVisibility.CUSTOM))
                .isFalse();
        }

        @Test
        @DisplayName("null は false (NPE 防御)")
        void nullLevel_returnsFalse() {
            assertThat(ResolverAuditPolicy.shouldAuditDeny(null))
                .isFalse();
        }
    }

    @Nested
    @DisplayName("shouldAuditAllow — allow 時の永続化判定")
    class ShouldAuditAllow {

        @Test
        @DisplayName("PRIVATE は true")
        void privateLevel_returnsTrue() {
            assertThat(ResolverAuditPolicy.shouldAuditAllow(StandardVisibility.PRIVATE))
                .isTrue();
        }

        @Test
        @DisplayName("CUSTOM_TEMPLATE は true")
        void customTemplate_returnsTrue() {
            assertThat(ResolverAuditPolicy.shouldAuditAllow(StandardVisibility.CUSTOM_TEMPLATE))
                .isTrue();
        }

        @Test
        @DisplayName("ADMINS_ONLY は false (deny のみ対象)")
        void adminsOnly_returnsFalse() {
            assertThat(ResolverAuditPolicy.shouldAuditAllow(StandardVisibility.ADMINS_ONLY))
                .isFalse();
        }

        @Test
        @DisplayName("PUBLIC / MEMBERS_ONLY / SUPPORTERS_AND_ABOVE / FOLLOWERS_ONLY / ORGANIZATION_WIDE / CUSTOM は false")
        void otherLevels_returnFalse() {
            assertThat(ResolverAuditPolicy.shouldAuditAllow(StandardVisibility.PUBLIC)).isFalse();
            assertThat(ResolverAuditPolicy.shouldAuditAllow(StandardVisibility.MEMBERS_ONLY)).isFalse();
            assertThat(ResolverAuditPolicy.shouldAuditAllow(StandardVisibility.SUPPORTERS_AND_ABOVE)).isFalse();
            assertThat(ResolverAuditPolicy.shouldAuditAllow(StandardVisibility.FOLLOWERS_ONLY)).isFalse();
            assertThat(ResolverAuditPolicy.shouldAuditAllow(StandardVisibility.ORGANIZATION_WIDE)).isFalse();
            assertThat(ResolverAuditPolicy.shouldAuditAllow(StandardVisibility.CUSTOM)).isFalse();
        }

        @Test
        @DisplayName("null は false (NPE 防御)")
        void nullLevel_returnsFalse() {
            assertThat(ResolverAuditPolicy.shouldAuditAllow(null))
                .isFalse();
        }
    }
}
