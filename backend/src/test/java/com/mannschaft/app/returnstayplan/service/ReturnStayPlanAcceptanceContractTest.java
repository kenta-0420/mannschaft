package com.mannschaft.app.returnstayplan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.perf.SqlIntentCounter;
import com.mannschaft.app.returnstayplan.ReturnStayPlanErrorCode;
import com.mannschaft.app.returnstayplan.dto.ReturnStayPlanCreateRequest;
import com.mannschaft.app.returnstayplan.entity.ReturnStayPlanEntity;
import com.mannschaft.app.returnstayplan.event.ReturnStayPlanLifecycleListener;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.bind.annotation.RequestParam;

/** F02.11 の未実装業務契約と注釈契約をAC単位で固定する。 */
class ReturnStayPlanAcceptanceContractTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 17);
    private final ReturnStayPlanService service = new ReturnStayPlanService(Clock.fixed(
            Instant.parse("2026-08-17T03:00:00Z"), ZoneId.of("Asia/Tokyo")));

    @Test
    @DisplayName("AC-14 件数上限: ACTIVE/UPCOMING合計30件は許可し31件はLIMIT_EXCEEDED")
    void ac14_30件と31件の境界() {
        assertThatCode(() -> service.validateCreateLimit(30)).doesNotThrowAnyException();
        assertBusinessError(ReturnStayPlanErrorCode.LIMIT_EXCEEDED,
                () -> service.validateCreateLimit(31));
    }

    @Test
    @DisplayName("AC-16 ACTIVE更新: 公開OFF・公開先保持・終了日延長は許可する")
    void ac16_activeで許可された項目だけ更新できる() {
        var current = activePlan("HOMECOMING", TODAY, TODAY.plusDays(2));
        var requested = request("HOMECOMING", false, TODAY, TODAY.plusDays(5), List.of(30L, 40L));

        assertThatCode(() -> service.validateActiveUpdate(current, requested))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-16 ACTIVE更新: planType変更はINVALID_REQUEST")
    void ac16_activeでplanTypeは変更できない() {
        var current = activePlan("HOMECOMING", TODAY.minusDays(1), TODAY.plusDays(2));
        var requested = request("STAYING", true, TODAY.minusDays(1), TODAY.plusDays(4), List.of(30L));

        assertBusinessError(ReturnStayPlanErrorCode.INVALID_REQUEST,
                () -> service.validateActiveUpdate(current, requested));
    }

    @Test
    @DisplayName("AC-18 一覧既定値: includeEnded=false,page=0,size=20")
    void ac18_controllerの一覧既定値を固定する() throws Exception {
        Method method = com.mannschaft.app.returnstayplan.controller.ReturnStayPlanController.class
                .getMethod("list", boolean.class, int.class, int.class);
        var parameters = method.getParameters();

        assertThat(parameters[0].getAnnotation(RequestParam.class).defaultValue()).isEqualTo("false");
        assertThat(parameters[1].getAnnotation(RequestParam.class).defaultValue()).isEqualTo("0");
        assertThat(parameters[2].getAnnotation(RequestParam.class).defaultValue()).isEqualTo("20");
    }

    @Test
    @DisplayName("AC-18 空一覧: 終了済み除外後0件なら空配列")
    void ac18_該当予定なしは空一覧() {
        assertThat(service.list(10L, false, 0, 20)).isEmpty();
    }

    @Test
    @DisplayName("AC-19 TEAM認可: viewerとownerが同じ有効TEAM MEMBERなら閲覧可")
    void ac19_双方がteamMemberなら閲覧できる() {
        assertThat(service.visibleToMember(101L, 202L, 303L,
                UUID.fromString("0190f3c0-0000-7000-8000-000000000019"))).isTrue();
    }

    @Test
    @DisplayName("AC-20 TEAM認可: 無所属SYSTEM_ADMINは閲覧を迂回できない")
    void ac20_systemAdminでも無所属なら閲覧できない() {
        assertThat(service.visibleToMember(999L, 202L, 303L,
                UUID.fromString("0190f3c0-0000-7000-8000-000000000020"))).isFalse();
    }

    @Test
    @DisplayName("AC-21 TEAM DTO: 公開先teamIdsとversionを含めない")
    void ac21_teamDtoから所有者専用項目を除外する() {
        var componentNames = Arrays.stream(ReturnStayPlanService.TeamPlanView.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertThat(componentNames)
                .containsExactly("id", "ownerDisplayName", "ownerAvatarUrl", "planType",
                        "countryCode", "prefectureCode", "regionName", "timezone",
                        "startDate", "endDate", "status")
                .doesNotContain("teamIds", "version");
    }

    @Test
    @DisplayName("AC-25 退会listener: AFTER_COMMIT・REQUIRES_NEW・event/purge poolを固定する")
    void ac25_退会listenerの三重防御注釈() throws Exception {
        assertListenerAnnotations("onUserAnonymized", com.mannschaft.app.auth.event.UserAnonymizedEvent.class,
                "event-pool");
        assertListenerAnnotations("onAccountPurged", com.mannschaft.app.gdpr.event.AccountPurgedEvent.class,
                "purge-pool");
    }

    @Test
    @DisplayName("AC-26 退会削除: 同じownerを二度削除しても二回目は0件")
    void ac26_owner削除は冪等() {
        // 骨格時の固定値2件ではなく、実装契約（未登録ownerは0件）を検証する。
        assertThat(service.deleteAllForOwner(260L)).isZero();
        assertThat(service.deleteAllForOwner(260L)).isZero();
    }

    @Test
    @DisplayName("AC-27 purge batch: cron・zone・ShedLock・BatchEndpoint名を固定する")
    void ac27_purgeBatch登録契約() throws Exception {
        Method method = ReturnStayPlanPurgeBatchService.class.getMethod("purgeExpiredPlans");

        assertThat(method.getAnnotation(Scheduled.class).cron()).isEqualTo("0 10 4 * * *");
        assertThat(method.getAnnotation(Scheduled.class).zone()).isEqualTo("Asia/Tokyo");
        assertThat(method.getAnnotation(SchedulerLock.class).name()).isEqualTo("returnStayPlanPurgeBatch");
        assertThat(method.getAnnotation(SchedulerLock.class).lockAtLeastFor()).isEqualTo("PT5S");
        assertThat(method.getAnnotation(SchedulerLock.class).lockAtMostFor()).isEqualTo("PT5M");
        assertThat(method.getAnnotation(BatchEndpoint.class).name())
                .isEqualTo("return-stay-plan-purge-daily");
        assertThat(method.getReturnType()).isEqualTo(Integer.class);
    }

    @Test
    @DisplayName("AC-31 member batch: 400人を固定SQL意図数で取得する")
    void ac31_400人でもnPlusOneを起こさない() {
        var memberIds = java.util.stream.LongStream.rangeClosed(1, 400).boxed().toList();
        SqlIntentCounter.reset();

        var result = service.listVisiblePlansForMembers(101L, 303L, memberIds);

        assertThat(result.keySet()).containsExactlyInAnyOrderElementsOf(memberIds);
        assertThat(SqlIntentCounter.intentCount("return_stay_plans")).isLessThanOrEqualTo(1);
        assertThat(SqlIntentCounter.intentCount("return_stay_plan_team_visibilities"))
                .isLessThanOrEqualTo(1);
    }

    private void assertListenerAnnotations(String name, Class<?> eventType, String executor) throws Exception {
        Method method = ReturnStayPlanLifecycleListener.class.getMethod(name, eventType);
        assertThat(method.getAnnotation(Async.class).value()).isEqualTo(executor);
        assertThat(method.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    private void assertBusinessError(
            ReturnStayPlanErrorCode expected,
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOf(BusinessException.class)
                .satisfies(exception -> assertThat(((BusinessException) exception).getErrorCode())
                        .isEqualTo(expected));
    }

    private ReturnStayPlanEntity activePlan(String type, LocalDate start, LocalDate end) {
        return ReturnStayPlanEntity.builder()
                .ownerUserId(10L)
                .planType(ReturnStayPlanEntity.PlanType.valueOf(type))
                .published(true)
                .countryCode("JP")
                .prefectureCode("13")
                .timezone("Asia/Tokyo")
                .startDate(start)
                .endDate(end)
                .build();
    }

    private ReturnStayPlanCreateRequest request(
            String type, boolean published, LocalDate start, LocalDate end, List<Long> teamIds) {
        return new ReturnStayPlanCreateRequest(type, published,
                new ReturnStayPlanCreateRequest.Location("JP", "13", null),
                start, end, teamIds);
    }
}
