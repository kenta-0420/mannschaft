package com.mannschaft.app.village.service;

import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 村憲章の <b>read 公開ゲート</b>（F17.3・設計書 §3.2）。
 *
 * <p>PUBLIC はログイン済なら誰でも、UNLISTED は現役村人/SYSTEM_ADMIN のみ、
 * それ以外（不存在・削除・凍結・UNLISTED 非メンバー）は {@code VILLAGE_NOT_FOUND}（404）で秘匿する。</p>
 *
 * <h2>判定の実体は {@link VillageAccessGate}</h2>
 * <p>この判定は元々本クラスにだけ存在し、他の村サービスは可視性を見ない複製を持っていた。
 * その取りこぼし（非公開村の存在オラクル）を根治するため、<b>本クラスの実装を抽出して</b>
 * 共通ゲート {@link VillageAccessGate#loadReadableVillage} を新設した。本クラスはそのゲートへ委譲する
 * 薄い入口として残る（呼び出し元・外部シグネチャは一切変えない）。
 * 実装を 2 つ持つと片方だけ直すドリフトが必ず起きるため、<b>ここで判定を書き戻さないこと</b>。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageCharterAccessService {

    private final VillageAccessGate villageAccessGate;

    /**
     * 閲覧可能な村を返す。PUBLIC はログイン済なら誰でも、UNLISTED は現役メンバー/SYSTEM_ADMIN のみ、
     * それ以外（不存在・削除・凍結・UNLISTED 非メンバー）は {@code VILLAGE_NOT_FOUND}（404）で秘匿する。
     *
     * @param villageId 村 ID
     * @param viewerId  閲覧者ユーザー ID
     * @return 閲覧可能な村
     * @throws com.mannschaft.app.common.BusinessException 秘匿対象（不存在・削除・凍結・UNLISTED 非メンバー）は
     *                                                     {@link VillageErrorCode#VILLAGE_NOT_FOUND}（404）
     */
    public VillageEntity loadReadableVillageOrHide(UUID villageId, Long viewerId) {
        return villageAccessGate.loadReadableVillage(villageId, viewerId);
    }
}
