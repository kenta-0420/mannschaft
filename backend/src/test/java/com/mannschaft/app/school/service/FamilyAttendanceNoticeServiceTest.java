package com.mannschaft.app.school.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.school.dto.FamilyAttendanceNoticeRequest;
import com.mannschaft.app.school.dto.FamilyAttendanceNoticeResponse;
import com.mannschaft.app.school.dto.FamilyNoticeListResponse;
import com.mannschaft.app.school.entity.FamilyAttendanceNoticeEntity;
import com.mannschaft.app.school.entity.FamilyNoticeType;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.event.FamilyAttendanceNoticeAcknowledgedEvent;
import com.mannschaft.app.school.event.FamilyAttendanceNoticeSubmittedEvent;
import com.mannschaft.app.school.repository.FamilyAttendanceNoticeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import org.mockito.ArgumentCaptor;

/**
 * {@link FamilyAttendanceNoticeService} 単体テスト。
 *
 * <ul>
 *   <li>submitNotice 正常系 — 連絡が保存されてレスポンスが返る</li>
 *   <li>submitNotice 異常系 — ケアリンクなし → 403</li>
 *   <li>acknowledgeNotice 正常系 — 確認済みになる</li>
 *   <li>acknowledgeNotice 異常系 — 存在しない noticeId → FAMILY_NOTICE_NOT_FOUND</li>
 *   <li>applyToRecord 正常系 — appliedToRecord が true になる</li>
 *   <li>applyToRecord 異常系 — 既反映 → FAMILY_NOTICE_ALREADY_APPLIED</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FamilyAttendanceNoticeService 単体テスト")
class FamilyAttendanceNoticeServiceTest {

    private static final Long SUBMITTER_ID = 1L;
    private static final Long STUDENT_ID = 10L;
    private static final Long TEAM_ID = 100L;
    private static final Long NOTICE_ID = 200L;
    /** 担任（＝チーム ADMIN 相当）のユーザーID。 */
    private static final Long TEACHER_ID = 99L;
    private static final LocalDate TODAY = LocalDate.of(2026, 5, 1);

    @Mock
    private FamilyAttendanceNoticeRepository noticeRepository;

    @Mock
    private UserCareLinkRepository userCareLinkRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private StorageService storageService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private FamilyAttendanceNoticeService service;

    // ────────────────────────────────
    // submitNotice
    // ────────────────────────────────

    @Nested
    @DisplayName("submitNotice")
    class SubmitNotice {

        @Test
        @DisplayName("正常系: 連絡が保存されてレスポンスが返る")
        void 正常系_連絡が保存される() {
            willDoNothing().given(accessControlService).checkCareLink(SUBMITTER_ID, STUDENT_ID);

            FamilyAttendanceNoticeEntity saved = buildEntity(false, null, null);
            setId(saved, NOTICE_ID);
            given(noticeRepository.save(any())).willReturn(saved);

            FamilyAttendanceNoticeRequest req = FamilyAttendanceNoticeRequest.builder()
                    .teamId(TEAM_ID)
                    .studentUserId(STUDENT_ID)
                    .attendanceDate(TODAY)
                    .noticeType(FamilyNoticeType.ABSENCE)
                    .build();

            FamilyAttendanceNoticeResponse response = service.submitNotice(SUBMITTER_ID, req);

            assertThat(response.getId()).isEqualTo(NOTICE_ID);
            assertThat(response.getStatus()).isEqualTo("PENDING");
            verify(noticeRepository).save(any());

            // Issue #2990 L6: 業務TX内では通知を直接呼ばず、noticeId だけを載せたイベントを publish する。
            // 通知の失敗で保護者が送った連絡そのものが巻き戻るのを防ぐ。
            ArgumentCaptor<FamilyAttendanceNoticeSubmittedEvent> captor =
                    ArgumentCaptor.forClass(FamilyAttendanceNoticeSubmittedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().noticeId()).isEqualTo(NOTICE_ID);
        }

        @Test
        @DisplayName("異常系: ケアリンクなし → BusinessException")
        void ケアリンクなし_BusinessException() {
            willThrow(new BusinessException(com.mannschaft.app.common.CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkCareLink(SUBMITTER_ID, STUDENT_ID);

            FamilyAttendanceNoticeRequest req = FamilyAttendanceNoticeRequest.builder()
                    .teamId(TEAM_ID)
                    .studentUserId(STUDENT_ID)
                    .attendanceDate(TODAY)
                    .noticeType(FamilyNoticeType.ABSENCE)
                    .build();

            assertThatThrownBy(() -> service.submitNotice(SUBMITTER_ID, req))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ────────────────────────────────
    // acknowledgeNotice
    // ────────────────────────────────

    @Nested
    @DisplayName("acknowledgeNotice")
    class AcknowledgeNotice {

        @Test
        @DisplayName("正常系: 確認済みになる")
        void 正常系_確認済みになる() {
            FamilyAttendanceNoticeEntity entity = buildEntity(false, null, null);
            setId(entity, NOTICE_ID);
            given(noticeRepository.findById(NOTICE_ID)).willReturn(Optional.of(entity));

            FamilyAttendanceNoticeEntity acknowledged = buildEntity(false, 99L, LocalDateTime.now());
            setId(acknowledged, NOTICE_ID);
            given(noticeRepository.save(any())).willReturn(acknowledged);

            FamilyAttendanceNoticeResponse response = service.acknowledgeNotice(TEAM_ID, NOTICE_ID, 99L);

            assertThat(response.getStatus()).isEqualTo("ACKNOWLEDGED");

            // Issue #2990 L6: 通知の失敗で担任の確認済み化が巻き戻らないよう、publish だけに留める。
            ArgumentCaptor<FamilyAttendanceNoticeAcknowledgedEvent> captor =
                    ArgumentCaptor.forClass(FamilyAttendanceNoticeAcknowledgedEvent.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().noticeId()).isEqualTo(NOTICE_ID);
        }

        @Test
        @DisplayName("異常系: 存在しない noticeId → FAMILY_NOTICE_NOT_FOUND")
        void 存在しないnoticeId_FAMILY_NOTICE_NOT_FOUND() {
            given(noticeRepository.findById(NOTICE_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> service.acknowledgeNotice(TEAM_ID, NOTICE_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(SchoolErrorCode.FAMILY_NOTICE_NOT_FOUND));
        }
    }

    // ────────────────────────────────
    // applyToAttendanceRecord
    // ────────────────────────────────

    @Nested
    @DisplayName("applyToRecord")
    class ApplyToRecord {

        @Test
        @DisplayName("正常系: appliedToRecord が true になる")
        void 正常系_appliedToRecordがtrue() {
            FamilyAttendanceNoticeEntity entity = buildEntity(false, 99L, LocalDateTime.now());
            setId(entity, NOTICE_ID);
            given(noticeRepository.findById(NOTICE_ID)).willReturn(Optional.of(entity));

            FamilyAttendanceNoticeEntity applied = buildEntity(true, 99L, LocalDateTime.now());
            setId(applied, NOTICE_ID);
            given(noticeRepository.save(any())).willReturn(applied);

            FamilyAttendanceNoticeResponse response = service.applyToAttendanceRecord(TEAM_ID, NOTICE_ID, 99L);

            assertThat(response.isAppliedToRecord()).isTrue();
            assertThat(response.getStatus()).isEqualTo("APPLIED");
        }

        @Test
        @DisplayName("異常系: 既反映 → FAMILY_NOTICE_ALREADY_APPLIED")
        void 既反映_FAMILY_NOTICE_ALREADY_APPLIED() {
            FamilyAttendanceNoticeEntity entity = buildEntity(true, 99L, LocalDateTime.now());
            setId(entity, NOTICE_ID);
            given(noticeRepository.findById(NOTICE_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.applyToAttendanceRecord(TEAM_ID, NOTICE_ID, 99L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                            .isEqualTo(SchoolErrorCode.FAMILY_NOTICE_ALREADY_APPLIED));
        }
    }

    // ────────────────────────────────
    // getTeamNotices
    // ────────────────────────────────

    @Nested
    @DisplayName("getTeamNotices")
    class GetTeamNotices {

        @Test
        @DisplayName("正常系: 一覧と未確認件数が返る")
        void 正常系_一覧と未確認件数() {
            FamilyAttendanceNoticeEntity unack = buildEntity(false, null, null);
            setId(unack, 1L);
            FamilyAttendanceNoticeEntity acked = buildEntity(false, 99L, LocalDateTime.now());
            setId(acked, 2L);
            given(noticeRepository.findByTeamIdAndAttendanceDateOrderByCreatedAtDesc(TEAM_ID, TODAY))
                    .willReturn(List.of(unack, acked));

            FamilyNoticeListResponse response = service.getTeamNotices(TEAM_ID, TODAY, TEACHER_ID);

            assertThat(response.getTotalCount()).isEqualTo(2);
            assertThat(response.getUnacknowledgedCount()).isEqualTo(1);
        }
    }

    // ────────────────────────────────
    // ヘルパー
    // ────────────────────────────────

    private FamilyAttendanceNoticeEntity buildEntity(
            boolean applied, Long acknowledgedBy, LocalDateTime acknowledgedAt) {
        return FamilyAttendanceNoticeEntity.builder()
                .teamId(TEAM_ID)
                .studentUserId(STUDENT_ID)
                .submitterUserId(SUBMITTER_ID)
                .attendanceDate(TODAY)
                .noticeType(FamilyNoticeType.ABSENCE)
                .appliedToRecord(applied)
                .acknowledgedBy(acknowledgedBy)
                .acknowledgedAt(acknowledgedAt)
                .build();
    }

    private void setId(FamilyAttendanceNoticeEntity entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
    }

    // ────────────────────────────────
    // toBuilder 更新破壊 回帰テスト
    // ────────────────────────────────

    /**
     * toBuilder().build() で作り直すと BaseEntity.id が引き継がれず id=null の新インスタンスになり
     * INSERT 化して行が重複するバグの回帰テスト。
     */
    @Nested
    @DisplayName("toBuilder更新破壊回帰")
    class ToBuilderUpdateRegression {

        private static final Long EXISTING_ID = 55L;

        @Test
        @DisplayName("acknowledgeNotice: 取得した同一インスタンスを id 保持のまま UPDATE する")
        void acknowledgeNotice_既存行をUPDATE_id保持() {
            // Given
            FamilyAttendanceNoticeEntity entity = buildEntity(false, null, null);
            setId(entity, EXISTING_ID);
            given(noticeRepository.findById(NOTICE_ID)).willReturn(Optional.of(entity));

            ArgumentCaptor<FamilyAttendanceNoticeEntity> captor =
                    ArgumentCaptor.forClass(FamilyAttendanceNoticeEntity.class);
            given(noticeRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            // When
            service.acknowledgeNotice(TEAM_ID, NOTICE_ID, 99L);

            // Then: save に渡るのは取得した同一インスタンスで、id が保持されている（=UPDATE 経路）
            FamilyAttendanceNoticeEntity saved = captor.getValue();
            assertThat(saved).isSameAs(entity);
            assertThat(saved.getId()).isEqualTo(EXISTING_ID);
            assertThat(saved.getAcknowledgedBy()).isEqualTo(99L);
            assertThat(saved.getAcknowledgedAt()).isNotNull();
        }

        @Test
        @DisplayName("applyToAttendanceRecord: 取得した同一インスタンスを id 保持のまま UPDATE する")
        void applyToAttendanceRecord_既存行をUPDATE_id保持() {
            // Given
            FamilyAttendanceNoticeEntity entity = buildEntity(false, 99L, LocalDateTime.now());
            setId(entity, EXISTING_ID);
            given(noticeRepository.findById(NOTICE_ID)).willReturn(Optional.of(entity));

            ArgumentCaptor<FamilyAttendanceNoticeEntity> captor =
                    ArgumentCaptor.forClass(FamilyAttendanceNoticeEntity.class);
            given(noticeRepository.save(captor.capture())).willAnswer(inv -> inv.getArgument(0));

            // When
            service.applyToAttendanceRecord(TEAM_ID, NOTICE_ID, 99L);

            // Then: 同一インスタンス・id 保持・appliedToRecord=true 反映
            FamilyAttendanceNoticeEntity saved = captor.getValue();
            assertThat(saved).isSameAs(entity);
            assertThat(saved.getId()).isEqualTo(EXISTING_ID);
            assertThat(saved.getAppliedToRecord()).isTrue();
        }
    }
}
