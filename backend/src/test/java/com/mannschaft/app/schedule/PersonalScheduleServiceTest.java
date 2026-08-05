package com.mannschaft.app.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.schedule.dto.BatchDeleteResponse;
import com.mannschaft.app.schedule.dto.CreatePersonalScheduleRequest;
import com.mannschaft.app.schedule.dto.PersonalScheduleResponse;
import com.mannschaft.app.schedule.dto.UpdatePersonalScheduleRequest;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.repository.PersonalScheduleReminderRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity;
import com.mannschaft.app.schedule.service.PersonalScheduleService;
import com.mannschaft.app.schedule.service.ScheduleAccessGuard;
import com.mannschaft.app.schedule.service.ScheduleRecurrenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link PersonalScheduleService} の単体テスト。
 * 個人スケジュールのCRUD・繰り返し・リマインダー・一括削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalScheduleService 単体テスト")
class PersonalScheduleServiceTest {

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private PersonalScheduleReminderRepository reminderRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private ScheduleRecurrenceService recurrenceService;

    /**
     * 認可ガードは状態を持たない純粋な判定のため、モックではなく実体を注入して
     * 本物の所有者判定を通す（@Spy により @InjectMocks の注入対象になる）。
     */
    @Spy
    private ScheduleAccessGuard scheduleAccessGuard = new ScheduleAccessGuard();

