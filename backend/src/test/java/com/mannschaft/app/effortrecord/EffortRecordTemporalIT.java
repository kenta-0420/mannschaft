package com.mannschaft.app.effortrecord;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.effortrecord.dto.CreateDefinitionSnapshotRequest;
import com.mannschaft.app.effortrecord.dto.CreateEffortRecordRequest;
import com.mannschaft.app.effortrecord.dto.EffortDefinitionTokenResponse;
import com.mannschaft.app.effortrecord.dto.EffortFieldDefinition;
import com.mannschaft.app.effortrecord.dto.EffortFieldValue;
import com.mannschaft.app.effortrecord.dto.EffortOwnerRecordResponse;
import com.mannschaft.app.effortrecord.dto.EffortRecordListCriteria;
import com.mannschaft.app.effortrecord.dto.EffortRecordPage;
import com.mannschaft.app.effortrecord.service.EffortDefinitionService;
import com.mannschaft.app.effortrecord.service.EffortOperationReceiptCleanupBatchService;
import com.mannschaft.app.effortrecord.service.EffortRecordOwnerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * F06.4.1 PR-1 の24時間失効（試練 / red 先行。CMP-261115-2145）。AC-9（receipt 24h 失効）/ AC-10（cursor 24h 失効）/
 * AC-62（期限削除バッチ）/ AC-20（PR-2: チーム定義 token の10分失効）。
 *
 * <p><b>時計</b>: 期限判定は共通 {@code utcClock} Bean（{@code ClockConfig}）で行うことを契約とし、本クラスだけ
 * 可変時計へ差し替える（他 context に触れないよう {@link DirtiesContext} AFTER_CLASS で閉じる。前例
 * {@code RecallRewardWeekTemporalIT}）。JWT の発行時刻と独立させるため、HTTP ではなく実 Service を直接呼ぶ。
 * DB・Service はモックしない。</p>
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("F06.4.1 24時間失効・定義 token 10分失効（AC-9/10/62/20）")
class EffortRecordTemporalIT extends EffortRecordItSupport {

