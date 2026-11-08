package com.mannschaft.app.common.storage.acl;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 既存claim後の同じnative TXで、所有・scope・親・束縛が一致する永続UUIDだけを読む。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StorageClaimedIdentityReader {
    private final JdbcTemplate jdbc;

    /** 新TXやnetworkを開かず、未確定・読取不能を空にする。claim自体の認可・失敗は変更しない。 */
    public Optional<UUID> currentClaimedIdentity(String fileKey, Long owner, StorageAclScope scope,
            StorageAclContentReference parent, StorageAclAttachmentBinding binding) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || fileKey == null || fileKey.isBlank() || owner == null || owner <= 0
                || scope == null || parent == null || binding == null) return Optional.empty();
        try {
            var rows = jdbc.query("SELECT id FROM storage_acls WHERE file_key=? AND owner_id=? "
                    + "AND scope_type=? AND scope_key=? AND acl_mode='CONTENT_BOUND' AND status='CLAIMED' "
                    + "AND parent_content_reference_type=? AND parent_content_reference_key=? "
                    + "AND attachment_binding_type=? AND attachment_binding_key=? LIMIT 2 FOR UPDATE",
                    (rs, index) -> rs.getBytes(1), fileKey, owner, scope.type().name(), scope.scopeKey(),
                    parent.type(), parent.key(), binding.type(), binding.key());
            if (rows.size() != 1 || rows.getFirst() == null || rows.getFirst().length != 16)
                return Optional.empty();
            var bytes = ByteBuffer.wrap(rows.getFirst());
            return Optional.of(new UUID(bytes.getLong(), bytes.getLong()));
        } catch (DataAccessException ignored) {
            // 入力・causeを複製せず、補助読取の喪失だけを分類する。例外をnative proxyへ渡さない。
            log.warn("ストレージ永続identity補助読取: UNAVAILABLE");
            return Optional.empty();
        }
    }
}
