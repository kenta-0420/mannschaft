package com.mannschaft.app.schedule.service;

import com.mannschaft.app.notification.fanout.FanoutMessageKind;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.membership.fanout.ScheduleKeepTeamFanoutRecipientSource;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.schedule.authz.ScheduleKeepScope;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CMP-017c: {@link ScheduleKeepNotificationService#notifyConverted} が「変換された」通知を
 * TEAM スコープの MEMBER 以上 全員（操作者除く）へ耐久 fan-out で配信する契約の単体テスト。
 *
 * <p>母集団の実解決（keyset・SUPPORTER 除外）は {@link ScheduleKeepTeamFanoutRecipientSourceIT} が
 * 実 DB で担保する。本テストはサービス層の分岐——PERSONAL 無通知（AC-5）・作成者可視性ガード（AC-4）・
 * 操作者除外を scope_ref に埋め込む（AC-3）・enqueue 引数（AC-1）・best-effort 順序（AC-9）——を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CMP-017c キープ変換通知 fan-out 結線 単体テスト")
class ScheduleKeepNotificationServiceTest {

    @Mock
    private com.mannschaft.app.notification.service.NotificationService notificationService;
    @Mock
    private ContentVisibilityChecker visibilityChecker;
    @Mock
    private TeamService teamService;
    @Mock
    private NotificationFanoutJobService fanoutJobService;

    private ScheduleKeepNotificationService service() {
        return new ScheduleKeepNotificationService(notificationService, visibilityChecker, teamService, fanoutJobService);
    }

    private static final long TEAM_ID = 42L;
    private static final long ACTOR_ID = 7L;
    private static final long CREATOR_ID = 3L;

    private ScheduleKeepEntity teamKeep(Long createdBy) {
        ScheduleKeepEntity keep = ScheduleKeepEntity.builder()
                .teamId(TEAM_ID)
                .title("夏合宿")
                .status(ScheduleKeepStatus.SCHEDULED)
                .sortOrder(0)
                .createdBy(createdBy)
                .build();
        keep.setId(UUID.randomUUID());
        return keep;
    }

    private ScheduleEntity schedule() {
        return ScheduleEntity.builder().id(9001L).teamId(TEAM_ID).title("夏合宿").build();
    }

    // ── AC-5: PERSONAL は通知を出さない（fan-out も creator 直送もしない） ──
    @Test
    @DisplayName("AC-5: PERSONAL スコープのキープ変換は通知（fan-out・creator 直送）を一切出さない")
    void ac5_personalScopeEmitsNothing() {
        ScheduleKeepScope scope = ScheduleKeepScope.personal(100L);
        ScheduleKeepEntity keep = ScheduleKeepEntity.builder()
                .userId(100L).title("個人キープ").status(ScheduleKeepStatus.SCHEDULED).sortOrder(0).createdBy(100L)
                .build();
        keep.setId(UUID.randomUUID());

        service().notifyConverted(scope, keep, schedule(), 100L);

        verifyNoInteractions(fanoutJobService);
        verifyNoInteractions(notificationService);
    }

    // ── AC-1 / AC-3: TEAM は MEMBER 全員へ fan-out enqueue（操作者を scope_ref に埋めて除外） ──
    @Test
    @DisplayName("AC-1/AC-3: TEAM 変換で SCHEDULE_KEEP_TEAM の fan-out を enqueue し、scope_ref に操作者・作成者を埋める")
    void ac1_ac3_teamEnqueuesFanoutExcludingActorAndCreator() {
        ScheduleKeepScope scope = ScheduleKeepScope.team(TEAM_ID);
        ScheduleKeepEntity keep = teamKeep(CREATOR_ID);
        when(visibilityChecker.canViewUuid(eq(ReferenceType.SCHEDULE_KEEP), eq(keep.getId()), eq(CREATOR_ID)))
                .thenReturn(true);
        when(teamService.getSlugById(TEAM_ID)).thenReturn("natsu");

        service().notifyConverted(scope, keep, schedule(), ACTOR_ID);

        ArgumentCaptor<String> scopeType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> scopeRef = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> notifType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> sourceEvent = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Long> actorId = ArgumentCaptor.forClass(Long.class);
        verify(fanoutJobService).enqueue(scopeType.capture(), scopeRef.capture(), notifType.capture(),
                sourceEvent.capture(), isNull(),any(FanoutMessageKind.class), any(String[].class), any(NotificationPriority.class),
                anyString(), anyLong(), anyString(), actorId.capture());

        assertThat(scopeType.getValue()).isEqualTo(ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE);
        assertThat(scopeRef.getValue())
                .as("scope_ref は teamId:actorId:creatorId（操作者・作成者を母集団から除く）")
                .isEqualTo(TEAM_ID + ":" + ACTOR_ID + ":" + CREATOR_ID);
        assertThat(notifType.getValue()).isEqualTo("SCHEDULE_KEEP_CONVERTED");
        assertThat(sourceEvent.getValue()).as("冪等キーはキープ ID").isEqualTo(keep.getId());
        assertThat(actorId.getValue()).isEqualTo(ACTOR_ID);

        // 作成者必達（creator は母集団から除外する代わりに直送で必ず受領）。
        verify(notificationService).createNotificationPreAuthorized(eq(CREATOR_ID), eq("SCHEDULE_KEEP_CONVERTED"),
                any(NotificationPriority.class), anyString(), anyString(),
                anyString(), anyLong(), eq(NotificationScopeType.TEAM), eq(TEAM_ID), anyString(), eq(ACTOR_ID));
    }

    // ── AC-4: 作成者が閲覧権喪失なら creator 直送はスキップ（が、他 MEMBER への fan-out は継続） ──
    @Test
    @DisplayName("AC-4: 作成者に閲覧権が無い場合 creator 直送はスキップするが、他 MEMBER への fan-out は継続する")
    void ac4_creatorNotVisibleSkipsDirectButStillFansOut() {
        ScheduleKeepScope scope = ScheduleKeepScope.team(TEAM_ID);
        ScheduleKeepEntity keep = teamKeep(CREATOR_ID);
        when(visibilityChecker.canViewUuid(eq(ReferenceType.SCHEDULE_KEEP), eq(keep.getId()), eq(CREATOR_ID)))
                .thenReturn(false);
        when(teamService.getSlugById(TEAM_ID)).thenReturn("natsu");

        service().notifyConverted(scope, keep, schedule(), ACTOR_ID);

        verify(notificationService, never()).createNotificationPreAuthorized(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(fanoutJobService).enqueue(eq(ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE), anyString(),
                anyString(), any(UUID.class), isNull(),any(FanoutMessageKind.class), any(String[].class), any(NotificationPriority.class),
                anyString(), anyLong(), anyString(), eq(ACTOR_ID));
    }

    // ── AC-4b: created_by が NULL（匿名化済み）でも fan-out は出す。scope_ref の creator 位置は 0 ──
    @Test
    @DisplayName("AC-4b: created_by=NULL（匿名化済み）でも fan-out は出す。creator 直送はしない・scope_ref creator=0")
    void ac4b_nullCreatorStillFansOutWithSentinel() {
        ScheduleKeepScope scope = ScheduleKeepScope.team(TEAM_ID);
        ScheduleKeepEntity keep = teamKeep(null);
        when(teamService.getSlugById(TEAM_ID)).thenReturn("natsu");

        service().notifyConverted(scope, keep, schedule(), ACTOR_ID);

        verifyNoInteractions(notificationService);
        ArgumentCaptor<String> scopeRef = ArgumentCaptor.forClass(String.class);
        verify(fanoutJobService).enqueue(eq(ScheduleKeepTeamFanoutRecipientSource.SCOPE_TYPE), scopeRef.capture(),
                anyString(), any(UUID.class), isNull(),any(FanoutMessageKind.class), any(String[].class), any(NotificationPriority.class),
                anyString(), anyLong(), anyString(), eq(ACTOR_ID));
        assertThat(scopeRef.getValue()).isEqualTo(TEAM_ID + ":" + ACTOR_ID + ":0");
    }

    // ── AC-9: enqueue 前に creator 直送を済ませる（fan-out enqueue 失敗でも作成者は受領） ──
    @Test
    @DisplayName("AC-9: creator 直送は fan-out enqueue より先に行い、enqueue 失敗でも作成者は通知済みになる")
    void ac9_creatorNotifiedBeforeFanoutEnqueueFailure() {
        ScheduleKeepScope scope = ScheduleKeepScope.team(TEAM_ID);
        ScheduleKeepEntity keep = teamKeep(CREATOR_ID);
        when(visibilityChecker.canViewUuid(eq(ReferenceType.SCHEDULE_KEEP), eq(keep.getId()), eq(CREATOR_ID)))
                .thenReturn(true);
        when(teamService.getSlugById(TEAM_ID)).thenReturn("natsu");
        doThrow(new RuntimeException("enqueue 失敗（DB 一時障害）"))
                .when(fanoutJobService).enqueue(anyString(), anyString(), anyString(), any(UUID.class), isNull(),any(FanoutMessageKind.class), any(String[].class), any(NotificationPriority.class), anyString(), anyLong(),
                        anyString(), anyLong());

        // best-effort: enqueue の失敗は呼び出し側が握る契約のため、ここでは伝播してよい。
        // Issue #2990 L8 以降、その呼び出し側は業務TX内の ScheduleKeepService ではなく
        // AFTER_COMMIT の ScheduleKeepConvertedNotificationListener であり、握っても
        // 巻き添えにする業務トランザクションはもう存在しない。
        assertThatThrownBy(() -> service().notifyConverted(scope, keep, schedule(), ACTOR_ID))
                .isInstanceOf(RuntimeException.class);

        // 作成者直送が enqueue より先に行われていること（enqueue が落ちても作成者は受領済み）。
        InOrder order = inOrder(notificationService, fanoutJobService);
        order.verify(notificationService).createNotificationPreAuthorized(eq(CREATOR_ID), anyString(),
                any(NotificationPriority.class), anyString(), anyString(),
                anyString(), anyLong(), any(NotificationScopeType.class), anyLong(), anyString(), anyLong());
        order.verify(fanoutJobService).enqueue(anyString(), anyString(), anyString(), any(UUID.class), isNull(),any(FanoutMessageKind.class), any(String[].class), any(NotificationPriority.class), anyString(), anyLong(), anyString(), anyLong());
    }
}
