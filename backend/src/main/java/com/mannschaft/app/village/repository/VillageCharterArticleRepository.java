package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageCharterArticleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村憲章の条リポジトリ（F17.3・設計書 §13.1.2/§6）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断）。非削除条を {@code sort_order} 昇順で取得する
 * （自動採番の元・§6.1）。IDOR 照合は Service 側で {@code charterId}／{@code villageId} を突き合わせる
 * （§AC-08）。再連番の bulk UPDATE（層1 非バンプ・§6.3）は出陣（W3）で追加する。</p>
 */
public interface VillageCharterArticleRepository extends JpaRepository<VillageCharterArticleEntity, UUID> {

    /** charter 配下の非削除条を {@code sort_order} 昇順で取得（自動採番の元・§6.1）。 */
    List<VillageCharterArticleEntity> findByCharterIdAndDeletedAtIsNullOrderBySortOrderAsc(UUID charterId);

    /** 条を id で取得（生存条のみ）。村/charter 一致の IDOR 照合は Service 側で行う（§AC-08）。 */
    Optional<VillageCharterArticleEntity> findByIdAndDeletedAtIsNull(UUID id);

    /** charter 配下の非削除条数（サブリスト上限 200 の判定用・§15.1/AC-20b）。 */
    long countByCharterIdAndDeletedAtIsNull(UUID charterId);
}
