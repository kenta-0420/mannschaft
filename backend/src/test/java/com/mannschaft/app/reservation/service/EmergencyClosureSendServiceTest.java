package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.CreateEmergencyClosureRequest;
import com.mannschaft.app.reservation.dto.EmergencyClosureResponse;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-261104-0334 臨時休業通知送信の冪等性の取りまとめ役 {@link EmergencyClosureSendService} の単体試練。
 *
 * <p>実 DB の同時実行・rollback は {@code EmergencyClosureIdempotencyIT} が固定する。本試練は分岐
 * （キー無し/形式不正/既存同内容/既存別内容/同時送信の一意制約違反/他の整合性違反）と要求ハッシュの正規化を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmergencyClosureSendService（臨時休業通知の冪等送信）")
class EmergencyClosureSendServiceTest {

    private static final Long TEAM_ID = 10L;
    private static final Long OPERATOR_ID = 100L;
    /** 試験ごとに生成する（固定の16進リテラルは秘密情報スキャナの誤検知を招くため）。小文字表記。 */
    private static final String KEY = UUID.randomUUID().toString();
    private static final String CONSTRAINT = "uq_emergency_closures_team_creator_idempotency";

    @Mock
    private EmergencyClosureService emergencyClosureService;

    @InjectMocks
    private EmergencyClosureSendService sendService;

    private CreateEmergencyClosureRequest request;
    private EmergencyClosureResponse response;

    @BeforeEach
    void setUp() {
        request = request("体調不良", false);
        response = EmergencyClosureResponse.builder().id(55L).teamId(TEAM_ID).sentCount(3).build();
    }

