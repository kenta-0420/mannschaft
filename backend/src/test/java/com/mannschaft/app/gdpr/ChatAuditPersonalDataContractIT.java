package com.mannschaft.app.gdpr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.AuditLogEntity;
import com.mannschaft.app.auth.repository.AuditLogRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.gdpr.dto.PrefetchedPersonalData;
import com.mannschaft.app.gdpr.service.PersonalDataCollector;
import com.mannschaft.app.gdpr.service.PersonalDataPrefetcher;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-261017-0549 の先行試練。実 Bean・実 MySQL で本人の現行データの収集範囲を検査する。
 * 関連メッセージの他人本文・招待情報・管理用監査属性はエクスポートへ追加しない。
 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("GDPR 本人チャット・監査ログ収集契約")
class ChatAuditPersonalDataContractIT extends AbstractMySqlIntegrationTest {

    private static final long OWNER = 920001L;
    private static final long OTHER = 920002L;
    private static final int OVER_PAGE_LIMIT = 105;
    private static final Set<String> CHAT_FIELDS = Set.of("id", "channelId", "parentId", "rootId", "depth",
            "body", "forwardedFromId", "isEdited", "isSystem", "messageType", "scheduledAt", "scheduledSentAt",
            "postedAsSubjectType", "postedAsSubjectId", "createdAt", "updatedAt");
    private static final Set<String> AUDIT_FIELDS = Set.of("id", "eventType", "ipAddress", "userAgent",
            "sessionHash", "metadata", "createdAt");

    @Autowired private PersonalDataCollector collector;
    @Autowired private PersonalDataPrefetcher prefetcher;
    @Autowired private ChatMessageRepository messages;
    @Autowired private ChatChannelRepository channels;
    @Autowired private AuditLogRepository audits;
    @Autowired private ObjectMapper objectMapper;
    @PersistenceContext private EntityManager em;

