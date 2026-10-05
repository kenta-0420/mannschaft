package com.mannschaft.app.timeline.dto;

import java.time.LocalDate;

/** TL の本文を含まない比較証跡。現在鍵だけで旧鍵履歴の勝者を推定しない。 */
public record TimelineContentFingerprint(String version, LocalDate week, String keyId, String digest) {
    /** 比較に使う添付の源所有永続 ID。URL や表示名は含めない。 */
    public record AttachmentRef(String idType, String canonicalId) { }
}
