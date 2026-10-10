package com.mannschaft.app.ranch.service;

import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.ranch.dto.CommandResult;
import com.mannschaft.app.ranch.dto.RanchInventoryItem;
import com.mannschaft.app.ranch.dto.RanchRecord;
import com.mannschaft.app.ranch.dto.RanchShopItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/** 認証本人lockの下で私有PRIMARY読取を一件ずつ実行する。 */
@Service
@RequiredArgsConstructor
public class RanchPrivateQueryFacade {
    private final UserOperationGuard guard;
    private final RanchAccessGuard access;
    private final RanchCommandQueryReader commands;
    private final RanchRecordQueryReader records;
    private final RanchRecordSourceLinkResolver sourceLinks;
    private final RanchInventoryQueryReader inventory;
    private final RanchShopQueryReader shop;
    private final Clock clock;

    public CommandResult command(Long userId, UUID commandId) {
        return guard.withActiveUser(userId, () -> {
            access.requireOwnedCommand(userId, commandId);
            return commands.get(userId, commandId);
        });
    }

    public CursorPagedResponse<RanchRecord> records(Long userId, String cursor, int limit) {
        return guard.withActiveUser(userId, () -> {
            // readerの独立Ranch TXが完了してから源の閲覧認可へ移り、越境TXを作らない。
            var read = records.readPage(userId, cursor, limit);
            return sourceLinks.resolve(userId, read);
        });
    }

    public CursorPagedResponse<RanchInventoryItem> collectibles(Long userId, String cursor, int limit) {
        return guard.withActiveUser(userId, () -> inventory.page(userId, cursor, limit));
    }

    public List<RanchShopItem> shop(Long userId) {
        return guard.withActiveUser(userId, () -> shop.current(userId,
                Instant.now(clock).truncatedTo(ChronoUnit.MICROS)));
    }
}
