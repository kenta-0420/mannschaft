package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.common.i18n.DeliveryLocales;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 §6.7・§14.2: 加盟の通知の文面種別（{@link FanoutMessageKind}）の試練。
 *
 * <p>加盟の通知種別9つそれぞれに文面種別があり、§14.2 の文面キーを使い、2つの引数（組織名・チーム名など）が
 * 6配信ロケールすべての本文にそのまま差し込まれることを確かめる。拒否は理由の有無で本文キーが分かれるため、
 * 理由ありの種別も併せて確かめる。</p>
 */
@DisplayName("F01.2.1 加盟の通知の文面種別")
class FanoutMessageKindTeamAffiliationTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d}");

    /** notification_type（§6.7）→ 本文キー（§14.2）。 */
    private static final Map<FanoutMessageKind, String> BODY_KEYS = Map.of(
            FanoutMessageKind.TEAM_ORG_APPLICATION_RECEIVED, "notification.teamAffiliation.applicationReceived.body",
            FanoutMessageKind.TEAM_ORG_APPLICATION_APPROVED, "notification.teamAffiliation.applicationApproved.body",
            FanoutMessageKind.TEAM_ORG_APPLICATION_REJECTED, "notification.teamAffiliation.applicationRejected.body",
            FanoutMessageKind.TEAM_ORG_APPLICATION_REJECTED_WITH_REASON,
            "notification.teamAffiliation.applicationRejected.bodyWithReason",
            FanoutMessageKind.TEAM_ORG_INVITE_RECEIVED, "notification.teamAffiliation.inviteReceived.body",
            FanoutMessageKind.TEAM_ORG_INVITE_ACCEPTED, "notification.teamAffiliation.inviteAccepted.body",
            FanoutMessageKind.TEAM_ORG_PENDING_EXPIRED, "notification.teamAffiliation.pendingExpired.body",
            FanoutMessageKind.TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM,
            "notification.teamAffiliation.pendingCancelledBySystem.body",
            FanoutMessageKind.TEAM_ORG_MEMBERSHIP_LEFT, "notification.teamAffiliation.membershipLeft.body",
            FanoutMessageKind.TEAM_ORG_MEMBERSHIP_REMOVED, "notification.teamAffiliation.membershipRemoved.body");

    @Test
    @DisplayName("§6.7 の加盟の通知種別9つと同名の文面種別があり、§14.2 のキーを使う")
    void 加盟の通知種別9つに同名の文面種別がある() {
        Set<String> names = Set.of(
                "TEAM_ORG_APPLICATION_RECEIVED", "TEAM_ORG_APPLICATION_APPROVED", "TEAM_ORG_APPLICATION_REJECTED",
                "TEAM_ORG_INVITE_RECEIVED", "TEAM_ORG_INVITE_ACCEPTED", "TEAM_ORG_PENDING_EXPIRED",
                "TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM", "TEAM_ORG_MEMBERSHIP_LEFT", "TEAM_ORG_MEMBERSHIP_REMOVED");
        for (String name : names) {
            FanoutMessageKind kind = FanoutMessageKind.valueOf(name);
            assertThat(kind.titleKey()).startsWith("notification.teamAffiliation.").endsWith(".title");
        }
        BODY_KEYS.forEach((kind, bodyKey) ->
                assertThat(kind.bodyKey()).as("%s の本文キー", kind).isEqualTo(bodyKey));
    }

    @Test
    @DisplayName("引数の本数は本文の {n} の数と一致し、6ロケールすべてで引数がそのまま差し込まれる")
    void 引数が全ロケールの本文に差し込まれる() {
        FanoutMessageRenderer renderer = new FanoutMessageRenderer(messageSource(), null);
        for (FanoutMessageKind kind : BODY_KEYS.keySet()) {
            List<String> args = kind.argCount() == 2
                    ? List.of("甲組織🏯", "乙チーム（第2）")
                    : List.of("甲組織🏯");
            Map<String, FanoutMessageRenderer.RenderedMessage> rendered =
                    renderer.renderAllLocales(kind, args.toArray(new String[0]));
            assertThat(rendered.keySet()).containsExactlyInAnyOrderElementsOf(DeliveryLocales.TAGS);
            for (String tag : DeliveryLocales.TAGS) {
                String body = rendered.get(tag).body();
                for (String arg : args) {
                    assertThat(body).as("%s/%s: 引数「%s」が差し込まれる", kind, tag, arg).contains(arg);
                }
                assertThat(PLACEHOLDER.matcher(body).find())
                        .as("%s/%s: 埋まらない {n} が残らない（argCount と本文が一致）: %s", kind, tag, body)
                        .isFalse();
            }
        }
    }

    /** 本番 {@code I18nConfig#messageSource} と同一設定。 */
    private static ReloadableResourceBundleMessageSource messageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:messages", "classpath:ValidationMessages", "classpath:email/email");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }
}
