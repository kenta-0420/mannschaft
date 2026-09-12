package com.mannschaft.app.common.storage.acl;

import java.util.UUID;

/** ストレージ ACL の所有スコープを課金スコープから独立して表現する値型。 */
public record StorageAclScope(StorageAclScopeType type, String scopeKey) {

    public StorageAclScope {
        if (type == null || scopeKey == null || scopeKey.isBlank()) {
            throw new IllegalArgumentException("Storage ACL scope is required");
        }
        switch (type) {
            case TEAM, ORGANIZATION, PERSONAL, PUBLIC -> scopeKey = String.valueOf(requirePositiveLong(scopeKey));
            case VILLAGE -> scopeKey = UUID.fromString(scopeKey).toString();
        }
    }

    public static StorageAclScope team(Long teamId) {
        return numeric(StorageAclScopeType.TEAM, teamId);
    }

    public static StorageAclScope organization(Long organizationId) {
        return numeric(StorageAclScopeType.ORGANIZATION, organizationId);
    }

    public static StorageAclScope village(UUID villageId) {
        if (villageId == null) {
            throw new IllegalArgumentException("Village ID is required");
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
            throw new IllegalArgumentException("Storage ACL scope ID must be positive");
        }
        return new StorageAclScope(type, String.valueOf(id));
    }

    private static long requirePositiveLong(String key) {
        try {
            long parsed = Long.parseLong(key);
            if (parsed <= 0) {
                throw new IllegalArgumentException("Storage ACL scope ID must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Storage ACL scope ID must be numeric", exception);
        }
    }
}
