package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 村憲章の <b>read 公開ゲート</b>（F17.3・設計書 §3.2）。
 *
 * <p>相性 API の主金型 {@code VillageAffinityService#loadPublicVillageOrHide}（PUBLIC のみ通し・
 * UNLISTED/削除/凍結は 404 秘匿）を土台に、UNLISTED 時のみ<b>現役メンバー/SYSTEM_ADMIN バイパス</b>
 * （掲示板 {@code checkVillageBulletinViewAccess} 由来）を足したハイブリッド。凍結村
 * （{@code archived_at} 非 NULL）も read では 404 に畳む（掲示板 read と同じ
 * {@code findByIdAndDeletedAtIsNullAndArchivedAtIsNull} 実体に揃える・§3.2）。</p>
 *
 * <p><b>W1 骨格スタブ</b>: 判定ロジックは出陣（W3）で実装する。本クラスは型・DI の seam のみ提供する。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageCharterAccessService {

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final AccessControlService accessControlService;

    /**
     * 閲覧可能な村を返す。PUBLIC はログイン済なら誰でも、UNLISTED は現役メンバー/SYSTEM_ADMIN のみ、
     * それ以外（不存在・削除・凍結・UNLISTED 非メンバー）は {@code VILLAGE_NOT_FOUND}（404）で秘匿する。
     *
     * @param villageId 村 ID
     * @param viewerId  閲覧者ユーザー ID
     * @return 閲覧可能な村
     * @throws UnsupportedOperationException W1 骨格スタブ（出陣 W3 で実装）
     */
    public VillageEntity loadReadableVillageOrHide(UUID villageId, Long viewerId) {
        // F17.3 W3（出陣）で実装: PUBLIC 通し／UNLISTED はメンバー・SYSTEM_ADMIN／それ以外 404（§3.2）。
        throw new UnsupportedOperationException("F17.3 W3（出陣）で実装予定: read 公開ゲート");
    }
}
