package com.mannschaft.app.matching.service;

import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 他ドメインへ地域マスタを参照させるための読み取り窓口。
 *
 * <p>Entity および Repository を公開せず、不変の値だけを返す。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RegionMasterLookupService {

    private final PrefectureRepository prefectureRepository;
    private final CityRepository cityRepository;

    /** 都道府県をEntityやRepositoryを漏らさない読み取り値として参照する。 */
    public Optional<Prefecture> findPrefectureByCode(String code) {
        return prefectureRepository.findById(code)
                .map(prefecture -> new Prefecture(prefecture.getCode(), prefecture.getName()));
    }

    /** 都道府県の存在をRepositoryを漏らさない読み取り窓口で確認する。 */
    public boolean existsPrefectureByCode(String code) {
        return prefectureRepository.existsById(code);
    }

    /** 市区町村をEntityやRepositoryを漏らさない読み取り値として参照する。 */
    public Optional<City> findCityByCode(String code) {
        return cityRepository.findById(code)
                .map(city -> new City(city.getCode(), city.getPrefectureCode(), city.getName()));
    }

    /** 都道府県名をコードへ引くための不変マップを返す。 */
    public Map<String, String> findPrefectureCodesByName() {
        return prefectureRepository.findAllByOrderByCodeAsc().stream()
                .collect(Collectors.toUnmodifiableMap(
                        prefecture -> prefecture.getName(),
                        prefecture -> prefecture.getCode(),
                        (ignored, replacement) -> replacement));
    }

    /** 都道府県内で名称が完全一致する市区町村を、Entity を漏らさず参照する。 */
    public List<City> findCitiesByPrefectureCodeAndName(String prefectureCode, String name) {
        return cityRepository.findByPrefectureCodeAndNameOrderByCodeAsc(prefectureCode, name).stream()
                .map(city -> new City(city.getCode(), city.getPrefectureCode(), city.getName()))
                .toList();
    }

    /** 都道府県内で名称が接頭辞一致する市区町村を、Entity を漏らさず参照する。 */
    public List<City> findCitiesByPrefectureCodeAndNameStartingWith(
            String prefectureCode, String name) {
        return cityRepository.findByPrefectureCodeAndNameStartingWithOrderByCodeAsc(prefectureCode, name)
                .stream()
                .map(city -> new City(city.getCode(), city.getPrefectureCode(), city.getName()))
                .toList();
    }

    /** Entityを漏らさない都道府県の読み取り値。 */
    public record Prefecture(String code, String name) {
    }

    /** Entityを漏らさない市区町村の読み取り値。 */
    public record City(String code, String prefectureCode, String name) {
    }
}
