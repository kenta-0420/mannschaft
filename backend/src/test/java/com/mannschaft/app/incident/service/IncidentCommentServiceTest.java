package com.mannschaft.app.incident.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.incident.IncidentErrorCode;
import com.mannschaft.app.incident.entity.IncidentCommentEntity;
import com.mannschaft.app.incident.entity.IncidentEntity;
import com.mannschaft.app.incident.repository.IncidentCommentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("IncidentCommentService")
class IncidentCommentServiceTest {

    private static final long INCIDENT_ID = 100L;
    private static final long SCOPE_ID = 200L;
    private static final long REPORTER_ID = 300L;
    private static final long ADMIN_ID = 400L;

    @Mock private IncidentService incidentService;
    @Mock private IncidentCommentRepository commentRepository;
    @Mock private IncidentAccessGuard incidentAccessGuard;
    @Mock private NameResolverService nameResolverService;
    @InjectMocks private IncidentCommentService service;

    private IncidentEntity incident;

    @BeforeEach
    void setUp() {
        incident = IncidentEntity.builder()
                .id(INCIDENT_ID)
                .scopeType("TEAM")
                .scopeId(SCOPE_ID)
                .reportedBy(REPORTER_ID)
                .title("漏水")
                .build();
        given(incidentService.findIncidentOrThrow(INCIDENT_ID)).willReturn(incident);
    }

    @Test
    @DisplayName("報告者は内部コメントを SQL 段階で除外して作成日時昇順に取得する")
    void listComments_reporter_excludesInternalAtQueryLayer() {
        given(incidentAccessGuard.requireVisibleOrConceal(incident, REPORTER_ID)).willReturn(false);
        IncidentCommentEntity first = comment(1L, REPORTER_ID, "最初", false);
        IncidentCommentEntity second = comment(2L, ADMIN_ID, "次", false);
        given(commentRepository.findVisibleByIncidentIdOrderByCreatedAtAsc(INCIDENT_ID, false))
                .willReturn(List.of(first, second));
        given(nameResolverService.resolveUserFullNames(any()))
                .willReturn(Map.of(REPORTER_ID, "報告者", ADMIN_ID, "管理者"));

        List<IncidentCommentService.IncidentCommentResponse> result =
                service.listComments(INCIDENT_ID, REPORTER_ID);

        assertThat(result).extracting(IncidentCommentService.IncidentCommentResponse::body)
                .containsExactly("最初", "次");
        assertThat(result.getFirst().user().displayName()).isEqualTo("報告者");
        verify(commentRepository).findVisibleByIncidentIdOrderByCreatedAtAsc(INCIDENT_ID, false);
    }

    @Test
    @DisplayName("ADMIN は内部コメントを含めて取得し、退会済み投稿者は安全な代替表示名にする")
    void listComments_admin_includesInternalAndUsesFallbackName() {
        given(incidentAccessGuard.requireVisibleOrConceal(incident, ADMIN_ID)).willReturn(true);
        IncidentCommentEntity internal = comment(1L, 999L, "内部引継ぎ", true);
        given(commentRepository.findVisibleByIncidentIdOrderByCreatedAtAsc(INCIDENT_ID, true))
                .willReturn(List.of(internal));
        given(nameResolverService.resolveUserFullNames(any())).willReturn(Map.of());

        List<IncidentCommentService.IncidentCommentResponse> result = service.listComments(INCIDENT_ID, ADMIN_ID);

        assertThat(result).singleElement().satisfies(comment -> {
            assertThat(comment.isInternal()).isTrue();
            assertThat(comment.user().displayName()).isEqualTo("不明なユーザー");
        });
        verify(commentRepository).findVisibleByIncidentIdOrderByCreatedAtAsc(INCIDENT_ID, true);
    }

