package com.mannschaft.app.inbox.service;

import com.mannschaft.app.inbox.InboxPriority;
import com.mannschaft.app.inbox.InboxSourceType;
import com.mannschaft.app.inbox.service.InboxPriorityNormalizer.NormalizationContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F04.11 {@link InboxPriorityNormalizer} 単体テスト（純粋関数・境界網羅）。
 *
 * <p>設計書 01_data_model.md §3.2 の正規化表をそのまま受け入れ条件化する。
 * TODO_DUE の暦日境界・CONFIRMABLE の 24h 昇格は <b>ユーザー TZ での現在時刻</b>に依存するため、
 * {@link NormalizationContext} で固定時刻・固定 TZ を注入して決定的に検証する
 * （設計書 03_business_logic.md §3）。</p>
 *
 * <p><b>test-first（red 想定）</b>: 本体は三陣で実装する。現段階は
 * {@link UnsupportedOperationException} で全件失敗するのが正しい。</p>
 */
@DisplayName("InboxPriorityNormalizer 単体テスト")
class InboxPriorityNormalizerTest {

    private final InboxPriorityNormalizer normalizer = new InboxPriorityNormalizer();

    /** ユーザー TZ（東京・UTC+9）。暦日判定はこの TZ で行う。 */
    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");

    // ─────────────────────────────────────────────────────────────────
    // NOTIFICATION: そのまま写像
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("NOTIFICATION（そのまま写像）")
    class Notification {

        @Test
        @DisplayName("URGENT → URGENT")
        void urgent() {
            assertThat(normalizer.normalize(InboxSourceType.NOTIFICATION, "URGENT"))
                    .isEqualTo(InboxPriority.URGENT);
        }

        @Test
        @DisplayName("HIGH → HIGH")
        void high() {
            assertThat(normalizer.normalize(InboxSourceType.NOTIFICATION, "HIGH"))
                    .isEqualTo(InboxPriority.HIGH);
        }

        @Test
        @DisplayName("NORMAL → NORMAL")
        void normal() {
            assertThat(normalizer.normalize(InboxSourceType.NOTIFICATION, "NORMAL"))
                    .isEqualTo(InboxPriority.NORMAL);
        }