    @Test
    @DisplayName("キー無しは従来どおり毎回新規作成（created=true）し、冪等列を渡さない")
    void キー無しは従来どおり() {
        given(emergencyClosureService.sendClosure(TEAM_ID, OPERATOR_ID, request)).willReturn(response);

        EmergencyClosureSendService.Outcome outcome = sendService.send(TEAM_ID, OPERATOR_ID, request, null);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.response()).isSameAs(response);
        verify(emergencyClosureService, never()).findIdempotentClosure(any(), any(), any());
    }

    @Test
    @DisplayName("形式不正のキーは RESERVATION_056 で拒否し、照会も送信もしない")
    void 形式不正は400() {
        assertThatThrownBy(() -> sendService.send(TEAM_ID, OPERATOR_ID, request, "0a1b-short"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.INVALID_CLOSURE_IDEMPOTENCY_KEY);
        verifyNoInteractions(emergencyClosureService);
    }

    @Test
    @DisplayName("初回はキーを小文字・ハッシュ付きで送信本体へ渡し created=true")
    void 初回は新規作成() {
        given(emergencyClosureService.findIdempotentClosure(TEAM_ID, OPERATOR_ID, KEY)).willReturn(Optional.empty());
        given(emergencyClosureService.sendClosure(eq(TEAM_ID), eq(OPERATOR_ID), any())).willReturn(response);

        EmergencyClosureSendService.Outcome outcome =
                sendService.send(TEAM_ID, OPERATOR_ID, request, KEY.toUpperCase());

        assertThat(outcome.created()).isTrue();
        ArgumentCaptor<CreateEmergencyClosureRequest> sent = ArgumentCaptor.forClass(CreateEmergencyClosureRequest.class);
        verify(emergencyClosureService).sendClosure(eq(TEAM_ID), eq(OPERATOR_ID), sent.capture());
        assertThat(sent.getValue()).isInstanceOf(IdempotentEmergencyClosureRequest.class);
        IdempotentEmergencyClosureRequest idempotent = (IdempotentEmergencyClosureRequest) sent.getValue();
        assertThat(idempotent.getIdempotencyKey()).as("小文字へ正規化").isEqualTo(KEY);
        assertThat(idempotent.getRequestHash()).isEqualTo(EmergencyClosureRequestHasher.hash(request));
        assertThat(EmergencyClosureRequestHasher.hash(idempotent)).as("内容は元の要求と同一")
                .isEqualTo(EmergencyClosureRequestHasher.hash(request));
    }

    @Test
    @DisplayName("0334-4: 同キー同内容の既存行があれば送信本体を呼ばず既存結果を返す（created=false）")
    void 同キー同内容は既存結果() {
        given(emergencyClosureService.findIdempotentClosure(TEAM_ID, OPERATOR_ID, KEY)).willReturn(Optional.of(
                new EmergencyClosureService.IdempotentClosure(response, EmergencyClosureRequestHasher.hash(request))));

        EmergencyClosureSendService.Outcome outcome = sendService.send(TEAM_ID, OPERATOR_ID, request, KEY);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.response()).isSameAs(response);
        verify(emergencyClosureService, never()).sendClosure(any(), any(), any());
    }

    @Test
    @DisplayName("0334-5: 同キー別内容の既存行があれば RESERVATION_055 で拒否し送信本体を呼ばない")
    void 同キー別内容は409() {
        given(emergencyClosureService.findIdempotentClosure(TEAM_ID, OPERATOR_ID, KEY)).willReturn(Optional.of(
                new EmergencyClosureService.IdempotentClosure(response,
                        EmergencyClosureRequestHasher.hash(request("別の理由", false)))));

        assertThatThrownBy(() -> sendService.send(TEAM_ID, OPERATOR_ID, request, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.CLOSURE_IDEMPOTENCY_KEY_REUSED);
        verify(emergencyClosureService, never()).sendClosure(any(), any(), any());
    }

    @Test
    @DisplayName("0334-6: 同時送信で冪等キーの一意制約に負けたら、新しい照会で先着の結果を返す（created=false）")
    void 一意制約違反は先着結果へ集約() {
        given(emergencyClosureService.findIdempotentClosure(TEAM_ID, OPERATOR_ID, KEY))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(new EmergencyClosureService.IdempotentClosure(
                        response, EmergencyClosureRequestHasher.hash(request))));
        given(emergencyClosureService.sendClosure(any(), any(), any()))
                .willThrow(duplicate("emergency_closures." + CONSTRAINT));

        EmergencyClosureSendService.Outcome outcome = sendService.send(TEAM_ID, OPERATOR_ID, request, KEY);

        assertThat(outcome.created()).isFalse();
        assertThat(outcome.response()).isSameAs(response);
        verify(emergencyClosureService, times(2)).findIdempotentClosure(TEAM_ID, OPERATOR_ID, KEY);
    }

    @Test
    @DisplayName("0334-11: 同時送信で負けた側の内容が先着と違えば RESERVATION_055")
    void 一意制約違反で内容違いは409() {
        given(emergencyClosureService.findIdempotentClosure(TEAM_ID, OPERATOR_ID, KEY))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(new EmergencyClosureService.IdempotentClosure(
                        response, EmergencyClosureRequestHasher.hash(request("先着の理由", true)))));
        given(emergencyClosureService.sendClosure(any(), any(), any()))
                .willThrow(duplicate(CONSTRAINT));

        assertThatThrownBy(() -> sendService.send(TEAM_ID, OPERATOR_ID, request, KEY))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ReservationErrorCode.CLOSURE_IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    @DisplayName("他の制約の違反は重複扱いせずそのまま投げ直す（再照会しない）")
    void 他の整合性違反は投げ直す() {
        given(emergencyClosureService.findIdempotentClosure(TEAM_ID, OPERATOR_ID, KEY)).willReturn(Optional.empty());
        DataIntegrityViolationException other = duplicate("email_outbox.uq_email_outbox_idempotency_key");
        given(emergencyClosureService.sendClosure(any(), any(), any())).willThrow(other);

        assertThatThrownBy(() -> sendService.send(TEAM_ID, OPERATOR_ID, request, KEY)).isSameAs(other);
        verify(emergencyClosureService, times(1)).findIdempotentClosure(TEAM_ID, OPERATOR_ID, KEY);
    }

    @Test
    @DisplayName("冪等リクエストは元の要求の全フィールドを写す（フィールド追加時の写し漏れを検出）")
    void 冪等リクエストは全フィールドを写す() throws Exception {
        CreateEmergencyClosureRequest source = request("全項目", true);
        source.setStartTime(LocalTime.of(9, 0));
        source.setEndTime(LocalTime.of(11, 0));
        IdempotentEmergencyClosureRequest copy = new IdempotentEmergencyClosureRequest(source, KEY, "h");

        for (java.lang.reflect.Field field : CreateEmergencyClosureRequest.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            assertThat(field.get(source)).as("写し漏れ: " + field.getName()).isNotNull();
            assertThat(field.get(copy)).as("写し漏れ: " + field.getName()).isEqualTo(field.get(source));
        }
    }

    @Nested
    @DisplayName("一意制約違反の判別")
    class CollisionDetection {

        @Test
        @DisplayName("Hibernate の制約名（表名接頭辞・バッククォート付きも）で判別する")
        void 制約名で判別() {
            assertThat(EmergencyClosureSendService.isIdempotencyKeyCollision(duplicate(CONSTRAINT))).isTrue();
            assertThat(EmergencyClosureSendService.isIdempotencyKeyCollision(
                    duplicate("`emergency_closures`.`" + CONSTRAINT + "`"))).isTrue();
            assertThat(EmergencyClosureSendService.isIdempotencyKeyCollision(duplicate("uq_other"))).isFalse();
        }

        @Test
        @DisplayName("制約名が取れないときは MySQL 1062 のメッセージに対象制約名があるときだけ判別する")
        void 制約名なしは1062メッセージで判別() {
            SQLIntegrityConstraintViolationException target = new SQLIntegrityConstraintViolationException(
                    "Duplicate entry 'x' for key 'emergency_closures." + CONSTRAINT + "'", "23000", 1062);
            SQLIntegrityConstraintViolationException otherKey = new SQLIntegrityConstraintViolationException(
                    "Duplicate entry 'x' for key 'email_outbox.uq_x'", "23000", 1062);
            SQLIntegrityConstraintViolationException notNull = new SQLIntegrityConstraintViolationException(
                    "Column 'reason' cannot be null", "23000", 1048);

            assertThat(EmergencyClosureSendService.isIdempotencyKeyCollision(
                    new DataIntegrityViolationException("x", target))).isTrue();
            assertThat(EmergencyClosureSendService.isIdempotencyKeyCollision(
                    new DataIntegrityViolationException("x", otherKey))).isFalse();
            assertThat(EmergencyClosureSendService.isIdempotencyKeyCollision(
                    new DataIntegrityViolationException("x", notNull))).isFalse();
        }
    }

    @Nested
    @DisplayName("要求ハッシュの正規化")
    class RequestHash {

        @Test
        @DisplayName("同じ内容は同じハッシュ（16進64文字）")
        void 同じ内容は同じ() {
            assertThat(EmergencyClosureRequestHasher.hash(request("理由", true)))
                    .isEqualTo(EmergencyClosureRequestHasher.hash(request("理由", true)))
                    .hasSize(64)
                    .matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("期間・時間帯・理由・件名・本文・取消フラグのどれが違ってもハッシュが変わる")
        void 各項目の違いで変わる() {
            String base = EmergencyClosureRequestHasher.hash(request("理由", false));

            CreateEmergencyClosureRequest endDate = request("理由", false);
            endDate.setEndDate(endDate.getEndDate().plusDays(1));
            CreateEmergencyClosureRequest timed = request("理由", false);
            timed.setStartTime(LocalTime.of(9, 0));
            timed.setEndTime(LocalTime.of(11, 0));
            CreateEmergencyClosureRequest subject = request("理由", false);
            subject.setSubject("別件名");
            CreateEmergencyClosureRequest body = request("理由", false);
            body.setMessageBody("別本文");

            assertThat(EmergencyClosureRequestHasher.hash(request("理由", true))).isNotEqualTo(base);
            assertThat(EmergencyClosureRequestHasher.hash(request("理由2", false))).isNotEqualTo(base);
            assertThat(EmergencyClosureRequestHasher.hash(endDate)).isNotEqualTo(base);
            assertThat(EmergencyClosureRequestHasher.hash(timed)).isNotEqualTo(base);
            assertThat(EmergencyClosureRequestHasher.hash(subject)).isNotEqualTo(base);
            assertThat(EmergencyClosureRequestHasher.hash(body)).isNotEqualTo(base);
        }

        @Test
        @DisplayName("区切り文字を含む値の組み替えで衝突しない（長さ接頭辞）")
        void 区切りの組み替えで衝突しない() {
            CreateEmergencyClosureRequest a = request("a\n1:b", false);
            CreateEmergencyClosureRequest b = request("a", false);
            b.setSubject("b\n" + b.getSubject());

            assertThat(EmergencyClosureRequestHasher.hash(a)).isNotEqualTo(EmergencyClosureRequestHasher.hash(b));
        }
    }

    private static DataIntegrityViolationException duplicate(String constraintName) {
        SQLIntegrityConstraintViolationException sql = new SQLIntegrityConstraintViolationException(
                "Duplicate entry", "23000", 1062);
        return new DataIntegrityViolationException("duplicate",
                new ConstraintViolationException("duplicate", sql, constraintName));
    }

    private static CreateEmergencyClosureRequest request(String reason, boolean cancelReservations) {
        CreateEmergencyClosureRequest request = new CreateEmergencyClosureRequest();
        request.setStartDate(LocalDate.of(2026, 11, 10));
        request.setEndDate(LocalDate.of(2026, 11, 10));
        request.setReason(reason);
        request.setSubject("臨時休業のお知らせ");
        request.setMessageBody("本日は休業します");
        request.setCancelReservations(cancelReservations);
        return request;
    }
}
