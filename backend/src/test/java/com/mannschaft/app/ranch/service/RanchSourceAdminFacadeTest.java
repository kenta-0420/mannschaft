package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ranchsource.SourceOutboxErrorCode;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminFacade;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;

import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 源の保存ACKが不明な境界だけを検証。fresh HTTP認可・源保存競合は実ITで別に検証する。 */
@ExtendWith(MockitoExtension.class)
class RanchSourceAdminFacadeTest {
    private static final Long ACTOR = 71L;
    private final ObjectMapper mapper = new ObjectMapper();
    private final RanchAdminAdmission admission = mock(RanchAdminAdmission.class);
    @Mock private ObjectProvider<SourceOutboxAdminFacade> provider;
    private final SourceOutboxAdminFacade source = mock(SourceOutboxAdminFacade.class);
    private RanchSourceAdminFacade facade;

    @BeforeEach
    void setup() {
        facade = new RanchSourceAdminFacade(admission, new RanchAdminInputParser(), provider);
        // この単体試験の受付済み主体。認可のDB判定を成功証明へ代用しない。
        when(admission.checked(eq(ACTOR), any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(1);
            return operation.get();
        });
    }

    @Test
    void 源窓口不在は正常ゼロや確定破棄004ではなく不確実503を返す() {
        assertThatThrownBy(() -> facade.health(ACTOR))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", SourceOutboxErrorCode.SOURCEOUTBOX_001)
                .hasFieldOrPropertyWithValue("httpStatusOverride", HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(source);
    }

    @Test
    void 源の保存後ACK不明例外を確定拒否へ再分類しない() throws Exception {
        when(provider.getIfAvailable()).thenReturn(source);
        UUID event = UUID.randomUUID(), key = UUID.randomUUID();
        var failure = new BusinessException(SourceOutboxErrorCode.SOURCEOUTBOX_001, HttpStatus.SERVICE_UNAVAILABLE);
        doThrow(failure).when(source).retry(eq(ACTOR), eq(RanchRewardSourceType.BLOG_FIRST_PUBLISH), eq(event), eq(key), any());
        var body = mapper.readTree("{\"reasonCode\":\"MANUAL_RETRY\"}");
        assertThatThrownBy(() -> facade.retry(ACTOR, RanchRewardSourceType.BLOG_FIRST_PUBLISH, event, key, body))
                .isSameAs(failure);
    }

    @Test
    void 本文でactorを指定した再処理は源を呼ぶ前に拒否する() throws Exception {
        var body = mapper.readTree("{\"reasonCode\":\"MANUAL_RETRY\",\"actorUserId\":99}");
        assertThatThrownBy(() -> facade.retry(ACTOR, RanchRewardSourceType.BLOG_FIRST_PUBLISH, UUID.randomUUID(), UUID.randomUUID(), body))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", RanchErrorCode.RANCH_006);
        verifyNoInteractions(provider, source);
    }
}
