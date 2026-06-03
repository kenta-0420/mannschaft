package com.mannschaft.app.inbox.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F04.11 {@link InboxDedupeKeyResolver} 単体テスト（Phase 3 ① 名寄せ）。
 *
 * <p>誤突合は ADHD ユーザーを最も混乱させる最高リスク領域。本テストは「正規化成功時のみ畳む」
 * 「正規化不能は自分自身キーで決して畳まない」「異実体は同一キーにならない」を受け入れ条件化する。
 * 設計書: 03_business_logic.md §8。</p>
 */
@DisplayName("InboxDedupeKeyResolver 単体テスト（名寄せキー）")
class InboxDedupeKeyResolverTest {

    private final InboxDedupeKeyResolver resolver = new InboxDedupeKeyResolver();

    @Nested
    @DisplayName("正規化（resolveCanonicalKey）")
    class ResolveCanonicalKey {

        @Test
        @DisplayName("NOTIFICATION の終端 sourceType=BLOG_POST は BLOG_POST:{id} に正規化される")
        void notificationBlogPostNormalized() {
            Optional<String> key = resolver.resolveCanonicalKey("BLOG_POST", 123L);

            assertThat(key).contains("BLOG_POST:123");
        }

        @Test
        @DisplayName("ANNOUNCEMENT の終端 sourceType=BLOG_POST は同一 BLOG_POST:{id} に正規化される（ソース横断で一致）")
        void announcementBlogPostNormalizedSameAsNotification() {
            Optional<String> notif = resolver.resolveCanonicalKey("BLOG_POST", 555L);
            Optional<String> announce = resolver.resolveCanonicalKey("BLOG_POST", 555L);

            assertThat(notif).isPresent();
            assertThat(announce).isEqualTo(notif);
        }

        @Test
        @DisplayName("MENTION の終端 targetType=TIMELINE_POST は TIMELINE_POST:{id} に正規化される")
        void mentionTimelinePostNormalized() {
            Optional<String> key = resolver.resolveCanonicalKey("TIMELINE_POST", 42L);

            assertThat(key).contains("TIMELINE_POST:42");
        }

        @Test
        @DisplayName("ReferenceType に未マッピングの語（TIMELINE_COMMENT）は正規化不能＝empty")
        void unmappedTypeNotNormalizable() {
            Optional<String> key = resolver.resolveCanonicalKey("TIMELINE_COMMENT", 7L);

            assertThat(key).isEmpty();
        }

        @Test
        @DisplayName("sourceType が null は正規化不能＝empty")
        void nullTypeNotNormalizable() {
            assertThat(resolver.resolveCanonicalKey(null, 7L)).isEmpty();
        }

        @Test
        @DisplayName("終端 ID が null は正規化不能＝empty（実体を一意特定できないため畳まない）")
        void nullTerminalIdNotNormalizable() {
            assertThat(resolver.resolveCanonicalKey("BLOG_POST", null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("canonicalRefOrSelf（フォールバック）")
    class CanonicalRefOrSelf {

        @Test
        @DisplayName("正規化成功時は正規化キーを返す（自分自身キーは使わない）")
        void normalizedWhenMapped() {
            String ref = resolver.canonicalRefOrSelf("BLOG_POST", 9L, "NOTIFICATION:1000");

            assertThat(ref).isEqualTo("BLOG_POST:9");
        }

        @Test
        @DisplayName("正規化不能時は自分自身キーを返す（ユニーク＝決して畳まれない）")
        void selfWhenNotMapped() {
            String ref = resolver.canonicalRefOrSelf("TIMELINE_COMMENT", 9L, "MENTION:1000");

            assertThat(ref).isEqualTo("MENTION:1000");
        }

        @Test
        @DisplayName("終端 ID が null でも自分自身キーにフォールバックする（畳まれない）")
        void selfWhenTerminalIdNull() {
            String ref = resolver.canonicalRefOrSelf("BLOG_POST", null, "ANNOUNCEMENT:2000");

            assertThat(ref).isEqualTo("ANNOUNCEMENT:2000");
        }
    }

    @Nested
    @DisplayName("誤突合の安全弁")
    class MisMatchGuard {

        @Test
        @DisplayName("同一 ReferenceType だが終端 ID が異なれば別キー（異実体は畳まれない）")
        void differentTerminalIdDifferentKey() {
            String a = resolver.canonicalRefOrSelf("BLOG_POST", 1L, "NOTIFICATION:1");
            String b = resolver.canonicalRefOrSelf("BLOG_POST", 2L, "ANNOUNCEMENT:9");

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("正規化不能な 2 件は互いに別キー（それぞれ自分自身キーゆえ畳まれない）")
        void twoUnmappedNeverCollide() {
            String a = resolver.canonicalRefOrSelf("TIMELINE_COMMENT", 1L, "MENTION:100");
            String b = resolver.canonicalRefOrSelf("UNKNOWN_X", 1L, "MENTION:200");

            assertThat(a).isNotEqualTo(b);
        }
    }
}
