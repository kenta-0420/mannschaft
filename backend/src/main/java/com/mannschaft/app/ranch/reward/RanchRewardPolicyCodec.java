package com.mannschaft.app.ranch.reward;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** 公開済みpolicyと本人週snapshotの同一canonical JSON/hashを固定する。 */
public final class RanchRewardPolicyCodec {
    private RanchRewardPolicyCodec() { }

    public static Encoded encode(RanchRewardPolicySnapshot policy, ObjectMapper json) {
        Objects.requireNonNull(policy);
        Objects.requireNonNull(json);
        Map<String, Object> sources = new TreeMap<>();
        for (RanchRewardSourceType type : RanchRewardSourceType.values()) {
            var rule = policy.sources().get(type);
            if (rule == null) throw new IllegalArgumentException("四源ruleが不足しています");
            Map<String, Object> fields = new TreeMap<>();
            fields.put("amountPoints", rule.amountPoints());
            fields.put("countLimit", rule.countLimit());
            fields.put("enabled", rule.enabled());
            sources.put(type.name(), fields);
        }
        Map<String, Object> root = new TreeMap<>();
        Map<String, Object> delivery = new TreeMap<>();
        delivery.put("batchSize", policy.delivery().batchSize());
        delivery.put("leaseSeconds", policy.delivery().leaseSeconds());
        delivery.put("maxAttempts", policy.delivery().maxAttempts());
        delivery.put("initialBackoffSeconds", policy.delivery().initialBackoffSeconds());
        delivery.put("maxBackoffSeconds", policy.delivery().maxBackoffSeconds());
        root.put("delivery", delivery);
        root.put("enabled", policy.enabled());
        root.put("globalCap", policy.globalCap());
        root.put("reasonCode", policy.reasonCode());
        root.put("sources", sources);
        try {
            String body = json.writeValueAsString(root);
            return new Encoded(body, digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("報酬policyを符号化できません", exception);
        }
    }

    public static RanchRewardPolicySnapshot decode(UUID policyId, long version,
                                                    Instant effectiveAt, String body,
                                                    byte[] expectedHash, ObjectMapper json) {
        Objects.requireNonNull(body);
        Objects.requireNonNull(expectedHash);
        try {
            JsonNode root = json.readTree(body);
            if (!root.isObject() || root.size() != 5 || !root.path("enabled").isBoolean()
                    || !root.path("reasonCode").isTextual() || !root.has("globalCap")
                    || !root.has("sources") || !root.path("sources").isObject()
                    || root.path("sources").size() != RanchRewardSourceType.values().length) {
                throw new IllegalArgumentException("報酬policy schemaが不正です");
            }
            long cap = positiveLong(root.path("globalCap"));
            JsonNode deliveryNode = root.path("delivery");
            if (!deliveryNode.isObject() || deliveryNode.size() != 5) {
                throw new IllegalArgumentException("報酬配送設定schemaが不正です");
            }
            var delivery = new RanchRewardPolicySnapshot.DeliverySettings(
                    positiveInt(deliveryNode.path("batchSize")),
                    positiveInt(deliveryNode.path("leaseSeconds")),
                    positiveInt(deliveryNode.path("maxAttempts")),
                    positiveInt(deliveryNode.path("initialBackoffSeconds")),
                    positiveInt(deliveryNode.path("maxBackoffSeconds")));
            EnumMap<RanchRewardSourceType, RanchRewardPolicySnapshot.SourceRule> rules =
                    new EnumMap<>(RanchRewardSourceType.class);
            for (RanchRewardSourceType type : RanchRewardSourceType.values()) {
                JsonNode value = root.path("sources").path(type.name());
                if (!value.isObject() || value.size() != 3
                        || !value.path("enabled").isBoolean()) {
                    throw new IllegalArgumentException("報酬源ruleが不正です");
                }
                rules.put(type, new RanchRewardPolicySnapshot.SourceRule(
                        value.path("enabled").booleanValue(),
                        positiveLong(value.path("amountPoints")),
                        positiveInt(value.path("countLimit"))));
            }
            RanchRewardPolicySnapshot policy = new RanchRewardPolicySnapshot(
                    policyId, version, effectiveAt, root.path("enabled").booleanValue(),
                    cap, rules, delivery, root.path("reasonCode").textValue());
            if (!Arrays.equals(encode(policy, json).sha256(), expectedHash)) {
                throw new IllegalArgumentException("報酬policy内容hashが一致しません");
            }
            return policy;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("報酬policy JSONが不正です", exception);
        }
    }

    private static long positiveLong(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
            throw new IllegalArgumentException("報酬policy数値は正のBIGINTが必要です");
        }
        return value.longValue();
    }

    private static int positiveInt(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) {
            throw new IllegalArgumentException("報酬policy数値は正のintが必要です");
        }
        return value.intValue();
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256が利用できません", impossible);
        }
    }

    public record Encoded(String json, byte[] sha256) {
        public Encoded {
            Objects.requireNonNull(json);
            Objects.requireNonNull(sha256);
            sha256 = sha256.clone();
        }
        @Override public byte[] sha256() { return sha256.clone(); }
    }
}
