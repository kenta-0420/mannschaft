package com.mannschaft.app.notification.fanout;

import com.mannschaft.app.village.repository.VillageMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * VILLAGE スコープの受信者ソース（P2）。村の現役 USER メンバーを
 * {@link VillageMembershipRepository#findActiveUserSubjectIdsByVillageIdKeyset} でキーセット供給する。
 *
 * <h2>scope_ref による村 UUID の復元（殿裁定）</h2>
 * <p>ジョブ表の多型スコープ参照 {@code scope_ref}（VARCHAR(36)）には村主キー UUID を文字列で格納する。
 * 本実装は {@link UUID#fromString(String)} で村 UUID を復元し、被覆索引 {@code idx_vm_fanout_keyset}
 * （V170 migration）を用いたキーセットページングで受信者 subject_id を 1 チャンクずつ返す。
 * 現役判定（{@code left_at IS NULL} かつ {@code banned_at IS NULL}）はリポジトリのクエリに閉じ込め、
 * 退村・BAN を漏れなく除外する。</p>
 */
@Component
@RequiredArgsConstructor
public class VillageFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー。{@link com.mannschaft.app.notification.NotificationScopeType} とは独立の戦略キー。 */
    public static final String SCOPE_TYPE = "VILLAGE";

    private final VillageMembershipRepository membershipRepository;

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    @Override
    public List<Long> nextPage(String scopeRef, long cursorSubjectId, int limit) {
        UUID villageId = UUID.fromString(scopeRef);
        return membershipRepository.findActiveUserSubjectIdsByVillageIdKeyset(
                villageId, cursorSubjectId, PageRequest.of(0, limit));
    }
}
