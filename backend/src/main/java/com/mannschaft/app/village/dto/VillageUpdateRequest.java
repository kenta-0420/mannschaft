package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import jakarta.validation.constraints.Size;

/**
 * 村更新リクエスト DTO（F17.1 §4.1.3）。
 *
 * <p>HEADMAN または SYSTEM_ADMIN による更新。{@code null} のフィールドは更新しない（部分更新）。
 * {@code slug} と {@code type} は変更不可。</p>
 */
public record VillageUpdateRequest(

        @Size(min = 1, max = 80)
        String name,

        @Size(max = 2000)
        String description,

        VillageJoinPolicy joinPolicy,

        VillageVisibility visibility,

        VillageBulletinVisibility bulletinVisibility,

        @Size(max = 40)
        String category,

        @Size(max = 255)
        String iconR2Key,

        @Size(max = 255)
        String coverR2Key,

        String guidelineMd
) {}
