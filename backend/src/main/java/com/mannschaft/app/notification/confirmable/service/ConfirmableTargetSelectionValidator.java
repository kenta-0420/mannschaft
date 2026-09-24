package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 確認通知の宛先入力の意味を解決する（軍議第8版確定稿 §3.3・AC-8〜11）。
 *
 * <p><b>骨格のみ（試練A）。出陣で実装する。</b></p>
 * <ul>
 *   <li>targets を省略した場合と null の場合 → 既定の宛先（AC-8）</li>
 *   <li>targets=[] → {@code TARGETS_EMPTY}（400・AC-9）。何も作らない</li>
 *   <li>targets と recipientGroupId を両方指定 → {@code TARGETS_AND_GROUP_BOTH_SPECIFIED}（400・AC-10）</li>
 *   <li>{@code recipientUserIds} は公開 API では無視する（AC-11）</li>
 * </ul>
 */
@Component
public class ConfirmableTargetSelectionValidator {

    /**
     * 宛先の入力を解決する。結果の種別は出陣時に定義する Resolution レコード等に委ねる。
     * 骨格段階では常に未実装として例外を投げる。
     */
    public void resolve(List<ConfirmableTargetSpec> targets, UUID recipientGroupId, List<Long> recipientUserIdsIgnored) {
        throw new UnsupportedOperationException("CMP-260920-1040 出陣で実装");
    }
}
