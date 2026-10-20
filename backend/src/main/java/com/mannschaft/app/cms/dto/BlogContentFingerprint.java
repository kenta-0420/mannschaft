package com.mannschaft.app.cms.dto;

import java.time.LocalDate;

/** Blog の本文を含まない比較証跡。現在鍵だけで旧鍵履歴の勝者を推定しない。 */
public record BlogContentFingerprint(String version, LocalDate week, String keyId, String digest) {
    /** 比較に使う添付の源所有永続 ID。URL や表示名は含めない。 */
    public record AttachmentRef(String idType, String canonicalId) { }
}
