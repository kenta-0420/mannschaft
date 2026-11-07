package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchCareRulePublicationRequest;
import com.mannschaft.app.ranch.dto.RanchOperationalControlsRequest;
import com.mannschaft.app.ranch.dto.RanchPolicyPublicationRequest;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/** 型・未知項目・全源重複をHTTP境界で拒否する。live gateはACK検索後に検証する。 */
@Component
public class RanchAdminInputParser {
    public RanchOperationalControlsRequest controls(JsonNode body) {
        exact(body, Set.of("version", "isCareEnabled", "isShopEnabled", "isDeliveryPaused", "isRewardsPaused", "reasonCode"));
        return new RanchOperationalControlsRequest(decimal(body, "version", false), bool(body, "isCareEnabled"),
                bool(body, "isShopEnabled"), bool(body, "isDeliveryPaused"), bool(body, "isRewardsPaused"), reason(body, 40));
    }

    public RanchCareRulePublicationRequest care(JsonNode body) {
        exact(body, Set.of("effectiveAt", "amountXp", "weeklyCapXp", "juvenileXp", "adultXp", "reasonCode"));
        var request = new RanchCareRulePublicationRequest(instant(body), decimal(body, "amountXp", true),
                decimal(body, "weeklyCapXp", true), decimal(body, "juvenileXp", true), decimal(body, "adultXp", true), reason(body, 80));
        if (Long.parseLong(request.juvenileXp()) >= Long.parseLong(request.adultXp())) throw invalid();
        return request;
    }

    public RanchPolicyPublicationRequest policy(JsonNode body) {
        exact(body, Set.of("effectiveAt", "enabled", "globalWeeklyCap", "sources", "delivery", "reasonCode"));
        JsonNode sources = body.get("sources");
        if (!sources.isArray() || sources.size() != RanchRewardSourceType.values().length) throw invalid();
        var seen = EnumSet.noneOf(RanchRewardSourceType.class);
        var rules = new ArrayList<RanchPolicyPublicationRequest.SourceRule>();
        for (JsonNode item : sources) {
            exact(item, Set.of("sourceType", "enabled", "amountPoints", "countLimit"));
            RanchRewardSourceType type;
            try { type = RanchRewardSourceType.valueOf(text(item, "sourceType")); }
            catch (IllegalArgumentException exception) { throw invalid(); }
            if (!seen.add(type)) throw invalid();
            rules.add(new RanchPolicyPublicationRequest.SourceRule(type, bool(item, "enabled"),
                    decimal(item, "amountPoints", true), positiveInt(item, "countLimit")));
        }
        JsonNode delivery = body.get("delivery");
        exact(delivery, Set.of("batchSize", "leaseSeconds", "maxAttempts", "initialBackoffSeconds", "maxBackoffSeconds"));
        var settings = new RanchPolicyPublicationRequest.Delivery(positiveInt(delivery, "batchSize"),
                positiveInt(delivery, "leaseSeconds"), positiveInt(delivery, "maxAttempts"),
                positiveInt(delivery, "initialBackoffSeconds"), positiveInt(delivery, "maxBackoffSeconds"));
        if (settings.initialBackoffSeconds() > settings.maxBackoffSeconds()) throw invalid();
        boolean enabled = bool(body, "enabled");
        if (enabled && rules.stream().noneMatch(rule -> rule.sourceType() == RanchRewardSourceType.PERSONAL_RECALL_COMPLETE && rule.enabled())) throw invalid();
        return new RanchPolicyPublicationRequest(instant(body), enabled, decimal(body, "globalWeeklyCap", true), rules, settings, reason(body, 80));
    }

    public void exact(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw invalid();
        var names = new HashSet<String>();
        body.fieldNames().forEachRemaining(names::add);
        if (!names.equals(allowed)) throw invalid();
    }

    private String text(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }

    private String decimal(JsonNode body, String key, boolean positive) {
        String value = text(body, key);
        if (!value.matches(positive ? "[1-9][0-9]*" : "0|[1-9][0-9]*")) throw invalid();
        try { Long.parseLong(value); } catch (NumberFormatException exception) { throw invalid(); }
        return value;
    }

    private boolean bool(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.booleanValue();
    }

    private int positiveInt(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) throw invalid();
        return value.intValue();
    }

    private Instant instant(JsonNode body) {
        try { return Instant.parse(text(body, "effectiveAt")); }
        catch (DateTimeParseException exception) { throw invalid(); }
    }

    private String reason(JsonNode body, int maxLength) {
        String value = text(body, "reasonCode");
        if (value.isBlank() || !value.equals(value.trim()) || value.length() > maxLength
                || value.chars().anyMatch(Character::isISOControl)) throw invalid();
        return value;
    }

    private BusinessException invalid() { return new BusinessException(RanchErrorCode.RANCH_006); }
}