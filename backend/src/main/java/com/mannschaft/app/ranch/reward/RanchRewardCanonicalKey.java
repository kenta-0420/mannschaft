package com.mannschaft.app.ranch.reward;

import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DayOfWeek;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.Objects;

/** 同じ source fact を本人ごとに一度だけ決定するための正準binary key。 */
public final class RanchRewardCanonicalKey {
    private final byte[] bytes;
    private final byte[] sha256;

    private RanchRewardCanonicalKey(byte[] bytes) {
        this.bytes = bytes.clone();
        try {
            this.sha256 = MessageDigest.getInstance("SHA-256").digest(this.bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256が利用できません", impossible);
        }
    }

    public static RanchRewardCanonicalKey of(RanchRewardEnvelope envelope) {
        Objects.requireNonNull(envelope);
        var rewardWeek = envelope.occurredAt().atZone(ZoneOffset.UTC).toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        boolean recall = envelope.sourceType() == RanchRewardSourceType.PERSONAL_RECALL_COMPLETE;
        String key = envelope.sourceType().name() + ':' + envelope.sourceIdType().name() + ':'
                + envelope.canonicalSourceId() + ":USER:" + envelope.recipientUserId()
                + (recall ? ":WEEK:" + rewardWeek : "");
        byte[] encoded = key.getBytes(StandardCharsets.US_ASCII);
        if (encoded.length == 0 || encoded.length > 240) {
            throw new IllegalArgumentException("正準報酬キーが240byte上限を超えます");
        }
        return new RanchRewardCanonicalKey(encoded);
    }

    public byte[] bytes() { return bytes.clone(); }
    public byte[] sha256() { return sha256.clone(); }

    /** SHA同値でも保存済み完全byte列が異なれば衝突として拒否する。 */
    public boolean sameStoredIdentity(byte[] savedBytes) {
        return Arrays.equals(bytes, savedBytes);
    }
}
