package com.mannschaft.app.ranch.service;

import com.mannschaft.app.ranch.DinosaurStage;
import com.mannschaft.app.ranch.RenderStyle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 未承認64素材と分離した、単一BABY歩行pilot専用の開発表示fixture。 */
@Component
public final class RanchDevelopmentVisualCatalog {
    public static final long CATALOG_VERSION = 2L;
    public static final String SPECIES_KEY = "DEV_TRICERATOPS";
    public static final String VARIANT_KEY = "DEV_ORANGE_96_WALK_V1";
    public static final String ASSET_KEY = "dev-triceratops-orange-pixel96-walk-v1";
    public static final String SHA256 =
            "8ad7de84f1d0f495f9aeda86175f9c914fd0cbb788079b4f3c7e101f91358117";

    public static final String PAINT_ASSET_KEY = "dev-triceratops-orange-paint2d-walk-v1";
    public static final String PAINT_SHA256 =
            "f7d5d8822ee2e41b8b41200bb0547195586c1ea8a558b294d38b445450322c19";

    private final boolean enabled;

    public RanchDevelopmentVisualCatalog(
            @Value("${mannschaft.ranch.development-visuals:false}") boolean requested,
            Environment environment) {
        enabled = requested
                && !environment.acceptsProfiles(Profiles.of("prod", "production"))
                && environment.acceptsProfiles(Profiles.of("dev | test | ranch-isolated"));
    }

    public Optional<Visual> find(long catalogVersion, String speciesKey, String variantKey,
                                 DinosaurStage stage, RenderStyle style) {
        Objects.requireNonNull(speciesKey);
        Objects.requireNonNull(variantKey);
        Objects.requireNonNull(stage);
        Objects.requireNonNull(style);
        if (!enabled || catalogVersion != CATALOG_VERSION
                || !SPECIES_KEY.equals(speciesKey) || !VARIANT_KEY.equals(variantKey)
                || stage != DinosaurStage.BABY) {
            return Optional.empty();
        }
        if (style == RenderStyle.PIXEL) {
            return Optional.of(new Visual(ASSET_KEY, SHA256, 96, 8,
                    List.of(0, 96, 192, 288, 384, 480, 576, 672, 768), List.of(0, 96)));
        }
        if (style == RenderStyle.PAINT_2D) {
            return Optional.of(new Visual(PAINT_ASSET_KEY, PAINT_SHA256, 0, 8,
                    List.of(0, 444, 887, 1331, 1774), List.of(0, 444, 887)));
        }
        return Optional.empty();
    }

    public record Visual(String assetKey, String sha256, int cellPixels, int frames,
                         List<Integer> xBoundaries, List<Integer> yBoundaries) {
        public Visual {
            xBoundaries = List.copyOf(xBoundaries);
            yBoundaries = List.copyOf(yBoundaries);
        }
    }
}
