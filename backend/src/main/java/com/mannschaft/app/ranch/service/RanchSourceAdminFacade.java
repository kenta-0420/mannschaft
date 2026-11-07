package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ranchsource.SourceOutboxErrorCode;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminFacade;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryAck;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxAdminRetryRequest;
import com.mannschaft.app.common.ranchsource.api.SourceOutboxHealthSummary;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

/** fresh本人管理資格を確かめた後、Ranch取引を持たず源自身の管理公開窓口へ渡す。 */
@Service
@RequiredArgsConstructor
public class RanchSourceAdminFacade {
    private final RanchAdminAdmission admission;
    private final RanchAdminInputParser input;
    private final ObjectProvider<SourceOutboxAdminFacade> source;

    public SourceOutboxHealthSummary health(Long actorId) {
        return admission.checked(actorId, () -> available().health());
    }

    public SourceOutboxAdminRetryAck retry(Long actorId, RanchRewardSourceType type,
                                          UUID eventId, UUID key, JsonNode body) {
        return admission.checked(actorId, () -> {
            input.exact(body, Set.of("reasonCode"));
            JsonNode reason = body.get("reasonCode");
            if (reason == null || !reason.isTextual()
                    || !reason.textValue().matches("[A-Z][A-Z0-9_]{0,79}")) {
                throw new BusinessException(RanchErrorCode.RANCH_006);
            }
            return available().retry(actorId, type, eventId, key,
                    new SourceOutboxAdminRetryRequest(reason.textValue()));
        });
    }

    private SourceOutboxAdminFacade available() {
        SourceOutboxAdminFacade facade;
        try { facade = source.getIfAvailable(); }
        catch (BeansException configurationFailure) { throw unavailable(); }
        if (facade == null) throw unavailable();
        return facade;
    }

    private static BusinessException unavailable() {
        // 源側の保存ACKを照会できないため確定拒否RANCH_004には分類しない。
        // aggregate/source ownTXの例外は捕捉せず、保存結果の不確実性を保持する。
        return new BusinessException(SourceOutboxErrorCode.SOURCEOUTBOX_001, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
