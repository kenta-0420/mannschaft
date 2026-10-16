package com.mannschaft.app.loyalty;

import com.mannschaft.app.common.EncryptionService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** actor/TEAM/filter/並び順へ束縛した署名付きseek cursor。 */
@Component
public class LoyaltyCursorCodec {
    private final EncryptionService encryption;
    public LoyaltyCursorCodec(EncryptionService encryption) { this.encryption = encryption; }
    public String encode(Context context, Position position) { return "unimplemented"; }
    public Position decode(Context context, String cursor) { return null; }
    public enum Stream { ACCOUNTS, TRANSACTIONS, MY_REQUESTS, STAFF_ALL, STAFF_PENDING }
    public record Context(Stream stream, Long actorUserId, Long teamId) { }
    public record Position(UUID id, LocalDateTime expiresAt) { }
}
