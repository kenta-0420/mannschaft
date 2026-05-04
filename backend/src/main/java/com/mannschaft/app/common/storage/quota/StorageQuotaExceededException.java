package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.BusinessException;

/**
 * F13 ストレージクォータ超過時にスローされる例外。
 *
 * <p>呼び出し元の機能サービスは、ユーザー向けに固有のエラーコード（例:
 * {@code PERSONAL_TIMETABLE_084 ATTACHMENT_QUOTA_EXCEEDED}）に変換してから再スローしてもよい。
 * 直接スローすれば {@link StorageQuotaErrorCode#QUOTA_EXCEEDED} として 409 を返す。</p>
 */
public class StorageQuotaExceededException extends BusinessException {

    private final StorageScopeType scopeType;
    private final Long scopeId;
    private final long requestedBytes;
    private final long usedBytes;
    private final long includedBytes;

    public StorageQuotaExceededException(StorageScopeType scopeType, Long scopeId,
                                         long requestedBytes, long usedBytes, long includedBytes) {
        super(StorageQuotaErrorCode.QUOTA_EXCEEDED);
        this.scopeType = scopeType;
        this.scopeId = scopeId;
        this.requestedBytes = requestedBytes;
        this.usedBytes = usedBytes;
        this.includedBytes = includedBytes;
    }

    public StorageScopeType getScopeType() {
        return scopeType;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public long getRequestedBytes() {
        return requestedBytes;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public long getIncludedBytes() {
        return includedBytes;
    }
}
