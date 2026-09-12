package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;

import java.util.UUID;

/**
 * ACL ownership scope, deliberately independent from billing scope.
 *
 * <p>Invalid externally supplied values are normalized to ACL_INVALID_REQUEST so they cannot become 500 responses.</p>
 */
public record StorageAclScope(StorageAclScopeType type, String scopeKey) {

    public StorageAclScope {
        try {
            if (type == null || scopeKey == null || scopeKey.isBlank()) {
                throw new IllegalArgumentException("Storage ACL scope is required");
            }
            switch (type) {
                case TEAM, ORGANIZATION, TOURNAMENT, TOURNAMENT_DIVISION, PERSONAL, PUBLIC ->
                        scopeKey = String.valueOf(requirePositiveLong(scopeKey));
                case VILLAGE -> scopeKey = UUID.fromString(scopeKey).toString();
            }
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST, exception);
        }
    }

    public static StorageAclScope team(Long teamId) {
        return numeric(StorageAclScopeType.TEAM, teamId);
    }

    public static StorageAclScope organization(Long organizationId) {
        return numeric(StorageAclScopeType.ORGANIZATION, organizationId);
    }

    public static StorageAclScope tournament(Long tournamentId) {
        return numeric(StorageAclScopeType.TOURNAMENT, tournamentId);
    }

    public static StorageAclScope tournamentDivision(Long tournamentDivisionId) {
        return numeric(StorageAclScopeType.TOURNAMENT_DIVISION, tournamentDivisionId);
    }

    public static StorageAclScope village(UUID villageId) {
        if (villageId == null) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST);
        }
        return new StorageAclScope(StorageAclScopeType.VILLAGE, villageId.toString());
    }

    public static StorageAclScope personal(Long ownerId) {
        return numeric(StorageAclScopeType.PERSONAL, ownerId);
    }

    public static StorageAclScope publicFor(Long ownerId) {
        return numeric(StorageAclScopeType.PUBLIC, ownerId);
    }

    private static StorageAclScope numeric(StorageAclScopeType type, Long id) {
        if (id == null || id <= 0) {
            throw new BusinessException(StorageErrorCode.ACL_INVALID_REQUEST);
        }
        return new StorageAclScope(type, String.valueOf(id));
    }

    private static long requirePositiveLong(String key) {
        long parsed = Long.parseLong(key);
        if (parsed <= 0) {
            throw new IllegalArgumentException("Storage ACL scope ID must be positive");
        }
        return parsed;
    }
}