    private static final Instant START = Instant.parse("2026-11-10T00:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 1);

    @TestBean(name = "utcClock", methodName = "effortTemporalClock")
    private Clock clock;

    @Autowired private EffortRecordOwnerService ownerService;
    @Autowired private EffortOperationReceiptCleanupBatchService cleanup;
    @Autowired private EffortDefinitionService definitionService;

    static Clock effortTemporalClock() {
        return new MovableClock(new AtomicReference<>(START), ZoneOffset.UTC);
    }

    @AfterEach
    void 時計を戻す() {
        time(START);
    }

    @Test
    @DisplayName("AC-9: 同 key は24時間以内なら同じ原本を返し、24時間経過後は新規として扱う")
    void AC9_receiptは24時間で失効する() {
        time(START);
        Long owner = user("receipt期限");
        String key = UUID.randomUUID().toString();
        CreateEffortRecordRequest request = request("60.00");

        EffortOwnerRecordResponse first = ownerService.create(owner, key, request);
        time(START.plus(Duration.ofHours(24)).minusSeconds(1));
        EffortOwnerRecordResponse replay = ownerService.create(owner, key, request);
        assertThat(replay.id()).as("24時間以内は保存済み応答").isEqualTo(first.id());
        assertThat(rows(owner)).isEqualTo(1);

        time(START.plus(Duration.ofHours(24)).plusSeconds(1));
        EffortOwnerRecordResponse afterExpiry = ownerService.create(owner, key, request);
        assertThat(afterExpiry.id()).as("24時間経過後の同 key は保証外（新規作成）").isNotEqualTo(first.id());
        assertThat(rows(owner)).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-62: 期限削除バッチは expires_at 経過の receipt だけを物理削除する")
    void AC62_期限削除バッチ() {
        time(START);
        Long owner = user("期限削除");
        ownerService.create(owner, UUID.randomUUID().toString(), request("60.00"));
        time(START.plus(Duration.ofHours(12)));
        ownerService.create(owner, UUID.randomUUID().toString(), request("61.00"));
        assertThat(receipts(owner)).isEqualTo(2);

        // 削除側は SQL の UTC_TIMESTAMP(6)（DB 実時刻）で期限判定する（番人方針）。可変 Clock の固定日とは独立なので、
        // 期限は DB 時刻基準に置き直す: 古い方=期限外（DB今-1h）・新しい方=期限内（DB今+1h）。
        expireByDbTime(owner, "ASC", "-");
        expireByDbTime(owner, "DESC", "+");
        int deleted = cleanup.purgeExpired();
        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(receipts(owner)).as("期限内（DB今+1h）の receipt は残る").isEqualTo(1);
        assertThat(rows(owner)).as("receipt の期限削除は原本を消さない").isEqualTo(2);

        tx(() -> em.createNativeQuery("UPDATE effort_operation_receipts SET expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 HOUR "
                + "WHERE actor_user_id = ?1").setParameter(1, owner).executeUpdate());
        cleanup.purgeExpired();
        assertThat(receipts(owner)).isZero();
    }

    /** created_at の昇順/降順で先頭1件の expires_at を DB 実時刻基準（±1時間）へ置く。 */
    private void expireByDbTime(Long actor, String order, String sign) {
        tx(() -> em.createNativeQuery("UPDATE effort_operation_receipts SET expires_at = UTC_TIMESTAMP(6) " + sign
                + " INTERVAL 1 HOUR WHERE actor_user_id = ?1 ORDER BY created_at " + order + " LIMIT 1")
                .setParameter(1, actor).executeUpdate());
    }

    @Test
    @DisplayName("AC-10: cursor は発行から24時間以内なら有効、24時間超は400")
    void AC10_cursorは24時間で失効する() {
        time(START);
        Long owner = user("cursor期限");
        for (int i = 0; i < 3; i++) {
            ownerService.create(owner, UUID.randomUUID().toString(), request("6" + i + ".00"));
        }
        EffortRecordPage first = ownerService.list(owner, new EffortRecordListCriteria(null, null, null, null, 1));
        assertThat(first.hasNext()).isTrue();
        String cursor = first.nextCursor();

        time(START.plus(Duration.ofHours(24)).minusSeconds(1));
        assertThat(ownerService.list(owner, new EffortRecordListCriteria(null, null, null, cursor, 1)).items()).hasSize(1);

        time(START.plus(Duration.ofHours(24)).plusSeconds(1));
        Throwable expired = catchThrowable(() -> ownerService.list(owner, new EffortRecordListCriteria(null, null, null, cursor, 1)));
        assertThat(expired).isInstanceOf(BusinessException.class);
        BusinessException business = (BusinessException) expired;
        HttpStatus status = business.getHttpStatusOverride() != null
                ? business.getHttpStatusOverride() : GlobalExceptionHandler.resolveStatus(business.getErrorCode());
        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(business.getErrorCode().getCode()).isEqualTo("COMMON_001");
    }

    @Test
    @DisplayName("AC-20: definitionToken は utcClock 基準で発行から10分有効。10分以内は TEAM 原本を作成でき、10分超は400 COMMON_001（fieldErrors=definitionToken）")
    void AC20_definitionTokenは10分で失効する() {
        time(START);
        Long team = team("token期限");
        Long admin = user("token期限管理者");
        teamAdmin(admin, team);
        Long owner = user("token期限本人");
        member(owner, team);
        Long template = teamTemplate(admin, team, "期限練習", List.of(FieldSpec.number("n", "回数", 0)));

        EffortDefinitionTokenResponse issued = definitionService.registerTeamSnapshot(owner,
                new CreateDefinitionSnapshotRequest(team, template));
        assertThat(issued.expiresAt()).isEqualTo(START.plus(Duration.ofMinutes(10)));

        time(START.plus(Duration.ofMinutes(10)).minusSeconds(1));
        EffortOwnerRecordResponse created = ownerService.create(owner, UUID.randomUUID().toString(), teamRequest(issued, "3"));
        assertThat(created.definition()).isEqualTo(issued.definition());

        time(START.plus(Duration.ofMinutes(10)).plusSeconds(1));
        Throwable expired = catchThrowable(() ->
                ownerService.create(owner, UUID.randomUUID().toString(), teamRequest(issued, "4")));
        assertThat(expired).isInstanceOf(BusinessException.class);
        BusinessException business = (BusinessException) expired;
        HttpStatus status = business.getHttpStatusOverride() != null
                ? business.getHttpStatusOverride() : GlobalExceptionHandler.resolveStatus(business.getErrorCode());
        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(business.getErrorCode().getCode()).isEqualTo("COMMON_001");
        assertThat(business.getFieldErrors()).extracting(ErrorResponse.FieldError::getField)
                .as("期限切れは token 不正と同じく definitionToken を指す").contains("definitionToken");
        assertThat(rows(owner)).as("失効 token では原本を作らない").isEqualTo(1);
    }

    @Test
    @DisplayName("API 契約 §4.1: TEAM 原本の作成完了後24時間以内に同じ Idempotency-Key・異なる本文を送ると、token 失効より先に 409 COMMON_003")
    void 同key別本文はtoken失効より先に409() {
        time(START);
        Long team = team("key別本文");
        Long admin = user("key別本文管理者");
        teamAdmin(admin, team);
        Long owner = user("key別本文本人");
        member(owner, team);
        Long template = teamTemplate(admin, team, "別本文練習", List.of(FieldSpec.number("n", "回数", 0)));
        EffortDefinitionTokenResponse issued = definitionService.registerTeamSnapshot(owner,
                new CreateDefinitionSnapshotRequest(team, template));
        String key = UUID.randomUUID().toString();
        ownerService.create(owner, key, teamRequest(issued, "3"));

        // token は失効、receipt（24時間）は有効。本文を変えた同 key の再送は token 検証ではなく key 衝突で 409。
        time(START.plus(Duration.ofMinutes(10)).plusSeconds(1));
        Throwable conflict = catchThrowable(() -> ownerService.create(owner, key, teamRequest(issued, "4")));
        assertThat(conflict).isInstanceOf(BusinessException.class);
        BusinessException business = (BusinessException) conflict;
        HttpStatus status = business.getHttpStatusOverride() != null
                ? business.getHttpStatusOverride() : GlobalExceptionHandler.resolveStatus(business.getErrorCode());
        assertThat(status).isEqualTo(HttpStatus.CONFLICT);
        assertThat(business.getErrorCode().getCode()).isEqualTo("COMMON_003");
        assertThat(rows(owner)).as("別本文の再送では原本を作らない").isEqualTo(1);

        // 同じ本文の再送は token 失効後でも元の応答を返す（既存契約の維持）。
        assertThat(ownerService.create(owner, key, teamRequest(issued, "3")).definition())
                .isEqualTo(issued.definition());
        assertThat(rows(owner)).isEqualTo(1);
    }

    private CreateEffortRecordRequest teamRequest(EffortDefinitionTokenResponse issued, String value) {
        List<EffortFieldValue> values = new java.util.ArrayList<>();
        for (EffortFieldDefinition field : issued.definition().fields()) {
            values.add(new EffortFieldValue(field.fieldKey(), value));
        }
        return new CreateEffortRecordRequest(DAY, TOKYO, issued.definition().definitionUuid(),
                issued.definition().revision(), issued.definitionToken(), values, null);
    }

    // ─────────────────────────────────────────────────────────────

    private CreateEffortRecordRequest request(String weight) {
        return new CreateEffortRecordRequest(DAY, TOKYO, WEIGHT, 1, null,
                List.of(new EffortFieldValue("weight", weight)), null);
    }

    private long rows(Long owner) {
        return count("SELECT COUNT(*) FROM effort_records WHERE owner_user_id = ?1", owner);
    }

    private long receipts(Long actor) {
        return count("SELECT COUNT(*) FROM effort_operation_receipts WHERE actor_user_id = ?1", actor);
    }

    private void time(Instant value) {
        ((MovableClock) clock).current.set(value);
    }

    /** この test context だけの時計。JVM 既定 TZ・他 context の Clock に触れない。 */
    private static final class MovableClock extends Clock {
        private final AtomicReference<Instant> current;
        private final ZoneId zone;

        private MovableClock(AtomicReference<Instant> current, ZoneId zone) {
            this.current = current;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this.zone.equals(zone) ? this : new MovableClock(current, zone);
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }
}
