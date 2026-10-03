package com.mannschaft.app.common.architecture.fixtures.selfscopedinput;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 検体の土台（{@code SelfScopedEndpointScopeInputGuardTest} 専用）。Repository・Service・DTO を
 * パッケージ私有の型としてまとめて置く。
 *
 * <p>どの型にも Spring のステレオタイプ注釈を付けない（テストの Spring コンテキストに Bean として
 * 拾われないため）。Repository も Spring Data を継承しない素の interface で、番人は名前の語尾
 * {@code Repository} で Repository と判定する。検体は実行されず、バイトコードとして読むだけである。</p>
 */
final class SpecimenSupport {

    private SpecimenSupport() {
    }
}

/** 是正前のシフト曜日既定と同じ形の検索（userId とスコープIDの組）。 */
interface SpecimenAvailabilityRepository {

    Optional<Object> findByUserIdAndTeamId(Long userId, Long teamId);
}

/** 自分のピン行を (userId, villageId) で引いて消す形。 */
interface SpecimenPinRepository {

    Optional<Object> findByUserIdAndVillageId(Long userId, UUID villageId);

    void delete(Object entity);

    Object save(Object entity);
}

/** 呼び出しが深さ上限の先にしか無い検体の終点。 */
interface SpecimenDeepRepository {

    long countByUserId(Long userId);
}

/** 是正前の ShiftAvailabilityService#getAvailabilityDefaults の再現。 */
class SpecimenAvailabilityService {

    private SpecimenAvailabilityRepository repository;

    Object getDefaults(Long userId, Long teamId) {
        return repository.findByUserIdAndTeamId(userId, teamId).orElse(null);
    }
}

/** 台帳どおりの呼び出し（find → delete）だけを持つ。 */
class SpecimenPinService {

    private SpecimenPinRepository repository;

    void unpin(Long userId, UUID villageId) {
        repository.findByUserIdAndVillageId(userId, villageId).ifPresent(repository::delete);
    }
}

/** 台帳行は {@link SpecimenPinService} と同じだが、save を足した版。 */
class SpecimenDriftedPinService {

    private SpecimenPinRepository repository;

    void unpin(Long userId, UUID villageId) {
        Object pin = repository.findByUserIdAndVillageId(userId, villageId).orElseThrow();
        repository.save(pin);
        repository.delete(pin);
    }
}

/** ハンドラから深さ 6（深さ上限 5 の先）で初めて Repository に届く鎖。 */
class SpecimenChainService {

    private SpecimenChainService next;
    private SpecimenDeepRepository repository;

    long level1(Long userId) {
        return next.level2(userId);
    }

    long level2(Long userId) {
        return next.level3(userId);
    }

    long level3(Long userId) {
        return next.level4(userId);
    }

    long level4(Long userId) {
        return next.level5(userId);
    }

    long level5(Long userId) {
        return next.level6(userId);
    }

    long level6(Long userId) {
        return repository.countByUserId(userId);
    }
}

/** 本文の入れ子に scopeId を持つ DTO。 */
record SpecimenNestedBody(String title, SpecimenNestedBody.Destination destination) {

    record Destination(String kind, Long scopeId) {
    }
}

/** 本文の UUID の列（ピン並び替えの形）。 */
record SpecimenReorderBody(List<UUID> orderedVillageIds) {
}

/** {@code @ModelAttribute} で受ける検索フォーム。入れ子の filter に scopeId がある。 */
class SpecimenSearchForm {

    private String keyword;
    private Filter filter;

    static class Filter {
        private Long scopeId;
        private String scopeType;
    }
}

/** 4 段の入れ子（深さ上限 3 を超える）。 */
record SpecimenDeepBody(Level2 level2) {

    record Level2(Level3 level3) {
    }

    record Level3(Level4 level4) {
    }

    record Level4(String note) {
    }
}

/** 型変数のフィールドを持つ（中身の型が決まらない）。 */
class SpecimenGenericBody<T> {

    private T payload;
}

/** interface 経由で呼ばれる Service（実装は Repository に依存する）。台帳どおり find → delete だけの版。 */
interface SpecimenPortService {

    void unpin(Long userId, UUID villageId);
}

class SpecimenPortServiceImpl implements SpecimenPortService {

    private SpecimenPinRepository repository;

    @Override
    public void unpin(Long userId, UUID villageId) {
        repository.findByUserIdAndVillageId(userId, villageId).ifPresent(repository::delete);
    }
}

/** interface 経由で呼ばれる Service。実装に save を足した版。 */
interface SpecimenDriftedPortService {

    void unpin(Long userId, UUID villageId);
}

class SpecimenDriftedPortServiceImpl implements SpecimenDriftedPortService {

    private SpecimenPinRepository repository;

    @Override
    public void unpin(Long userId, UUID villageId) {
        Object pin = repository.findByUserIdAndVillageId(userId, villageId).orElseThrow();
        repository.save(pin);
        repository.delete(pin);
    }
}