        @Test
        @DisplayName("LOW → LOW")
        void low() {
            assertThat(normalizer.normalize(InboxSourceType.NOTIFICATION, "LOW"))
                    .isEqualTo(InboxPriority.LOW);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // ANNOUNCEMENT: URGENT→URGENT / IMPORTANT→HIGH / NORMAL→NORMAL
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("ANNOUNCEMENT（URGENT/IMPORTANT/NORMAL の写像）")
    class Announcement {

        @Test
        @DisplayName("URGENT → URGENT")
        void urgent() {
            assertThat(normalizer.normalize(InboxSourceType.ANNOUNCEMENT, "URGENT"))
                    .isEqualTo(InboxPriority.URGENT);
        }

        @Test
        @DisplayName("IMPORTANT → HIGH")
        void importantToHigh() {
            assertThat(normalizer.normalize(InboxSourceType.ANNOUNCEMENT, "IMPORTANT"))
                    .isEqualTo(InboxPriority.HIGH);
        }

        @Test
        @DisplayName("NORMAL → NORMAL")
        void normal() {
            assertThat(normalizer.normalize(InboxSourceType.ANNOUNCEMENT, "NORMAL"))
                    .isEqualTo(InboxPriority.NORMAL);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // MENTION: 一律 HIGH
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("MENTION（一律 HIGH）")
    class Mention {

        @Test
        @DisplayName("優先度概念なし → 常に HIGH（rawPriority=null でも HIGH）")
        void alwaysHigh() {
            assertThat(normalizer.normalize(InboxSourceType.MENTION, null))
                    .isEqualTo(InboxPriority.HIGH);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // TODO_DUE: 期限切れ→URGENT / 当日→HIGH / 3日内→NORMAL / それ以遠→LOW
    //   ユーザー TZ の暦日で判定。基準現在時刻 = 2026-05-31 12:00 (Asia/Tokyo)
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("TODO_DUE（due_date 基準・ユーザー TZ 暦日判定）")
    class TodoDue {

        /** 基準現在時刻: 2026-05-31（日）12:00 JST */
        private final NormalizationContext ctx =
                new NormalizationContext(LocalDateTime.of(2026, 5, 31, 12, 0), TOKYO);

        @Test
        @DisplayName("期限切れ（昨日の期限）→ URGENT")
        void overdue_URGENT() {
            LocalDateTime yesterday = LocalDateTime.of(2026, 5, 30, 23, 59);
            assertThat(normalizer.normalizeTodoDue(yesterday, ctx))
                    .isEqualTo(InboxPriority.URGENT);
        }

        @Test
        @DisplayName("当日 23:59（同一暦日の終端）→ HIGH")
        void today_2359_HIGH() {
            LocalDateTime todayEnd = LocalDateTime.of(2026, 5, 31, 23, 59);
            assertThat(normalizer.normalizeTodoDue(todayEnd, ctx))
                    .isEqualTo(InboxPriority.HIGH);
        }

        @Test
        @DisplayName("翌日 00:00（暦日が変わった直後）→ NORMAL（3 日内）")
        void tomorrow_0000_NORMAL() {
            LocalDateTime tomorrowStart = LocalDateTime.of(2026, 6, 1, 0, 0);
            assertThat(normalizer.normalizeTodoDue(tomorrowStart, ctx))
                    .isEqualTo(InboxPriority.NORMAL);
        }

        @Test
        @DisplayName("3 日後 → NORMAL（3 日内の上端）")
        void threeDaysLater_NORMAL() {
            LocalDateTime threeDays = LocalDateTime.of(2026, 6, 3, 12, 0);
            assertThat(normalizer.normalizeTodoDue(threeDays, ctx))
                    .isEqualTo(InboxPriority.NORMAL);
        }

        @Test
        @DisplayName("4 日後 → LOW（対象外相当・3 日内を超える）")
        void fourDaysLater_LOW() {
            LocalDateTime fourDays = LocalDateTime.of(2026, 6, 4, 0, 0);
            assertThat(normalizer.normalizeTodoDue(fourDays, ctx))
                    .isEqualTo(InboxPriority.LOW);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // CONFIRMABLE: 親 priority 写像 + 未確認かつ締切 24h 以内は URGENT 昇格
    //   基準現在時刻 = 2026-05-31 12:00 JST
    // ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("CONFIRMABLE（親 priority 写像 + 24h 以内昇格）")
    class Confirmable {

        /** 基準現在時刻: 2026-05-31 12:00 JST */
        private final NormalizationContext ctx =
                new NormalizationContext(LocalDateTime.of(2026, 5, 31, 12, 0), TOKYO);

        @Test
        @DisplayName("親 NORMAL・締切なし・未確認 → NORMAL（写像のみ）")
        void parentNormal_noDeadline() {
            assertThat(normalizer.normalizeConfirmable("NORMAL", null, false, ctx))
                    .isEqualTo(InboxPriority.NORMAL);
        }

        @Test
        @DisplayName("親 HIGH・締切 25h 後・未確認 → HIGH（昇格境界外）")
        void parentHigh_deadline25h_notConfirmed_noPromotion() {
            LocalDateTime deadline = ctx.now().plusHours(25);
            assertThat(normalizer.normalizeConfirmable("HIGH", deadline, false, ctx))
                    .isEqualTo(InboxPriority.HIGH);
        }

        @Test
        @DisplayName("親 NORMAL・締切 23h 後・未確認 → URGENT に昇格（24h 以内）")
        void parentNormal_deadline23h_notConfirmed_promoted() {
            LocalDateTime deadline = ctx.now().plusHours(23);
            assertThat(normalizer.normalizeConfirmable("NORMAL", deadline, false, ctx))
                    .isEqualTo(InboxPriority.URGENT);
        }

        @Test
        @DisplayName("親 NORMAL・締切 23h 後・確認済み → NORMAL（確認済みは昇格しない）")
        void parentNormal_deadline23h_confirmed_noPromotion() {
            LocalDateTime deadline = ctx.now().plusHours(23);
            assertThat(normalizer.normalizeConfirmable("NORMAL", deadline, true, ctx))
                    .isEqualTo(InboxPriority.NORMAL);
        }
    }
}
