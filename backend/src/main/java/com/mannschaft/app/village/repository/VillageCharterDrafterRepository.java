package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageCharterDrafterEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村憲章の策定者リポジトリ（F17.3・設計書 §13.1.3/§5/§11.1）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断）。表示順 {@code sort_order} 昇順で取得し、
 * 二重登録防止（§5.4）と退会匿名化（{@code findByUserId}・§11.1）に必要なクエリを持つ。</p>
 */
public interface VillageCharterDrafterRepository extends JpaRepository<VillageCharterDrafterEntity, UUID> {

    /** charter 配下の策定者を表示順（{@code sort_order} 昇順）で取得。 */
    List<VillageCharterDrafterEntity> findByCharterIdOrderBySortOrderAsc(UUID charterId);

    /** 策定者を id で取得しつつ charter 一致まで照合する（他 charter の drafter は 404・§AC-16）。 */
    Optional<VillageCharterDrafterEntity> findByIdAndCharterId(UUID id, UUID charterId);

    /** 同一ユーザーの二重策定者登録チェック（§5.4）。退会後の複数 NULL は UNIQUE 相異扱いで共存。 */
    boolean existsByCharterIdAndUserId(UUID charterId, Long userId);

    /** charter 配下の策定者数（サブリスト上限 20 の判定用・§15.1/AC-20b）。 */
    long countByCharterId(UUID charterId);

    /**
     * 退会匿名化用（§11.1）: 当該ユーザーが策定者として刻まれている全行を取得する。
     * 出陣（W3）で {@code VillageUserCleanerEventListener} が {@code user_id} を NULL 化する。
     */
    List<VillageCharterDrafterEntity> findByUserId(Long userId);
}