    @Test
    @DisplayName("SYSTEM_ADMIN は所属の有無にかかわらず全コメントを取得する")
    void listComments_systemAdmin_includesInternalWithoutMembership() {
        given(incidentAccessGuard.requireVisibleOrConceal(incident, ADMIN_ID)).willReturn(true);
        given(commentRepository.findVisibleByIncidentIdOrderByCreatedAtAsc(INCIDENT_ID, true))
                .willReturn(List.of(comment(1L, REPORTER_ID, "監査", true)));
        given(nameResolverService.resolveUserFullNames(any())).willReturn(Map.of(REPORTER_ID, "報告者"));

        List<IncidentCommentService.IncidentCommentResponse> result = service.listComments(INCIDENT_ID, ADMIN_ID);

        assertThat(result).singleElement().extracting(IncidentCommentService.IncidentCommentResponse::isInternal)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("非所属者は incident の存在を秘匿して 404 エラーとなりコメントを検索しない")
    void listComments_nonMember_concealsExistence() {
        given(incidentAccessGuard.requireVisibleOrConceal(incident, 999L))
                .willThrow(new BusinessException(IncidentErrorCode.INCIDENT_002));

        assertThatThrownBy(() -> service.listComments(INCIDENT_ID, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(IncidentErrorCode.INCIDENT_002);

        verify(commentRepository, never()).findVisibleByIncidentIdOrderByCreatedAtAsc(any(), any(Boolean.class));
    }

    @Test
    @DisplayName("所属していても報告者・担当者・ADMIN/DEPUTY_ADMIN 以外は 404 とする")
    void listComments_unrelatedMember_concealsExistence() {
        long unrelatedMemberId = 777L;
        given(incidentAccessGuard.requireVisibleOrConceal(incident, unrelatedMemberId))
                .willThrow(new BusinessException(IncidentErrorCode.INCIDENT_002));

        assertThatThrownBy(() -> service.listComments(INCIDENT_ID, unrelatedMemberId))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(IncidentErrorCode.INCIDENT_002);
        verify(commentRepository, never()).findVisibleByIncidentIdOrderByCreatedAtAsc(any(), any(Boolean.class));
    }

    @Test
    @DisplayName("SUPPORTER は報告者本人でもコメントを閲覧できない")
    void listComments_supporterReporter_concealsExistence() {
        given(incidentAccessGuard.requireVisibleOrConceal(incident, REPORTER_ID))
                .willThrow(new BusinessException(IncidentErrorCode.INCIDENT_002));

        assertThatThrownBy(() -> service.listComments(INCIDENT_ID, REPORTER_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(IncidentErrorCode.INCIDENT_002);
        verify(commentRepository, never()).findVisibleByIncidentIdOrderByCreatedAtAsc(any(), any(Boolean.class));
    }

    @Test
    @DisplayName("USER 担当者は共通可視性 guard を通過して取得できる")
    void listComments_userAssigneeIsAllowedByGuard() {
        long assigneeId = 778L;
        given(incidentAccessGuard.requireVisibleOrConceal(incident, assigneeId)).willReturn(false);
        given(commentRepository.findVisibleByIncidentIdOrderByCreatedAtAsc(INCIDENT_ID, false))
                .willReturn(List.of());
        given(nameResolverService.resolveUserFullNames(any())).willReturn(Map.of());

        assertThat(service.listComments(INCIDENT_ID, assigneeId)).isEmpty();
    }

    @Test
    @DisplayName("createdAt はサーバー基準ゾーンを明示した OffsetDateTime で返す")
    void listComments_convertsCreatedAtToOffsetDateTime() {
        given(incidentAccessGuard.requireVisibleOrConceal(incident, ADMIN_ID)).willReturn(true);
        IncidentCommentEntity comment = comment(1L, REPORTER_ID, "timestamp", false).toBuilder()
                .createdAt(LocalDateTime.of(2026, 9, 14, 10, 30))
                .build();
        given(commentRepository.findVisibleByIncidentIdOrderByCreatedAtAsc(INCIDENT_ID, true))
                .willReturn(List.of(comment));
        given(nameResolverService.resolveUserFullNames(any())).willReturn(Map.of());

        List<IncidentCommentService.IncidentCommentResponse> result = service.listComments(INCIDENT_ID, ADMIN_ID);

        assertThat(result.getFirst().createdAt())
                .isEqualTo(OffsetDateTime.parse("2026-09-14T10:30:00+09:00"));
    }

    private IncidentCommentEntity comment(long id, long userId, String body, boolean internal) {
        return IncidentCommentEntity.builder()
                .id(id)
                .incidentId(INCIDENT_ID)
                .userId(userId)
                .body(body)
                .isInternal(internal)
                .build();
    }
}
