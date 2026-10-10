package com.mannschaft.app.ranch.service;

import com.mannschaft.app.auth.service.UserOperationGuard;
import com.mannschaft.app.ranch.dto.FeedingResult;
import com.mannschaft.app.ranch.dto.HatchResponse;
import com.mannschaft.app.ranch.dto.RanchHatchRequest;
import com.mannschaft.app.ranch.dto.RanchPurchaseRequest;
import com.mannschaft.app.ranch.dto.InteractionResult;
import com.mannschaft.app.ranch.dto.OwnerSummary;
import com.mannschaft.app.ranch.dto.RanchInteractionRequest;
import com.mannschaft.app.ranch.dto.RanchSettings;
import com.mannschaft.app.ranch.dto.RanchSettingsRequest;
import com.mannschaft.app.ranch.dto.RanchVersionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** 本人ACTIVE lock内で所有設定と無料行動を順次独立TXへ渡す候補。 */
@Service
@RequiredArgsConstructor
public class RanchOwnerActionFacade {
    private final UserOperationGuard guard;
    private final RanchSettingsReplayReader settingsReplay;
    private final RanchWidgetVisibilityReader visibility;
    private final RanchOwnerCommandWriter ownerCommands;
    private final RanchFeedingWriter feeding;
    private final RanchHatchWriter hatching;
    private final RanchPurchaseWriter purchases;
    private final RanchExternalProjectionProvider projection;
    private final RanchTouchWriter touch;
    private final Clock clock;

    public RanchSettings settings(Long userId, UUID key, RanchSettingsRequest request) {
        return guard.withActiveUser(userId, () -> {
            var saved = settingsReplay.saved(userId, key, request);
            if (saved.isPresent()) return saved.orElseThrow();
            boolean canonicalVisibility = visibility.visible(userId);
            return ownerCommands.settings(userId, key, request, canonicalVisibility, now());
        });
    }

    public OwnerSummary pause(Long userId, UUID key, RanchVersionRequest request) {
        return guard.withActiveUser(userId,
                () -> ownerCommands.pause(userId, key, request, now()));
    }

    public OwnerSummary resume(Long userId, UUID key, RanchVersionRequest request) {
        return guard.withActiveUser(userId,
                () -> ownerCommands.resume(userId, key, request, now()));
    }

    public FeedingResult feed(Long userId, UUID key, RanchVersionRequest request) {
        return guard.withActiveUser(userId,
                () -> feeding.feed(userId, key, request, now()));
    }

    public RanchTouchWriter.TouchOutcome touch(Long userId, UUID key, RanchInteractionRequest request) {
        return guard.withActiveUser(userId,
                () -> touch.touchOutcome(userId, key, request, now()));
    }

    public HatchResponse hatch(Long userId, UUID key, RanchHatchRequest request) {
        return guard.withActiveUser(userId, () -> {
            var saved = hatching.savedReplay(userId, key, request);
            if (saved.isPresent()) return saved.orElseThrow();
            Instant now = now();
            var external = projection.current(userId, now);
            return hatching.hatch(userId, key, request, now, external);
        });
    }

    public RanchFeedingWriter.FeedOutcome feedOutcome(Long userId, UUID key, RanchVersionRequest request) {
        return guard.withActiveUser(userId, () -> feeding.feedOutcome(userId, key, request, now()));
    }

    public RanchPurchaseWriter.PurchaseOutcome purchase(Long userId, UUID key, RanchPurchaseRequest request) {
        return guard.withActiveUser(userId, () -> purchases.purchaseOutcome(userId, key, request, now()));
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
