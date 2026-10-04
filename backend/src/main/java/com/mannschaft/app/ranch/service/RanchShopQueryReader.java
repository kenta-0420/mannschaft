package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.dto.RanchShopItem;
import com.mannschaft.app.ranch.entity.RanchShopCatalogEntity;
import com.mannschaft.app.ranch.repository.RanchCollectibleCatalogRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
import com.mannschaft.app.ranch.repository.RanchShopCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 公開制御が有効な時だけ現行価格版を投影する。 */
@Service
@RequiredArgsConstructor
public class RanchShopQueryReader {
    private final RanchShopCatalogRepository catalog;
    private final RanchCollectibleCatalogRepository collectibles;
    private final RanchInventoryRepository inventory;
    private final RanchShopControl control;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public List<RanchShopItem> current(Long userId, Instant serverTime) {
        Objects.requireNonNull(userId);
        Instant now = Objects.requireNonNull(serverTime).truncatedTo(ChronoUnit.MICROS);
        if (!control.enabled()) return List.of();
        Set<String> owned = new HashSet<>();
        inventory.findByUserIdOrderByAwardedAtDescIdDesc(userId).stream()
                .filter(item -> !item.isRevoked() && item.getSkuKey() != null)
                .forEach(item -> owned.add(item.getSkuKey()));
        LinkedHashMap<String, RanchShopCatalogEntity> latest = new LinkedHashMap<>();
        for (RanchShopCatalogEntity row : catalog.findByActiveTrueOrderBySkuKeyAscPriceVersionDesc()) {
            latest.putIfAbsent(row.getSkuKey(), row);
        }
        return latest.values().stream().flatMap(row ->
                collectibles.findByCollectibleKeyAndActiveTrue(row.getCollectibleKey())
                        .filter(asset -> "SHOP".equals(asset.getSourceKind()))
                        .stream().map(asset -> new RanchShopItem(row.getSkuKey(),
                                row.getCollectibleKey(), asset.getLabelKey(), asset.getAssetKey(),
                                Long.toString(row.getPricePoints()), Long.toString(row.getPriceVersion()),
                                owned.contains(row.getSkuKey())))).toList();
    }
}
