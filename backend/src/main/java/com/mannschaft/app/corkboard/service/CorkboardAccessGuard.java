package com.mannschaft.app.corkboard.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.corkboard.CorkboardErrorCode;
import com.mannschaft.app.corkboard.entity.CorkboardEntity;
import com.mannschaft.app.corkboard.repository.CorkboardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * F09.8 個人コルクボード（{@code scope_type = PERSONAL}）の認可ゲート。認可根治戦役 第1波・個人領域。
 *
 * <h2>保証する内容</h2>
 * <p>個人ボードの参照・更新・削除・カードのピン止めは<b>ボード所有者本人のみ</b>。
 * 認可はリクエストのパスではなく <b>{@code corkboards.owner_id} 由来</b>で確定する
 * （{@code findByIdAndOwnerId} で id と所有者を同時に条件化するため、他者所有の boardId では行が引けない）。
 * 他者所有・不存在・論理削除済みはいずれも<b>同一のエラー</b>に正規化して存在を秘匿する。</p>
 *
 * <p>共有ボード（TEAM / ORGANIZATION）の編集権限は {@link CorkboardPermissionService} が
 * {@code edit_policy} に従って判定する。本ゲートは個人スコープ専用である。</p>
 *
 * <h2>本クラスに集約した理由</h2>
 * <p>同一の所有者判定が {@link CorkboardService}（詳細・更新・削除）と
 * {@link MyCorkboardPinService}（ピン止め）に重複していた。判定の所在を 1 箇所へ集約し、
 * いずれかの経路だけ緩む事故を構造的に防ぐ。</p>
 */
@Component
@RequiredArgsConstructor
public class CorkboardAccessGuard {

    /** 個人ボードの {@code scope_type} 値。 */
    private static final String SCOPE_PERSONAL = "PERSONAL";

    private final CorkboardRepository corkboardRepository;

    /**
     * 所有者本人のボードを取得する（個人ボード CRUD 用）。
     *
     * @param userId  操作ユーザー ID（認証主体）
     * @param boardId 対象ボード ID
     * @return 所有者本人のボード実体
     * @throws BusinessException 他者所有・不存在・論理削除済み
     *                           （{@link CorkboardErrorCode#BOARD_NOT_FOUND} / 404）
     */
    public CorkboardEntity requireOwnedBoard(Long userId, Long boardId) {
        return corkboardRepository.findByIdAndOwnerId(boardId, userId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.BOARD_NOT_FOUND));
    }

    /**
     * ピン止め操作の対象ボードを取得する（所有者本人 かつ 個人スコープ）。
     *
     * <p>ピン止めは v1.0 では個人ボード限定のため、所有者不一致と非個人スコープを
     * 同一のエラー（{@link CorkboardErrorCode#PIN_PERSONAL_ONLY}）に正規化して
     * ボードの実在・スコープ種別を漏らさない。</p>
     *
     * @param userId  操作ユーザー ID（認証主体）
     * @param boardId 対象ボード ID
     * @return 所有者本人の個人ボード実体
     * @throws BusinessException 他者所有・不存在・個人スコープでない
     *                           （{@link CorkboardErrorCode#PIN_PERSONAL_ONLY} / 403）
     */
    public CorkboardEntity requirePinnableOwnBoard(Long userId, Long boardId) {
        CorkboardEntity board = corkboardRepository.findByIdAndOwnerId(boardId, userId)
                .orElseThrow(() -> new BusinessException(CorkboardErrorCode.PIN_PERSONAL_ONLY));
        if (!SCOPE_PERSONAL.equals(board.getScopeType())) {
            throw new BusinessException(CorkboardErrorCode.PIN_PERSONAL_ONLY);
        }
        return board;
    }
}
