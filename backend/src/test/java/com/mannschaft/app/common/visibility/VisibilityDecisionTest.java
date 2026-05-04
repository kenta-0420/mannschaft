package com.mannschaft.app.common.visibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link VisibilityDecision} の static factory メソッドの単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §7.2 の
 * インバリアント (allowed=true なら denyReason=null, allowed=false なら denyReason 必須)
 * を担保する。
 */
@DisplayName("VisibilityDecision の static factory")
class VisibilityDecisionTest {

    @Nested
    @DisplayName("allow(type, id)")
    class AllowFactory {

        @Test
        @DisplayName("allowed=true、denyReason=null、resolvedLevel/detail も null になる")
        void allow_returnsAllowedDecisionWithNullDenyReason() {
            VisibilityDecision decision =
                VisibilityDecision.allow(ReferenceType.BLOG_POST, 42L);

            assertThat(decision.referenceType()).isEqualTo(ReferenceType.BLOG_POST);
            assertThat(decision.contentId()).isEqualTo(42L);
            assertThat(decision.allowed()).isTrue();
            assertThat(decision.denyReason()).isNull();
            assertThat(decision.resolvedLevel()).isNull();
            assertThat(decision.detail()).isNull();
        }
    }

    @Nested
    @DisplayName("deny(type, id, reason)")
    class DenyFactoryWithoutDetail {

        @Test
        @DisplayName("allowed=false、指定した denyReason が保持される、detail は null")
        void deny_returnsDeniedDecisionWithReason() {
            VisibilityDecision decision = VisibilityDecision.deny(
                ReferenceType.EVENT, 7L, DenyReason.NOT_A_MEMBER);

            assertThat(decision.referenceType()).isEqualTo(ReferenceType.EVENT);
            assertThat(decision.contentId()).isEqualTo(7L);
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason()).isEqualTo(DenyReason.NOT_A_MEMBER);
            assertThat(decision.resolvedLevel()).isNull();
            assertThat(decision.detail()).isNull();
        }
    }

    @Nested
    @DisplayName("deny(type, id, reason, detail)")
    class DenyFactoryWithDetail {

        @Test
        @DisplayName("allowed=false、指定した denyReason と detail が保持される")
        void deny_withDetail_returnsDeniedDecisionWithDetail() {
            VisibilityDecision decision = VisibilityDecision.deny(
                ReferenceType.SURVEY,
                100L,
                DenyReason.TEMPLATE_RULE_NO_MATCH,
                "rule#3 mismatched");

            assertThat(decision.referenceType()).isEqualTo(ReferenceType.SURVEY);
            assertThat(decision.contentId()).isEqualTo(100L);
            assertThat(decision.allowed()).isFalse();
            assertThat(decision.denyReason())
                .isEqualTo(DenyReason.TEMPLATE_RULE_NO_MATCH);
            assertThat(decision.detail()).isEqualTo("rule#3 mismatched");
        }
    }

    @Nested
    @DisplayName("コンパクトコンストラクタのインバリアント")
    class CompactConstructorInvariants {

        @Test
        @DisplayName("allowed=true で denyReason 非 null は IllegalArgumentException")
        void allowedTrue_withDenyReason_throws() {
            assertThatThrownBy(() ->
                new VisibilityDecision(
                    ReferenceType.BLOG_POST,
                    1L,
                    true,
                    DenyReason.NOT_A_MEMBER,
                    null,
                    null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denyReason must be null when allowed=true");
        }

        @Test
        @DisplayName("allowed=false で denyReason=null は IllegalArgumentException")
        void allowedFalse_withoutDenyReason_throws() {
            assertThatThrownBy(() ->
                new VisibilityDecision(
                    ReferenceType.BLOG_POST,
                    1L,
                    false,
                    null,
                    null,
                    null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("denyReason must not be null when allowed=false");
        }

        @Test
        @DisplayName("deny(type, id, null) は NullPointerException")
        void denyFactory_withNullReason_throws() {
            assertThatThrownBy(() ->
                VisibilityDecision.deny(ReferenceType.BLOG_POST, 1L, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("denyReason must not be null when denying");
        }

        @Test
        @DisplayName("referenceType=null は NullPointerException")
        void nullReferenceType_throws() {
            assertThatThrownBy(() ->
                VisibilityDecision.allow(null, 1L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("referenceType must not be null");
        }
    }
}
