package com.mannschaft.app.member.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.event.DomainEvent;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.member.dto.UpdateMemberSubtabVisibilityRequest;
import com.mannschaft.app.member.entity.MemberSubtabRoleVisibilityEntity;
import com.mannschaft.app.member.event.MemberSubtabVisibilityUpdatedEvent;
import com.mannschaft.app.member.repository.MemberSubtabRoleVisibilityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * PR #3387 D-3T 根治 試練 — AC-D3T-14a（軍議書 gungi-3387-d3t.md 第3版 §7）。
 *
 * <p>書き込み専用 Bean {@link MemberSubtabVisibilityWriter} が発行する
 * {@link MemberSubtabVisibilityUpdatedEvent} の中身が、現行 {@code MemberSubtabVisibilityService#recordAuditLog}
 * （修正前 366 行）が {@code AuditLogService#record} へ渡していた値と一字一句同じであることを固定する。</p>
 *
 * <p>組み立て（出陣で合わせること）: {@code new MemberSubtabVisibilityWriter(repository, domainEventPublisher,
 * objectMapper)}、{@code applyUpdates(ScopeType, Long scopeId, Long actorUserId,
 * List<SubtabVisibilityUpdateItem>)}。イベントの getter は {@code getActorUserId / getTeamId /
 * getOrganizationId / getMetadataJson}。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PR #3387 D-3T 試練 AC-14a: 監査イベントの中身は現行と一字一句同じ")
class MemberSubtabVisibilityWriterTest {

    private static final Long ACTOR = 1L;
    private static final Long ORG_ID = 200L;
    private static final Long TEAM_ID = 300L;

    @Mock private MemberSubtabRoleVisibilityRepository repository;
    @Mock private DomainEventPublisher domainEventPublisher;

    private static List<UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem> items(
            String subtabKey, MinRole minRole) {
        UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem item =
                new UpdateMemberSubtabVisibilityRequest.SubtabVisibilityUpdateItem();
        item.setSubtabKey(subtabKey);
        item.setMinRole(minRole);
        return List.of(item);
    }

    private MemberSubtabVisibilityUpdatedEvent publishedEvent() {
        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(domainEventPublisher).publish(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(MemberSubtabVisibilityUpdatedEvent.class);
        return (MemberSubtabVisibilityUpdatedEvent) captor.getValue();
    }

    @Test
    @DisplayName("組織スコープ: organizationId だけ値があり、metadataJson は現行の文字列と完全一致")
    void 組織スコープのイベントは現行と同じ() {
        given(repository.findByScopeTypeAndScopeIdAndSubtabKey(ScopeType.ORGANIZATION, ORG_ID, "member_profiles"))
                .willReturn(Optional.empty());
        MemberSubtabVisibilityWriter writer =
                new MemberSubtabVisibilityWriter(repository, domainEventPublisher, new ObjectMapper());

        writer.applyUpdates(ScopeType.ORGANIZATION, ORG_ID, ACTOR, items("member_profiles", MinRole.PUBLIC));

        MemberSubtabVisibilityUpdatedEvent event = publishedEvent();
        assertThat(event.getActorUserId()).isEqualTo(ACTOR);
        assertThat(event.getTeamId()).isNull();
        assertThat(event.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(event.getMetadataJson()).isEqualTo(
                "{\"scope_type\":\"ORGANIZATION\",\"scope_id\":200,"
                        + "\"changes\":[{\"subtab_key\":\"member_profiles\",\"before\":\"MEMBER\",\"after\":\"PUBLIC\"}]}");
    }

    @Test
    @DisplayName("チームスコープ: teamId だけ値があり、既存行の更新は before=既存値")
    void チームスコープのイベントは現行と同じ() {
        MemberSubtabRoleVisibilityEntity existing = MemberSubtabRoleVisibilityEntity.builder()
                .scopeType(ScopeType.TEAM).scopeId(TEAM_ID)
                .subtabKey("member_profiles").minRole(MinRole.PUBLIC).updatedBy(9L)
                .build();
        given(repository.findByScopeTypeAndScopeIdAndSubtabKey(ScopeType.TEAM, TEAM_ID, "member_profiles"))
                .willReturn(Optional.of(existing));
        MemberSubtabVisibilityWriter writer =
                new MemberSubtabVisibilityWriter(repository, domainEventPublisher, new ObjectMapper());

        writer.applyUpdates(ScopeType.TEAM, TEAM_ID, ACTOR, items("member_profiles", MinRole.SUPPORTER));

        MemberSubtabVisibilityUpdatedEvent event = publishedEvent();
        assertThat(event.getActorUserId()).isEqualTo(ACTOR);
        assertThat(event.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(event.getOrganizationId()).isNull();
        assertThat(event.getMetadataJson()).isEqualTo(
                "{\"scope_type\":\"TEAM\",\"scope_id\":300,"
                        + "\"changes\":[{\"subtab_key\":\"member_profiles\",\"before\":\"PUBLIC\",\"after\":\"SUPPORTER\"}]}");
    }

    @Test
    @DisplayName("ObjectMapper の直列化が失敗したら metadataJson は \"{}\"")
    void 直列化失敗ならmetadataは空オブジェクト() throws Exception {
        given(repository.findByScopeTypeAndScopeIdAndSubtabKey(ScopeType.ORGANIZATION, ORG_ID, "member_profiles"))
                .willReturn(Optional.empty());
        ObjectMapper failing = mock(ObjectMapper.class);
        given(failing.writeValueAsString(any())).willThrow(new JsonProcessingException("AC-14a injected") {
        });
        MemberSubtabVisibilityWriter writer =
                new MemberSubtabVisibilityWriter(repository, domainEventPublisher, failing);

        writer.applyUpdates(ScopeType.ORGANIZATION, ORG_ID, ACTOR, items("member_profiles", MinRole.PUBLIC));

        assertThat(publishedEvent().getMetadataJson()).isEqualTo("{}");
    }

    @Test
    @DisplayName("差分が無ければイベントを発行しない")
    void 差分が無ければ発行しない() {
        given(repository.findByScopeTypeAndScopeIdAndSubtabKey(ScopeType.ORGANIZATION, ORG_ID, "member_profiles"))
                .willReturn(Optional.empty());
        MemberSubtabVisibilityWriter writer =
                new MemberSubtabVisibilityWriter(repository, domainEventPublisher, new ObjectMapper());

        // 紹介の既定値は MEMBER。既存行なしで MEMBER を送る＝差分なし
        writer.applyUpdates(ScopeType.ORGANIZATION, ORG_ID, ACTOR, items("member_profiles", MinRole.MEMBER));

        verify(domainEventPublisher, never()).publish(any());
    }
}
