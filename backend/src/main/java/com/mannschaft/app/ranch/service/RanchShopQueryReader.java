package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.dto.RanchShopItem;
import com.mannschaft.app.ranch.entity.RanchShopCatalogEntity;
import com.mannschaft.app.ranch.entity.RanchCollectibleCatalogEntity;
import com.mannschaft.app.ranch.repository.RanchCollectibleCatalogRepository;
import com.mannschaft.app.ranch.repository.RanchInventoryRepository;
import com.mannschaft.app.ranch.repository.RanchShopCatalogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
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
        Objects.requireNonNull(serverTime);
        if (!control.enabled()) return List.of();
        LinkedHashMap<String, RanchShopCatalogEntity> latest = new LinkedHashMap<>();
        for (RanchShopCatalogEntity row : catalog.findByActiveTrueOrderBySkuKeyAscPriceVersionDesc()) {
            latest.putIfAbsent(row.getSkuKey(), row);
        }
        if (latest.isEmpty()) return List.of();
        Set<String> owned = new HashSet<>(inventory.ownedShopSkuKeys(userId, latest.keySet()));
        Set<String> collectibleKeys = new HashSet<>();
        latest.values().forEach(row -> collectibleKeys.add(row.getCollectibleKey()));
        Map<String, RanchCollectibleCatalogEntity> assets = new HashMap<>();
        collectibles.findByCollectibleKeyInAndActiveTrue(collectibleKeys)
                .forEach(asset -> assets.put(asset.getCollectibleKey(), asset));
        return latest.values().stream().flatMap(row -> {
            var asset = assets.get(row.getCollectibleKey());
            if (asset == null || !"SHOP".equals(asset.getSourceKind())) return java.util.stream.Stream.empty();
            return java.util.stream.Stream.of(new RanchShopItem(row.getSkuKey(),
                    row.getCollectibleKey(), asset.getLabelKey(), asset.getAssetKey(),
                    Long.toString(row.getPricePoints()), Long.toString(row.getPriceVersion()),
                    owned.contains(row.getSkuKey())));
        }).toList();
    }
}
