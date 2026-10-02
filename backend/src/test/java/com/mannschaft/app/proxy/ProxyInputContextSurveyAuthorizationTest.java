package com.mannschaft.app.proxy;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.proxy.ProxyInputContext.SurveyResponseOperation;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity.FeatureScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 認可印を別の主体・操作・対象へ流用できないことをContext実体で確認する。 */
class ProxyInputContextSurveyAuthorizationTest {
    private ProxyInputContext activeContext() {
        ProxyInputContext context = new ProxyInputContext();
        context.activate(20L, 30L, "PAPER_FORM", "試練原本", Set.of(FeatureScope.SURVEY), 10L);
        return context;
    }

    @Test
    void 同じ対象と操作の認可印だけが本人を返す() {
        ProxyInputContext context = activeContext();
        for (SurveyResponseOperation operation : SurveyResponseOperation.values()) {
            context.authorizeSurveyResponse(10L, 40L, operation);
            assertThat(context.requireSurveyResponseSubject(10L, 40L, operation)).isEqualTo(20L);
        }
    }

    @Test
    void 閲覧の認可印を送信へ流用できない() {
        ProxyInputContext context = activeContext();
        context.authorizeSurveyResponse(10L, 40L, SurveyResponseOperation.GET_ME);
        assertThatThrownBy(() -> context.requireSurveyResponseSubject(10L, 40L, SurveyResponseOperation.SUBMIT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 別アンケートでは認可印が一致しない() {
        ProxyInputContext context = activeContext();
        context.authorizeSurveyResponse(10L, 40L, SurveyResponseOperation.SUBMIT);
        assertThatThrownBy(() -> context.requireSurveyResponseSubject(10L, 41L, SurveyResponseOperation.SUBMIT))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 検証した代理者と異なる主体では印を作成も使用もできない() {
        ProxyInputContext context = activeContext();
        assertThatThrownBy(() -> context.authorizeSurveyResponse(11L, 40L, SurveyResponseOperation.SUBMIT))
                .isInstanceOf(BusinessException.class);
        context.authorizeSurveyResponse(10L, 40L, SurveyResponseOperation.SUBMIT);
        assertThatThrownBy(() -> context.requireSurveyResponseSubject(11L, 40L, SurveyResponseOperation.SUBMIT))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 再有効化と解除は認可印を消す(boolean activateAgain) {
        ProxyInputContext context = activeContext();
        context.authorizeSurveyResponse(10L, 40L, SurveyResponseOperation.SUBMIT);
        if (activateAgain) {
            context.activate(20L, 30L, "PAPER_FORM", "試練原本", Set.of(FeatureScope.SURVEY), 10L);
        } else {
            context.clear();
        }
        assertThatThrownBy(() -> context.requireSurveyResponseSubject(10L, 40L, SurveyResponseOperation.SUBMIT))
                .isInstanceOf(BusinessException.class);
    }
}
