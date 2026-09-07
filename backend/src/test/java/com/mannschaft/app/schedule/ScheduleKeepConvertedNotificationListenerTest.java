package com.mannschaft.app.schedule;

import com.mannschaft.app.schedule.authz.ScheduleKeepScope;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepEntity;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;
import com.mannschaft.app.schedule.event.ScheduleKeepConvertedEvent;
import com.mannschaft.app.schedule.repository.ScheduleKeepRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleKeepConvertedNotificationListener;
import com.mannschaft.app.schedule.service.ScheduleKeepNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link ScheduleKeepConvertedNotificationListener} の単体テスト（Issue #2990 L8）。
 *
 * <p>是正でキープ変換通知が業務TXの内側からリスナーへ移った結果、<b>スコープが
 * 「リクエストパス由来の {@code ScheduleKeepScope} を受け取る」から
 * 「コミット済みのキープのスコープ列から復元する」へ変わった</b>。復元を誤ると
 * 通知の宛先スコープ・遷移先 URL・fan-out の母集団がまとめてずれるため、
 * 3 スコープすべてについて復元結果を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Issue #2990 L8 キープ変換通知の配送リスナー 単体テスト")
class ScheduleKeepConvertedNotificationListenerTest {

    private static final Long SCHEDULE_ID = 9001L;
    private static final Long TEAM_ID = 42L;
    private static final Long ORG_ID = 77L;
    private static final Long ACTOR_ID = 7L;

    @Mock
    private ScheduleKeepRepository scheduleKeepRepository;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private ScheduleKeepNotificationService scheduleKeepNotificationService;

    @InjectMocks
    private ScheduleKeepConvertedNotificationListener listener;

    private ScheduleKeepEntity keep(Long teamId, Long organizationId, Long userId) {
        ScheduleKeepEntity keep = ScheduleKeepEntity.builder()
                .teamId(teamId)
                .organizationId(organizationId)
                .userId(userId)
                .title("夏合宿")
                .status(ScheduleKeepStatus.SCHEDULED)
                .sortOrder(0)
                .createdBy(3L)
                .build();
        keep.setId(UUID.randomUUID());
        return keep;
    }

    private ScheduleEntity schedule() {
        return ScheduleEntity.builder().id(SCHEDULE_ID).teamId(TEAM_ID).title("夏合宿").build();
    }

    private ScheduleKeepScope deliverAndCaptureScope(ScheduleKeepEntity keep) {
        given(scheduleKeepRepository.findById(keep.getId())).willReturn(Optional.of(keep));
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule()));

        listener.onScheduleKeepConverted(
                new ScheduleKeepConvertedEvent(keep.getId(), SCHEDULE_ID, ACTOR_ID));

        ArgumentCaptor<ScheduleKeepScope> scope = ArgumentCaptor.forClass(ScheduleKeepScope.class);
        verify(scheduleKeepNotificationService).notifyConverted(
                scope.capture(), any(ScheduleKeepEntity.class), any(ScheduleEntity.class), anyLong());
        return scope.getValue();
    }

    @Test
    @DisplayName("TEAM のキープは TEAM スコープとして復元する（組織 ID を併せ持っていても TEAM が勝つ）")
    void teamスコープを復元する() {
        // team_id と organization_id の両方を持つ形（チームは組織に属する）でも TEAM と判定すること。
        assertThat(deliverAndCaptureScope(keep(TEAM_ID, ORG_ID, null)))
                .isEqualTo(ScheduleKeepScope.team(TEAM_ID));
    }

    @Test
    @DisplayName("ORGANIZATION のキープは ORGANIZATION スコープとして復元する")
    void 組織スコープを復元する() {
        assertThat(deliverAndCaptureScope(keep(null, ORG_ID, null)))
                .isEqualTo(ScheduleKeepScope.organization(ORG_ID));
    }

    @Test
    @DisplayName("PERSONAL のキープは PERSONAL スコープとして復元する")
    void 個人スコープを復元する() {
        assertThat(deliverAndCaptureScope(keep(null, null, 100L)))
                .isEqualTo(ScheduleKeepScope.personal(100L));
    }

    @Test
    @DisplayName("キープまたは予定が読み直せない場合は配送を中止する")
    void 読み直せない場合は配送しない() {
        UUID keepId = UUID.randomUUID();
        given(scheduleKeepRepository.findById(keepId)).willReturn(Optional.empty());
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule()));

        listener.onScheduleKeepConverted(new ScheduleKeepConvertedEvent(keepId, SCHEDULE_ID, ACTOR_ID));

        verify(scheduleKeepNotificationService, never())
                .notifyConverted(any(), any(), any(), any());
    }

    @Test
    @DisplayName("通知（fan-out enqueue を含む）が例外を投げても呼び出し元へは伝播させない")
    void 配送失敗は呼び出し元へ伝播しない() {
        ScheduleKeepEntity k = keep(TEAM_ID, null, null);
        given(scheduleKeepRepository.findById(k.getId())).willReturn(Optional.of(k));
        given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(schedule()));
        willThrow(new RuntimeException("fan-out enqueue 失敗（DB 一時障害）"))
                .given(scheduleKeepNotificationService).notifyConverted(any(), any(), any(), any());

        assertThatCode(() -> listener.onScheduleKeepConverted(
                new ScheduleKeepConvertedEvent(k.getId(), SCHEDULE_ID, ACTOR_ID)))
                .as("是正前はこの失敗が業務TXを汚染し、キープ変換ごと巻き戻っていた")
                .doesNotThrowAnyException();
    }
}
