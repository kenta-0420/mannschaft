package com.mannschaft.app.returnstayplan.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.returnstayplan.event.ReturnStayPlanLifecycleListener;
import java.lang.reflect.Method;
import java.util.Arrays;
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

/** Static API and lifecycle annotation contracts; behavior is covered by MySQL ITs. */
class ReturnStayPlanAcceptanceContractTest {

    @Test
    @DisplayName("AC-18 list defaults are includeEnded=false, page=0, size=20")
    void ac18_listDefaults() throws Exception {
        Method method = com.mannschaft.app.returnstayplan.controller.ReturnStayPlanController.class
                .getMethod("list", boolean.class, int.class, int.class);
        var parameters = method.getParameters();
        assertThat(parameters[0].getAnnotation(RequestParam.class).defaultValue()).isEqualTo("false");
        assertThat(parameters[1].getAnnotation(RequestParam.class).defaultValue()).isEqualTo("0");
        assertThat(parameters[2].getAnnotation(RequestParam.class).defaultValue()).isEqualTo("20");
    }

    @Test
    @DisplayName("AC-21 TEAM DTO excludes owner-only teamIds and version")
    void ac21_teamDtoBoundary() {
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
    @DisplayName("AC-25 withdrawal listeners use AFTER_COMMIT, REQUIRES_NEW and dedicated pools")
    void ac25_listenerAnnotations() throws Exception {
        assertListener("onUserAnonymized", com.mannschaft.app.auth.event.UserAnonymizedEvent.class,
                "event-pool");
        assertListener("onAccountPurged", com.mannschaft.app.gdpr.event.AccountPurgedEvent.class,
                "purge-pool");
    }

    @Test
    @DisplayName("AC-27 purge batch has catalog, schedule and ShedLock contract")
    void ac27_purgeBatchAnnotations() throws Exception {
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

    private void assertListener(String methodName, Class<?> eventType, String executor)
            throws Exception {
        Method method = ReturnStayPlanLifecycleListener.class.getMethod(methodName, eventType);
        assertThat(method.getAnnotation(Async.class).value()).isEqualTo(executor);
        assertThat(method.getAnnotation(Transactional.class).propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
        assertThat(method.getAnnotation(TransactionalEventListener.class).phase())
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
