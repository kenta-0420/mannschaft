package com.mannschaft.app.ranch.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.gamification.dto.LegacyBadgeAward;
import com.mannschaft.app.ranch.RanchErrorCode;
import com.mannschaft.app.ranch.dto.RanchLegacySyncResult;
import com.mannschaft.app.ranch.entity.RanchCollectibleCatalogEntity;
import com.mannschaft.app.ranch.entity.RanchCommandEntity;
import com.mannschaft.app.ranch.entity.RanchInventoryEntity;
import com.mannschaft.app.ranch.repository.RanchCollectibleCatalogRepository;
import com.mannschaft.app.ranch.repository.RanchCommandRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
import com.mannschaft.app.ranch.repository.RanchOwnerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 元取得・承認素材・永久inventory・成功commandを本人Ranch取引で結ぶ。 */
@Service
@RequiredArgsConstructor
public class RanchLegacySyncWriter {
    private final RanchCommandRepository commands;
    private final RanchOwnerRepository owners;
    private final RanchCollectibleCatalogRepository collectibles;
    private final RanchInventoryRepository inventory;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RanchLegacySyncResult sync(Long userId, UUID key, byte[] hash, long afterId,
                                      List<LegacyBadgeAward> source) {
        var previous = commands.findByUserIdAndIdempotencyKey(userId, key);
        if (previous.isPresent()) {
            var saved = previous.orElseThrow();
            if (!"LEGACY_SYNC".equals(saved.getCommandType())
                    || !Arrays.equals(hash, saved.getBodyHash())) {
                throw new BusinessException(RanchErrorCode.RANCH_003, HttpStatus.CONFLICT);
            }
            return decode(saved.getResultJson());
        }
        if (source == null || source.size() > 101) throw new IllegalStateException("旧取得ページが有界ではありません");
        long last = afterId;
        for (LegacyBadgeAward award : source) {
            if (award == null || award.awardRowId() <= last) {
                throw new IllegalStateException("旧取得ページの順序が不正です");
            }
            last = award.awardRowId();
        }
        var owner = owners.lockByUserId(userId).orElseThrow(() ->
                new BusinessException(RanchErrorCode.RANCH_001, HttpStatus.NOT_FOUND));
        int processed = Math.min(source.size(), 100);
        boolean hasNext = source.size() > 100;
        long next = processed == 0 ? afterId : source.get(processed - 1).awardRowId();
        List<LegacyBadgeAward> page = source.subList(0, processed);
        Set<String> keys = page.stream().filter(LegacyBadgeAward::sourceBadgeAvailable)
                .map(award -> catalogKey(award.badgeId())).collect(Collectors.toSet());
        Map<String, RanchCollectibleCatalogEntity> approved = keys.isEmpty() ? Map.of()
                : collectibles.findByCollectibleKeyInAndActiveTrue(keys).stream()
                .filter(item -> "LEGACY_BADGE".equals(item.getSourceKind()))
                .collect(Collectors.toMap(RanchCollectibleCatalogEntity::getCollectibleKey,
                        item -> item));

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        int imported = 0;
        for (LegacyBadgeAward award : page) {
            if (!award.sourceBadgeAvailable()) continue;
            String catalogKey = catalogKey(award.badgeId());
            if (!approved.containsKey(catalogKey)) continue;
            byte[] acquisition = RanchAcquisitionKey.legacyBadge("LONG",
                    award.badgeId(), award.periodLabel());
            if (inventory.findByUserIdAndAcquisitionKindAndAcquisitionKey(
                    userId, "LEGACY_BADGE", acquisition).isPresent()) continue;
            inventory.save(RanchInventoryEntity.builder()
                    .ownerId(owner.getId()).userId(userId).collectibleKey(catalogKey)
                    .acquisitionKind("LEGACY_BADGE").acquisitionKey(acquisition)
                    .legacyBadgeId(award.badgeId()).legacyAwardPeriod(award.periodLabel())
                    .awardedAt(now).revoked(false).createdAt(now).build());
            imported++;
        }
        if (imported > 0) {
            owner.advanceVersion();
            owners.save(owner);
        }
        UUID commandId = UuidV7.generate();
        RanchLegacySyncResult result = new RanchLegacySyncResult(commandId,
                Long.toString(next), processed, imported, hasNext, now);
        RanchCommandEntity command = RanchCommandEntity.builder()
                .ownerId(owner.getId()).userId(userId).idempotencyKey(key)
                .commandType("LEGACY_SYNC").bodyHash(hash).resultJson(encode(result))
                .completedAt(now).createdAt(now).build();
        command.setId(commandId);
        commands.saveAndFlush(command);
        return result;
    }

    private static String catalogKey(String canonicalBadgeId) {
        String key = "LEGACY_BADGE:" + canonicalBadgeId;
        if (key.length() > 80) throw new IllegalArgumentException("旧badge catalog keyが不正です");
        return key;
    }

    private String encode(RanchLegacySyncResult result) {
        try { return json.writeValueAsString(result); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("取込結果を保存できません", exception); }
    }

    private RanchLegacySyncResult decode(String saved) {
        try { return json.readValue(saved, RanchLegacySyncResult.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("保存済み取込結果が不正です", exception); }
    }
}
