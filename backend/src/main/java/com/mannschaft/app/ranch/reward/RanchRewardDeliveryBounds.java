package com.mannschaft.app.ranch.reward;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/** 測定済み版として明示登録された配送値だけを公開政策に許す。未登録は公開不可。 */
@Service
@RequiredArgsConstructor
public class RanchRewardDeliveryBounds {
    private static final String PREFIX = "mannschaft.ranch.delivery.bounds.";
    private final Environment environment;

    public Optional<RegisteredBounds> registered() {
        String version = environment.getProperty(PREFIX + "version");
        if (version == null || version.isBlank()) return Optional.empty();
        if (!version.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalStateException("配送値上限の登録版が不正です");
        }
        return Optional.of(new RegisteredBounds(version,
                range("batch-size"), range("lease-seconds"), range("max-attempts"),
                range("initial-backoff-seconds"), range("max-backoff-seconds")));
    }

    /** 管理公開の入口で呼ぶ。運営登録値や測定範囲を推測して補わない。 */
    public void validate(RanchRewardPolicySnapshot.DeliverySettings settings) {
        Objects.requireNonNull(settings);
        RegisteredBounds bounds = registered().orElseThrow(
                () -> new IllegalStateException("配送値上限が未登録です"));
        if (!bounds.batchSize().contains(settings.batchSize())
                || !bounds.leaseSeconds().contains(settings.leaseSeconds())
                || !bounds.maxAttempts().contains(settings.maxAttempts())
                || !bounds.initialBackoffSeconds().contains(settings.initialBackoffSeconds())
                || !bounds.maxBackoffSeconds().contains(settings.maxBackoffSeconds())) {
            throw new IllegalArgumentException("配送設定が登録済み測定範囲外です");
        }
    }

    private Range range(String name) {
        try {
            return new Range(readPositive(name + ".min"), readPositive(name + ".max"));
        } catch (RuntimeException invalid) {
            throw new IllegalStateException("配送値上限の登録が不完全または不正です", invalid);
        }
    }

    private int readPositive(String key) {
        String raw = environment.getProperty(PREFIX + key);
        if (raw == null || !raw.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("配送値上限が未登録です");
        }
        return Integer.parseInt(raw);
    }

    public record Range(int min, int max) {
        public Range {
            if (min <= 0 || max < min) throw new IllegalArgumentException("配送値の測定範囲が不正です");
        }

        public boolean contains(int value) {
            return min <= value && value <= max;
        }
    }

    public record RegisteredBounds(String version, Range batchSize, Range leaseSeconds,
                                   Range maxAttempts, Range initialBackoffSeconds,
                                   Range maxBackoffSeconds) { }
}