    @Test
    void G1_実スキャンのカテゴリがすべて既存exportキーへ登録される() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(PersonalData.class));
        Set<String> missing = new HashSet<>();
        for (var candidate : scanner.findCandidateComponents("com.mannschaft.app")) {
            String category = Class.forName(candidate.getBeanClassName()).getAnnotation(PersonalData.class).category();
            if (!collector.getCategoryKeys().contains(category)) missing.add(category);
        }

        assertThat(missing).isEmpty();
    }

    @Test
    void G2_G3_本人の複数channel返信代表転送を100件超すべて固定16項目で収集する() throws Exception {
        long firstChannel = channel();
        long secondChannel = channel();
        ChatMessageEntity other = messages.save(ChatMessageEntity.builder()
                .channelId(firstChannel).senderId(OTHER).body("対象外の他人本文").build());
        for (int i = 0; i < OVER_PAGE_LIMIT; i++) {
            messages.save(ChatMessageEntity.builder().channelId(i % 2 == 0 ? firstChannel : secondChannel)
                    .senderId(OWNER).body("本人保存本文" + i).build());
        }
        ChatMessageEntity special = messages.save(ChatMessageEntity.builder()
                .channelId(secondChannel).senderId(OWNER).parentId(other.getId()).rootId(other.getId()).depth(1)
                .forwardedFromId(other.getId()).postedAsSubjectType(VillageSubjectType.TEAM).postedAsSubjectId(77L)
                .body("本人が保存した転送本文").isEdited(true).build());
        messages.save(ChatMessageEntity.builder().channelId(firstChannel).senderId(null).body("匿名本文").build());
        messages.save(ChatMessageEntity.builder().channelId(firstChannel).senderId(OTHER)
                .postedAsSubjectType(VillageSubjectType.TEAM).postedAsSubjectId(77L).body("他人の代表投稿").build());
        ChatMessageEntity deleted = messages.save(ChatMessageEntity.builder()
                .channelId(firstChannel).senderId(OWNER).body("削除済み本人本文").build());
        deleted.softDelete();
        em.flush();
        em.clear();

        JsonNode rows = collect("chat_messages", "chat_messages.json", OWNER);

        assertThat(rows.size()).isEqualTo(OVER_PAGE_LIMIT + 1);
        for (JsonNode row : rows) assertThat(fieldNames(row)).isEqualTo(CHAT_FIELDS);
        JsonNode exported = find(rows, special.getId());
        assertThat(exported.path("body").asText()).isEqualTo("本人が保存した転送本文");
        assertThat(exported.path("parentId").asLong()).isEqualTo(other.getId());
        assertThat(exported.path("forwardedFromId").asLong()).isEqualTo(other.getId());
        assertThat(exported.path("postedAsSubjectType").asText()).isEqualTo("TEAM");
        assertThat(exported.path("postedAsSubjectId").asLong()).isEqualTo(77L);
        assertThat(exported.path("isEdited").asBoolean()).isTrue();
        assertThat(exported.get("scheduledAt").isNull()).isTrue();
        assertThat(exported.get("scheduledSentAt").isNull()).isTrue();
        assertThat(rows.toString()).doesNotContain("対象外の他人本文", "匿名本文", "他人の代表投稿", "削除済み本人本文");
    }

    @Test
    void G3_G4_監査は本人actorだけを固定7項目で全件収集しmetadataとnullを保持する() throws Exception {
        for (int i = 0; i < OVER_PAGE_LIMIT; i++) {
            audits.save(AuditLogEntity.builder().userId(OWNER).eventType("LOGIN_SUCCESS").build());
        }
        AuditLogEntity own = audits.save(AuditLogEntity.builder().userId(OWNER).targetUserId(OTHER)
                .teamId(77L).organizationId(88L).eventType("EMAIL_CHANGED")
                .ipAddress("192.0.2.1").userAgent("GDPR contract fixture").sessionHash("fixture-session-hash")
                .metadata("{\"old_email\":\"old@example.invalid\",\"extra\":null}").build());
        audits.save(AuditLogEntity.builder().userId(OTHER).targetUserId(OWNER).eventType("USER_FROZEN").build());
        audits.save(AuditLogEntity.builder().userId(null).targetUserId(OWNER).eventType("USER_ARCHIVED").build());
        em.flush();
        em.clear();

        JsonNode rows = collect("audit_logs", "audit_logs.json", OWNER);

        assertThat(rows.size()).isEqualTo(OVER_PAGE_LIMIT + 1);
        for (JsonNode row : rows) assertThat(fieldNames(row)).isEqualTo(AUDIT_FIELDS);
        JsonNode exported = find(rows, own.getId());
        assertThat(exported.path("eventType").asText()).isEqualTo("EMAIL_CHANGED");
        assertThat(exported.path("ipAddress").asText()).isEqualTo("192.0.2.1");
        assertThat(exported.path("sessionHash").asText()).isEqualTo("fixture-session-hash");
        JsonNode metadata = objectMapper.readTree(exported.path("metadata").asText());
        assertThat(metadata.path("old_email").asText()).isEqualTo("old@example.invalid");
        assertThat(metadata.has("extra") && metadata.get("extra").isNull()).isTrue();
        JsonNode noDetails = rows.get(0).path("eventType").asText().equals("LOGIN_SUCCESS") ? rows.get(0) : rows.get(1);
        assertThat(noDetails.get("metadata").isNull()).isTrue();
        assertThat(noDetails.get("ipAddress").isNull()).isTrue();
        assertThat(noDetails.get("userAgent").isNull()).isTrue();
        assertThat(noDetails.get("sessionHash").isNull()).isTrue();
        assertThat(rows.toString()).doesNotContain("USER_FROZEN", "USER_ARCHIVED");
    }

    @Test
    void G5_対象が0件のカテゴリだけ空配列になる() throws Exception {
        assertThat(collect("chat_messages", "chat_messages.json", OTHER).isEmpty()).isTrue();
        assertThat(collect("audit_logs", "audit_logs.json", OTHER).isEmpty()).isTrue();
    }

    @Test
    void G6_本人chat監査の事前取得欠落を空配列に握りつぶさない() {
        for (String category : Set.of("chat_messages", "audit_logs")) {
            assertThatThrownBy(() -> collector.collect(OWNER, Set.of(category), PrefetchedPersonalData.none()))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void G2_G3_null本人IDを匿名senderやsystemactorの検索へ変換しない() {
        for (String category : Set.of("chat_messages", "audit_logs")) {
            assertThatThrownBy(() -> prefetcher.prefetch(null, Set.of(category)))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Test
    void G5_部分カテゴリは対象だけ事前取得する() {
        PrefetchedPersonalData chat = prefetcher.prefetch(OWNER, Set.of("chat_messages"));
        assertThat(chat.chatMessages()).isNotNull().isEmpty();
        assertThat(chat.auditLogs()).isNull();
        assertThat(chat.teamOrgMemberships()).isNull();

        PrefetchedPersonalData audit = prefetcher.prefetch(OWNER, Set.of("audit_logs"));
        assertThat(audit.auditLogs()).isNotNull().isEmpty();
        assertThat(audit.chatMessages()).isNull();
        assertThat(audit.teamOrgMemberships()).isNull();

        PrefetchedPersonalData unrelated = prefetcher.prefetch(OWNER, Set.of("account"));
        assertThat(unrelated.chatMessages()).isNull();
        assertThat(unrelated.auditLogs()).isNull();
        assertThat(unrelated.teamOrgMemberships()).isNull();
    }

    @Test
    void G5_null空カテゴリは全事前取得カテゴリを含む() {
        for (Set<String> categories : java.util.Arrays.asList(null, Set.<String>of())) {
            PrefetchedPersonalData all = prefetcher.prefetch(OWNER, categories);
            assertThat(all.teamOrgMemberships()).isNotNull().isEmpty();
            assertThat(all.chatMessages()).isNotNull().isEmpty();
            assertThat(all.auditLogs()).isNotNull().isEmpty();
        }
    }

    private long channel() {
        return channels.save(ChatChannelEntity.builder().channelType(ChannelType.DM).createdBy(OWNER).build()).getId();
    }

    private JsonNode collect(String category, String file, long userId) throws Exception {
        var result = collector.collect(userId, Set.of(category), prefetcher.prefetch(userId, Set.of(category)));
        assertThat(result.keySet()).containsExactly(file);
        return objectMapper.readTree(result.get(file));
    }

    private Set<String> fieldNames(JsonNode row) {
        Set<String> fields = new HashSet<>();
        row.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private JsonNode find(JsonNode rows, long id) {
        for (JsonNode row : rows) if (row.path("id").asLong() == id) return row;
        throw new AssertionError("本人の対象行がエクスポートにない: id=" + id);
    }
}