    @InjectMocks
    private PersonalScheduleService personalScheduleService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long SCHEDULE_ID = 1L;
    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 4, 1, 10, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 4, 1, 12, 0);
    /** JST(+09:00) のオフセットを付与した OffsetDateTime（テスト用）。 */
    private static final OffsetDateTime START_ODT = OffsetDateTime.of(2026, 4, 1, 10, 0, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime END_ODT = OffsetDateTime.of(2026, 4, 1, 12, 0, 0, 0, ZoneOffset.ofHours(9));

    private ScheduleEntity createPersonalScheduleEntity() {
        return ScheduleEntity.builder()
                .userId(USER_ID)
                .title("個人予定")
                .description("テスト")
                .location("自宅")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.OTHER)
                .color("#FF0000")
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .minResponseRole(MinResponseRole.ADMIN_ONLY)
                .status(ScheduleStatus.SCHEDULED)
                .attendanceRequired(false)
                .attendanceStatus(AttendanceGenerationStatus.READY)
                .commentOption(CommentOption.HIDDEN)
                .isException(false)
                .createdBy(USER_ID)
                .build();
    }

    private ScheduleEntity createCancelledPersonalScheduleEntity() {
        return ScheduleEntity.builder()
                .userId(USER_ID)
                .title("キャンセル済み")
                .startAt(START)
                .endAt(END)
                .allDay(false)
                .eventType(EventType.OTHER)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.ADMIN_ONLY)
                .status(ScheduleStatus.CANCELLED)
                .isException(false)
                .createdBy(USER_ID)
                .build();
    }

    // ========================================
    // createPersonalSchedule
    // ========================================

    @Nested
    @DisplayName("createPersonalSchedule")
    class CreatePersonalSchedule {

        @Test
        @DisplayName("個人スケジュール作成_正常_保存されてイベント発行される")
        void 個人スケジュール作成_正常_保存されてイベント発行される() {
            // given
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "個人予定", "テスト", "自宅", START_ODT, END_ODT, false, "OTHER", "#FF0000",
                    null, null, null);

            // when
            PersonalScheduleResponse result = personalScheduleService.createPersonalSchedule(req, USER_ID);

            // then
            assertThat(result.getContent().title()).isEqualTo("個人予定");
            assertThat(result.getStatus().status()).isEqualTo("SCHEDULED");
            verify(scheduleRepository).save(any(ScheduleEntity.class));
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("個人スケジュール作成_日付不正_例外スロー")
        void 個人スケジュール作成_日付不正_例外スロー() {
            // given
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());

            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "個人予定", null, null, END_ODT, START_ODT, false, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> personalScheduleService.createPersonalSchedule(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.INVALID_DATE_RANGE);
        }

        @Test
        @DisplayName("個人スケジュール作成_上限超過_例外スロー")
        void 個人スケジュール作成_上限超過_例外スロー() {
            // given
            List<ScheduleEntity> thousandSchedules =
                    java.util.stream.IntStream.range(0, 1000)
                            .mapToObj(i -> createPersonalScheduleEntity())
                            .toList();
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(thousandSchedules);

            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "個人予定", null, null, START_ODT, END_ODT, false, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> personalScheduleService.createPersonalSchedule(req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.PERSONAL_SCHEDULE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("個人スケジュール作成_リマインダー付き_リマインダーも保存される")
        void 個人スケジュール作成_リマインダー付き_リマインダーも保存される() {
            // given
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "個人予定", null, null, START_ODT, END_ODT, false, null, null,
                    List.of(10, 30), null, null);

            // when
            personalScheduleService.createPersonalSchedule(req, USER_ID);

            // then
            verify(reminderRepository).deleteByScheduleId(any());
            verify(reminderRepository).saveAll(any());
        }

        @Test
        @DisplayName("繰り返し+相対リマインダー_各子スケジュールへ複製される")
        void 繰り返し_相対リマインダー_各子スケジュールへ複製される() {
            // given
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // 子スケジュール 2 件（DAILY count=2 に相当）
            ScheduleEntity child1 = ScheduleEntity.builder()
                    .userId(USER_ID)
                    .title("個人予定")
                    .startAt(START.plusDays(1))
                    .endAt(END.plusDays(1))
                    .allDay(false)
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ADMIN_ONLY)
                    .minResponseRole(MinResponseRole.ADMIN_ONLY)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(false)
                    .attendanceStatus(AttendanceGenerationStatus.READY)
                    .commentOption(CommentOption.HIDDEN)
                    .isException(false)
                    .createdBy(USER_ID)
                    .build();
            ScheduleEntity child2 = ScheduleEntity.builder()
                    .userId(USER_ID)
                    .title("個人予定")
                    .startAt(START.plusDays(2))
                    .endAt(END.plusDays(2))
                    .allDay(false)
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ADMIN_ONLY)
                    .minResponseRole(MinResponseRole.ADMIN_ONLY)
                    .status(ScheduleStatus.SCHEDULED)
                    .attendanceRequired(false)
                    .attendanceStatus(AttendanceGenerationStatus.READY)
                    .commentOption(CommentOption.HIDDEN)
                    .isException(false)
                    .createdBy(USER_ID)
                    .build();

            given(scheduleRepository.findByParentScheduleIdOrderByStartAtAsc(org.mockito.ArgumentMatchers.nullable(Long.class)))
                    .willReturn(List.of(child1, child2));

            // 繰り返しルール付きリクエスト（DAILY, count=2, 相対リマインダー [15]）
            com.mannschaft.app.schedule.dto.RecurrenceRuleDto rule =
                    new com.mannschaft.app.schedule.dto.RecurrenceRuleDto(
                            "DAILY", 1, null, "COUNT", null, 2);
            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "個人予定", null, null, START_ODT, END_ODT, false, null, null,
                    List.of(15), null, rule);

            // when
            personalScheduleService.createPersonalSchedule(req, USER_ID);

            // then: 子2件 × リマインダー1件 = saveAll に 2 エンティティが渡される
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<PersonalScheduleReminderEntity>> captor =
                    ArgumentCaptor.forClass((Class) List.class);
            // saveAll は 親リマインダー保存 + 子複製保存 の 2 回呼ばれる
            verify(reminderRepository, org.mockito.Mockito.times(2)).saveAll(captor.capture());
            List<PersonalScheduleReminderEntity> childReminders = captor.getAllValues().get(1);
            assertThat(childReminders).hasSize(2);
            assertThat(childReminders).allSatisfy(r -> {
                assertThat(r.getReminderKind()).isEqualTo(ReminderKind.RELATIVE);
                assertThat(r.getRemindBeforeMinutes()).isEqualTo(15);
            });
        }

        @Test
        @DisplayName("繰り返し+絶対リマインダーのみ_子へは複製されない")
        void 繰り返し_絶対リマインダーのみ_子へは複製されない() {
            // given
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            com.mannschaft.app.schedule.dto.RecurrenceRuleDto rule =
                    new com.mannschaft.app.schedule.dto.RecurrenceRuleDto(
                            "DAILY", 1, null, "COUNT", null, 2);

            // 相対リマインダーは null、絶対リマインダーのみ指定
            OffsetDateTime absReminder = OffsetDateTime.of(2026, 4, 1, 9, 0, 0, 0, ZoneOffset.ofHours(9));
            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "個人予定", null, null, START_ODT, END_ODT, false, null, null,
                    null, List.of(absReminder), rule);

            // when
            personalScheduleService.createPersonalSchedule(req, USER_ID);

            // then: findByParentScheduleIdOrderByStartAtAsc は呼ばれない（子への複製をスキップ）
            verify(scheduleRepository, never()).findByParentScheduleIdOrderByStartAtAsc(org.mockito.ArgumentMatchers.nullable(Long.class));
        }
    }

    // ========================================
    // createPersonalSchedule - タイムゾーン変換
    // ========================================

    @Nested
    @DisplayName("createPersonalSchedule_タイムゾーン変換")
    class CreatePersonalScheduleTimezoneConversion {

        @Test
        @DisplayName("UTC入力_JST(+9h)に変換してEntityに保存される")
        void UTC入力_JSTに変換してEntityに保存される() {
            // given: UTC 01:00 = JST 10:00
            OffsetDateTime startUtc = OffsetDateTime.of(2026, 4, 1, 1, 0, 0, 0, ZoneOffset.UTC);
            OffsetDateTime endUtc = OffsetDateTime.of(2026, 4, 1, 3, 0, 0, 0, ZoneOffset.UTC);
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            ScheduleEntity[] saved = new ScheduleEntity[1];
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> {
                        saved[0] = invocation.getArgument(0);
                        return saved[0];
                    });

            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "UTC入力テスト", null, null, startUtc, endUtc, false, null, null, null, null, null);

            // when
            personalScheduleService.createPersonalSchedule(req, USER_ID);

            // then: UTC 01:00 は JST 10:00 に変換される
            assertThat(saved[0].getStartAt())
                    .isEqualTo(LocalDateTime.of(2026, 4, 1, 10, 0, 0));
            assertThat(saved[0].getEndAt())
                    .isEqualTo(LocalDateTime.of(2026, 4, 1, 12, 0, 0));
        }

        @Test
        @DisplayName("JST入力_そのままEntityに保存される")
        void JST入力_そのままEntityに保存される() {
            // given: JST 10:00 はそのまま 10:00 として保存される
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());
            ScheduleEntity[] saved = new ScheduleEntity[1];
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> {
                        saved[0] = invocation.getArgument(0);
                        return saved[0];
                    });

            CreatePersonalScheduleRequest req = new CreatePersonalScheduleRequest(
                    "JST入力テスト", null, null, START_ODT, END_ODT, false, null, null, null, null, null);

            // when
            personalScheduleService.createPersonalSchedule(req, USER_ID);

            // then: JST 10:00 → 変換後も 10:00
            assertThat(saved[0].getStartAt()).isEqualTo(START);
            assertThat(saved[0].getEndAt()).isEqualTo(END);
        }
    }

    // ========================================
    // listPersonalSchedules
    // ========================================

    @Nested
    @DisplayName("listPersonalSchedules")
    class ListPersonalSchedules {

        @Test
        @DisplayName("一覧取得_正常_スケジュール一覧を返す")
        void 一覧取得_正常_スケジュール一覧を返す() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(USER_ID, START, END))
                    .willReturn(List.of(entity));

            // when
            List<PersonalScheduleResponse> result =
                    personalScheduleService.listPersonalSchedules(USER_ID, START, END, null, null, null, 20);

            // then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getContent().title()).isEqualTo("個人予定");
        }

        @Test
        @DisplayName("一覧取得_キーワード検索_一致するもののみ返す")
        void 一覧取得_キーワード検索_一致するもののみ返す() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(USER_ID, START, END))
                    .willReturn(List.of(entity));

            // when
            List<PersonalScheduleResponse> result =
                    personalScheduleService.listPersonalSchedules(USER_ID, START, END, "個人", null, null, 20);

            // then
            assertThat(result).hasSize(1);
        }

        @Test
        @DisplayName("一覧取得_キーワード不一致_空リスト")
        void 一覧取得_キーワード不一致_空リスト() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(USER_ID, START, END))
                    .willReturn(List.of(entity));

            // when
            List<PersonalScheduleResponse> result =
                    personalScheduleService.listPersonalSchedules(USER_ID, START, END, "存在しない", null, null, 20);

            // then
            assertThat(result).isEmpty();
        }
    }

    // ========================================
    // getPersonalSchedule
    // ========================================

    @Nested
    @DisplayName("getPersonalSchedule")
    class GetPersonalSchedule {

        @Test
        @DisplayName("詳細取得_正常_レスポンスを返す")
        void 詳細取得_正常_レスポンスを返す() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(reminderRepository.findByScheduleIdOrderByRemindBeforeMinutesAsc(SCHEDULE_ID))
                    .willReturn(List.of());

            // when
            PersonalScheduleResponse result = personalScheduleService.getPersonalSchedule(SCHEDULE_ID, USER_ID);

            // then
            assertThat(result.getContent().title()).isEqualTo("個人予定");
        }

        @Test
        @DisplayName("詳細取得_相対と絶対の両リマインダーがdetailedRemindersに載る（機能55第三陣）")
        void 詳細取得_相対と絶対両リマインダーが載る() {
            // given: 相対(30分前) + 絶対(固定日時) の2件
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            LocalDateTime absoluteAt = LocalDateTime.of(2026, 4, 1, 9, 0);
            com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity relative =
                    com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity.builder()
                            .scheduleId(SCHEDULE_ID)
                            .reminderKind(ReminderKind.RELATIVE)
                            .remindBeforeMinutes(30)
                            .notified(false)
                            .build();
            com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity absolute =
                    com.mannschaft.app.schedule.entity.PersonalScheduleReminderEntity.builder()
                            .scheduleId(SCHEDULE_ID)
                            .reminderKind(ReminderKind.ABSOLUTE)
                            .remindAt(absoluteAt)
                            .notified(true)
                            .build();
            given(reminderRepository.findByScheduleIdOrderByRemindBeforeMinutesAsc(SCHEDULE_ID))
                    .willReturn(List.of(relative, absolute));

            // when
            PersonalScheduleResponse result = personalScheduleService.getPersonalSchedule(SCHEDULE_ID, USER_ID);

            // then: detailedReminders に両方載る
            assertThat(result.getDetailedReminders()).hasSize(2);
            assertThat(result.getDetailedReminders())
                    .anySatisfy(r -> {
                        assertThat(r.getReminderKind()).isEqualTo("RELATIVE");
                        assertThat(r.getRemindBeforeMinutes()).isEqualTo(30);
                        assertThat(r.getNotified()).isFalse();
                    })
                    .anySatisfy(r -> {
                        assertThat(r.getReminderKind()).isEqualTo("ABSOLUTE");
                        assertThat(r.getRemindAt()).isEqualTo(absoluteAt);
                        assertThat(r.getNotified()).isTrue();
                    });
            // 後方互換の reminders には相対分のみ
            assertThat(result.getReminders()).containsExactly(30);
        }

        @Test
        @DisplayName("詳細取得_他人のスケジュール_例外スロー")
        void 詳細取得_他人のスケジュール_例外スロー() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> personalScheduleService.getPersonalSchedule(SCHEDULE_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.NOT_SCHEDULE_OWNER);
        }

        @Test
        @DisplayName("詳細取得_不存在_例外スロー")
        void 詳細取得_不存在_例外スロー() {
            // given
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> personalScheduleService.getPersonalSchedule(SCHEDULE_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_NOT_FOUND);
        }
    }

    // ========================================
    // updatePersonalSchedule
    // ========================================

    @Nested
    @DisplayName("updatePersonalSchedule")
    class UpdatePersonalSchedule {

        @Test
        @DisplayName("更新_正常_更新されてイベント発行される")
        void 更新_正常_更新されてイベント発行される() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(reminderRepository.findByScheduleIdOrderByRemindBeforeMinutesAsc(any()))
                    .willReturn(List.of());

            UpdatePersonalScheduleRequest req = new UpdatePersonalScheduleRequest(
                    "更新タイトル", null, null, null, null, null, null, null, null, null, null, null);

            // when
            personalScheduleService.updatePersonalSchedule(SCHEDULE_ID, req, USER_ID);

            // then
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("更新_正常_同一エンティティのフィールドが直接変更される（INSERT発生しない）")
        void 更新_正常_同一エンティティのフィールドが直接変更される() {
            // given: toBuilder().build()のバグでは新規エンティティが生成されsave()がINSERTになる。
            // このテストはentityのフィールドが直接変更されることを検証する。
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(reminderRepository.findByScheduleIdOrderByRemindBeforeMinutesAsc(any()))
                    .willReturn(List.of());

            UpdatePersonalScheduleRequest req = new UpdatePersonalScheduleRequest(
                    "更新タイトル", "更新説明", "更新場所", null, null, null, null, "#00FF00", null, null, null, null);

            // when
            PersonalScheduleResponse result = personalScheduleService.updatePersonalSchedule(SCHEDULE_ID, req, USER_ID);

            // then: save()に渡されたエンティティがfindById()で取得した同一オブジェクトであること（INSERTでなくUPDATE）
            assertThat(result.getContent().title()).isEqualTo("更新タイトル");
            assertThat(result.getContent().description()).isEqualTo("更新説明");
            assertThat(result.getContent().location()).isEqualTo("更新場所");
            assertThat(result.getContent().color()).isEqualTo("#00FF00");
            // nullのフィールドは元の値が保持される
            assertThat(result.getTime().startAt()).isEqualTo(START);
            assertThat(result.getTime().endAt()).isEqualTo(END);
        }

        @Test
        @DisplayName("更新_部分更新_nullフィールドは元の値が保持される")
        void 更新_部分更新_nullフィールドは元の値が保持される() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(reminderRepository.findByScheduleIdOrderByRemindBeforeMinutesAsc(any()))
                    .willReturn(List.of());

            // titleのみ更新（他はnull→変更なし）
            UpdatePersonalScheduleRequest req = new UpdatePersonalScheduleRequest(
                    "タイトルのみ更新", null, null, null, null, null, null, null, null, null, null, null);

            // when
            PersonalScheduleResponse result = personalScheduleService.updatePersonalSchedule(SCHEDULE_ID, req, USER_ID);

            // then
            assertThat(result.getContent().title()).isEqualTo("タイトルのみ更新");
            // 変更されていないフィールドは元の値が保持される
            assertThat(result.getContent().description()).isEqualTo("テスト");
            assertThat(result.getContent().location()).isEqualTo("自宅");
            assertThat(result.getContent().color()).isEqualTo("#FF0000");
        }

        @Test
        @DisplayName("更新_他人のスケジュール_例外スロー")
        void 更新_他人のスケジュール_例外スロー() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            UpdatePersonalScheduleRequest req = new UpdatePersonalScheduleRequest(
                    "更新", null, null, null, null, null, null, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> personalScheduleService.updatePersonalSchedule(SCHEDULE_ID, req, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.NOT_SCHEDULE_OWNER);
        }

        @Test
        @DisplayName("更新_キャンセル済み_例外スロー")
        void 更新_キャンセル済み_例外スロー() {
            // given
            ScheduleEntity cancelled = createCancelledPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(cancelled));

            UpdatePersonalScheduleRequest req = new UpdatePersonalScheduleRequest(
                    "更新", null, null, null, null, null, null, null, null, null, null, null);

            // when & then
            assertThatThrownBy(() -> personalScheduleService.updatePersonalSchedule(SCHEDULE_ID, req, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_ALREADY_CANCELLED);
        }
    }

    // ========================================
    // deletePersonalSchedule
    // ========================================

    @Nested
    @DisplayName("deletePersonalSchedule")
    class DeletePersonalSchedule {

        @Test
        @DisplayName("削除_単体_論理削除される")
        void 削除_単体_論理削除される() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            personalScheduleService.deletePersonalSchedule(SCHEDULE_ID, "THIS_ONLY", USER_ID);

            // then
            assertThat(entity.getDeletedAt()).isNotNull();
            verify(eventPublisher).publishEvent(any(Object.class));
        }

        @Test
        @DisplayName("削除_他人のスケジュール_例外スロー")
        void 削除_他人のスケジュール_例外スロー() {
            // given
            ScheduleEntity entity = createPersonalScheduleEntity();
            given(scheduleRepository.findById(SCHEDULE_ID)).willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> personalScheduleService.deletePersonalSchedule(SCHEDULE_ID, null, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.NOT_SCHEDULE_OWNER);
        }
    }

    // ========================================
    // batchDeletePersonalSchedules
    // ========================================

    @Nested
    @DisplayName("batchDeletePersonalSchedules")
    class BatchDeletePersonalSchedules {

        @Test
        @DisplayName("一括削除_正常_削除件数とスキップ件数を返す")
        void 一括削除_正常_削除件数とスキップ件数を返す() {
            // given
            ScheduleEntity ownSchedule = createPersonalScheduleEntity();
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(ownSchedule));
            given(scheduleRepository.findById(2L)).willReturn(Optional.empty());
            given(scheduleRepository.save(any(ScheduleEntity.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            // when
            BatchDeleteResponse result =
                    personalScheduleService.batchDeletePersonalSchedules(List.of(1L, 2L), USER_ID);

            // then
            assertThat(result.getDeletedCount()).isEqualTo(1);
            assertThat(result.getSkippedCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("一括削除_上限超過_例外スロー")
        void 一括削除_上限超過_例外スロー() {
            // given
            List<Long> ids = java.util.stream.LongStream.range(1, 52).boxed().toList();

            // when & then
            assertThatThrownBy(() -> personalScheduleService.batchDeletePersonalSchedules(ids, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.BATCH_DELETE_LIMIT_EXCEEDED);
        }

        @Test
        @DisplayName("一括削除_他人のスケジュール_スキップされる")
        void 一括削除_他人のスケジュール_スキップされる() {
            // given
            ScheduleEntity otherUserSchedule = ScheduleEntity.builder()
                    .userId(OTHER_USER_ID)
                    .title("他人の予定")
                    .startAt(START)
                    .endAt(END)
                    .allDay(false)
                    .eventType(EventType.OTHER)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.ADMIN_ONLY)
                    .status(ScheduleStatus.SCHEDULED)
                    .isException(false)
                    .build();
            given(scheduleRepository.findById(1L)).willReturn(Optional.of(otherUserSchedule));

            // when
            BatchDeleteResponse result =
                    personalScheduleService.batchDeletePersonalSchedules(List.of(1L), USER_ID);

            // then
            assertThat(result.getDeletedCount()).isZero();
            assertThat(result.getSkippedCount()).isEqualTo(1);
        }
    }
}
