package com.mannschaft.app.matching.service;

import com.mannschaft.app.matching.dto.CityResponse;
import com.mannschaft.app.matching.dto.PrefectureResponse;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.CityRepository;
import com.mannschaft.app.matching.repository.PrefectureRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * マスタデータサービス。都道府県・市区町村マスタの取得を担当する。
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MasterDataService {

    private final PrefectureRepository prefectureRepository;
    private final CityRepository cityRepository;
    private final MatchingMapper matchingMapper;

    /**
     * 全都道府県一覧を取得する。
     */
    public List<PrefectureResponse> listPrefectures() {
        return matchingMapper.toPrefectureResponseList(prefectureRepository.findAllByOrderByCodeAsc());
    }

    /**
     * 都道府県内の市区町村一覧を取得する。
     */
    public List<CityResponse> listCities(String prefectureCode) {
        return matchingMapper.toCityResponseList(cityRepository.findByPrefectureCodeOrderByCodeAsc(prefectureCode));
    }
}
