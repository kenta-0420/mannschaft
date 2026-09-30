package com.mannschaft.app.common.i18n;

import com.mannschaft.app.notification.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F01.2.1 のバックエンド i18n・通知種別の番人（試練・AC-G118 BE 側）。
 *
 * <ul>
 *   <li>エラーメッセージ {@code error.org.064-070 / error.team.064-072 / error.broadcast.006-013} と
 *       {@code notification.teamAffiliation.*}（設計書 §14.2）の全キーが、
 *       7本すべての {@code messages*.properties} にある</li>
 *   <li>通知種別9つ（設計書 §6.7）が {@link NotificationType} に定義され、ラベルキーが基幹3本にある</li>
 * </ul>
 */
@DisplayName("F01.2.1 BE i18n キー・通知種別の番人（AC-G118）")
class TeamAffiliationMessageKeysGuardTest {

    private static final List<String> RESOURCE_NAMES = List.of(
            "messages.properties",
            "messages_ja.properties",
            "messages_en.properties",
            "messages_zh.properties",
            "messages_ko.properties",
            "messages_es.properties",
            "messages_de.properties");

    /** ラベルキーの必須バンドル（NotificationTypeLabelGuardTest と同じ範囲）。 */
    private static final List<String> LABEL_BUNDLES = List.of(
            "messages.properties", "messages_ja.properties", "messages_en.properties");

    /** 設計書 §6.7 の通知種別9つ。 */
    static final List<String> NOTIFICATION_TYPES = List.of(
            "TEAM_ORG_APPLICATION_RECEIVED",
            "TEAM_ORG_APPLICATION_APPROVED",
            "TEAM_ORG_APPLICATION_REJECTED",
            "TEAM_ORG_INVITE_RECEIVED",
            "TEAM_ORG_INVITE_ACCEPTED",
            "TEAM_ORG_PENDING_EXPIRED",
            "TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM",
            "TEAM_ORG_MEMBERSHIP_LEFT",
            "TEAM_ORG_MEMBERSHIP_REMOVED");

    /** 設計書 §14.2 の通知文面キー（19本）。 */
    static final List<String> NOTIFICATION_KEYS = List.of(
            "notification.teamAffiliation.applicationReceived.title",
            "notification.teamAffiliation.applicationReceived.body",
            "notification.teamAffiliation.applicationApproved.title",
            "notification.teamAffiliation.applicationApproved.body",
            "notification.teamAffiliation.applicationRejected.title",
            "notification.teamAffiliation.applicationRejected.body",
            "notification.teamAffiliation.applicationRejected.bodyWithReason",
            "notification.teamAffiliation.inviteReceived.title",
            "notification.teamAffiliation.inviteReceived.body",
            "notification.teamAffiliation.inviteAccepted.title",
            "notification.teamAffiliation.inviteAccepted.body",
            "notification.teamAffiliation.pendingExpired.title",
            "notification.teamAffiliation.pendingExpired.body",
            "notification.teamAffiliation.pendingCancelledBySystem.title",
            "notification.teamAffiliation.pendingCancelledBySystem.body",
            "notification.teamAffiliation.membershipLeft.title",
            "notification.teamAffiliation.membershipLeft.body",
            "notification.teamAffiliation.membershipRemoved.title",
            "notification.teamAffiliation.membershipRemoved.body",
            "notification.teamAffiliation.group.unassigned");

    /** 設計書 §11 / §14.2 のエラーメッセージキー（24本）。 */
    static final List<String> ERROR_KEYS = Stream.of(
                    range("error.org.", 64, 70),
                    range("error.team.", 64, 72),
                    range("error.broadcast.", 6, 13))
            .flatMap(s -> s)
            .collect(Collectors.toList());

    private static Stream<String> range(String prefix, int from, int to) {
        return IntStream.rangeClosed(from, to).mapToObj(n -> prefix + String.format("%03d", n));
    }

    private static List<String> allKeys() {
        List<String> all = new ArrayList<>(ERROR_KEYS);
        all.addAll(NOTIFICATION_KEYS);
        return all;
    }

    @Test
    @DisplayName("空虚 green 防止: キー一覧の件数（error 24 + notification 20）")
    void キー件数() {
        assertThat(ERROR_KEYS).hasSize(7 + 9 + 8);
        assertThat(NOTIFICATION_KEYS).hasSize(20);
        assertThat(NOTIFICATION_TYPES).hasSize(9);
    }

    @Test
    @DisplayName("AC-G118: error.* と notification.teamAffiliation.* の全キーが 7 本すべての messages*.properties にある")
    void 全ロケールに全キーが存在する() throws IOException {
        Map<String, Properties> bundles = loadAll(RESOURCE_NAMES);
        List<String> missing = findMissing(bundles, allKeys());
        assertThat(missing)
                .as("F01.2.1 の i18n キーが欠けているロケールがある（%d 件）: %s", missing.size(), missing)
                .isEmpty();
    }

    @Test
    @DisplayName("AC-G118: 通知種別9つが NotificationType に定義され、ラベルキーが基幹3本にある")
    void 通知種別が定義されラベルがある() throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, Properties> bundles = loadAll(LABEL_BUNDLES);
        for (String name : NOTIFICATION_TYPES) {
            NotificationType type = null;
            try {
                type = NotificationType.valueOf(name);
            } catch (IllegalArgumentException e) {
                problems.add(name + ": NotificationType に未定義");
            }
            if (type != null) {
                problems.addAll(findMissing(bundles, List.of(type.getLabelKey())));
            }
        }
        assertThat(problems).as("通知種別の未実装・ラベル欠落: %s", problems).isEmpty();
    }

    @Test
    @DisplayName("自己検証: 1キーだけ欠いたロケールがあれば findMissing はそのキーを報告する（偽陰性防止）")
    void 自己検証_欠落を検出する() {
        Properties full = new Properties();
        Properties lacking = new Properties();
        for (String key : allKeys()) {
            full.setProperty(key, "v");
            lacking.setProperty(key, "v");
        }
        String dropped = "notification.teamAffiliation.membershipRemoved.body";
        lacking.remove(dropped);
        Map<String, Properties> bundles = new LinkedHashMap<>();
        bundles.put("full", full);
        bundles.put("lacking", lacking);

        assertThat(findMissing(bundles, allKeys())).containsExactly("lacking → " + dropped);

        Properties blank = new Properties();
        blank.putAll(full);
        blank.setProperty(dropped, "  ");
        assertThat(findMissing(Map.of("blank", blank), allKeys()))
                .as("空白値も欠落扱い").containsExactly("blank → " + dropped);
        assertThat(findMissing(Map.of("full", full), allKeys())).isEmpty();
    }

    static List<String> findMissing(Map<String, Properties> bundles, List<String> keys) {
        List<String> missing = new ArrayList<>();
        bundles.forEach((name, props) -> {
            for (String key : keys) {
                String value = props.getProperty(key);
                if (value == null || value.isBlank()) {
                    missing.add(name + " → " + key);
                }
            }
        });
        return missing;
    }

    private static Map<String, Properties> loadAll(List<String> names) throws IOException {
        Map<String, Properties> map = new LinkedHashMap<>();
        for (String name : names) {
            Properties props = new Properties();
            try (InputStream in = TeamAffiliationMessageKeysGuardTest.class.getClassLoader()
                    .getResourceAsStream(name)) {
                assertThat(in).as("リソースが見つからない: %s", name).isNotNull();
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }
            map.put(name, props);
        }
        return map;
    }
}
