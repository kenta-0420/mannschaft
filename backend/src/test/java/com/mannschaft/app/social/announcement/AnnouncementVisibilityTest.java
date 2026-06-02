package com.mannschaft.app.social.announcement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnnouncementVisibility} 単体テスト（F02.6 §6.2 可視性漏洩根治）。
 *
 * <p>本テストは「漏洩（SUPPORTER に MEMBERS_ONLY 露出）」と「逆バグ（MEMBER 以上が PUBLIC/
 * SUPPORTERS_AND_ABOVE を取りこぼす）」の双方を固定する正準テストである。
 * 設計書 F02.6 §6.2（613 行「SUPPORTER には MEMBERS_ONLY を返さない」）および
 * F00 {@code AbstractContentVisibilityResolver.visibleByVisibility} の意味論に一致する。</p>
 */
@DisplayName("AnnouncementVisibility（可視性正準マッピング）")
class AnnouncementVisibilityTest {

    @Nested
    @DisplayName("allowedFor（閲覧者ロール → 可視 visibility 集合）")
    class AllowedFor {

        @Test
        @DisplayName("未ログイン/PUBLIC/null/未知 → {PUBLIC} のみ")
        void publicViewerSeesOnlyPublic() {
            assertThat(AnnouncementVisibility.allowedFor(null))
                    .containsExactlyInAnyOrder("PUBLIC");
            assertThat(AnnouncementVisibility.allowedFor("PUBLIC"))
                    .containsExactlyInAnyOrder("PUBLIC");
            assertThat(AnnouncementVisibility.allowedFor("GUEST"))
                    .containsExactlyInAnyOrder("PUBLIC");
            assertThat(AnnouncementVisibility.allowedFor("UNKNOWN_ROLE"))
                    .containsExactlyInAnyOrder("PUBLIC");
        }

        @Test
        @DisplayName("SUPPORTER → {PUBLIC, SUPPORTERS_AND_ABOVE}（MEMBERS_ONLY を含めない＝漏洩防止）")
        void supporterSeesPublicAndSupporters() {
            assertThat(AnnouncementVisibility.allowedFor("SUPPORTER"))
                    .containsExactlyInAnyOrder("PUBLIC", "SUPPORTERS_AND_ABOVE")
                    .doesNotContain("MEMBERS_ONLY");
        }

        @Test
        @DisplayName("MEMBER 以上 → 3 種全部（PUBLIC/SUPPORTERS_AND_ABOVE 取りこぼし解消）")
        void memberAndAboveSeeAll() {
            for (String role : new String[] {"MEMBER", "DEPUTY_ADMIN", "ADMIN", "SYSTEM_ADMIN"}) {
                assertThat(AnnouncementVisibility.allowedFor(role))
                        .as("role=%s", role)
                        .containsExactlyInAnyOrder("PUBLIC", "SUPPORTERS_AND_ABOVE", "MEMBERS_ONLY");
            }
        }

        @Test
        @DisplayName("大文字小文字を問わない")
        void caseInsensitive() {
            assertThat(AnnouncementVisibility.allowedFor("supporter"))
                    .containsExactlyInAnyOrder("PUBLIC", "SUPPORTERS_AND_ABOVE");
            assertThat(AnnouncementVisibility.allowedFor("member"))
                    .containsExactlyInAnyOrder("PUBLIC", "SUPPORTERS_AND_ABOVE", "MEMBERS_ONLY");
        }
    }

    @Nested
    @DisplayName("isVisibleTo（feed.visibility × 閲覧者ロール）")
    class IsVisibleTo {

        @Test
        @DisplayName("SUPPORTER は MEMBERS_ONLY を閲覧不可（漏洩根治）")
        void supporterCannotSeeMembersOnly() {
            assertThat(AnnouncementVisibility.isVisibleTo("MEMBERS_ONLY", "SUPPORTER")).isFalse();
        }

        @Test
        @DisplayName("SUPPORTER は PUBLIC / SUPPORTERS_AND_ABOVE を閲覧可")
        void supporterSeesPublicAndSupporters() {
            assertThat(AnnouncementVisibility.isVisibleTo("PUBLIC", "SUPPORTER")).isTrue();
            assertThat(AnnouncementVisibility.isVisibleTo("SUPPORTERS_AND_ABOVE", "SUPPORTER")).isTrue();
        }

        @Test
        @DisplayName("MEMBER は PUBLIC / SUPPORTERS_AND_ABOVE / MEMBERS_ONLY を全て閲覧可（取りこぼし解消）")
        void memberSeesAll() {
            assertThat(AnnouncementVisibility.isVisibleTo("PUBLIC", "MEMBER")).isTrue();
            assertThat(AnnouncementVisibility.isVisibleTo("SUPPORTERS_AND_ABOVE", "MEMBER")).isTrue();
            assertThat(AnnouncementVisibility.isVisibleTo("MEMBERS_ONLY", "MEMBER")).isTrue();
        }

        @Test
        @DisplayName("未ログインは PUBLIC のみ閲覧可")
        void publicViewerSeesOnlyPublic() {
            assertThat(AnnouncementVisibility.isVisibleTo("PUBLIC", null)).isTrue();
            assertThat(AnnouncementVisibility.isVisibleTo("SUPPORTERS_AND_ABOVE", null)).isFalse();
            assertThat(AnnouncementVisibility.isVisibleTo("MEMBERS_ONLY", null)).isFalse();
        }

        @Test
        @DisplayName("feed.visibility が null なら不可視")
        void nullFeedVisibilityInvisible() {
            assertThat(AnnouncementVisibility.isVisibleTo(null, "SYSTEM_ADMIN")).isFalse();
        }
    }
}
