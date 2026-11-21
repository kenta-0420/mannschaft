package com.mannschaft.app.billing;

import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mapstruct.factory.Mappers;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260930-1931 AC13: 正常API上限とlegacy型境界の純粋試練。
 * DB/APIから到達不能な巨大値はここだけで検査する。DB・Bean・外部通信のmockは使わない。
 * 先行骨格のUOEはstub redとして分類し、既存public入口の実業務redとは区別する。
 */
@DisplayName("1931 legacy価格のchecked変換契約")
class LegacyPlanPriceMapperTest {

    private final LegacyPlanPriceMapper mapper = Mappers.getMapper(LegacyPlanPriceMapper.class);

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @MethodSource("catalogAmounts")
    void catalogの正常0と上限は維持し欠損と表現不能額は販売不能nullにする(Long source, Integer expected) {
        assertThat(mapper.toCatalogPriceJpy(source)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @MethodSource("validContractAmounts")
    void 契約の正常0と正常最大税込額とInteger境界を桁落ちなく保存できる(Long source, Integer expected) {
        assertThat(mapper.requireContractPriceJpy(source)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @MethodSource("invalidContractAmounts")
    void 契約の欠損と負値とoverflowは040で拒否し500や無料へ変換しない(Long source) {
        assertThatThrownBy(() -> mapper.requireContractPriceJpy(source)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EntitlementErrorCode.PLAN_PRICE_NOT_CONFIGURED);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @MethodSource("validBands")
    void 正常帯1と10とShort境界を同値で保存できる(Integer source, Short expected) {
        assertThat(mapper.requireContractBandNo(source)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{displayName} [{index}] {arguments}")
    @MethodSource("invalidBands")
    void 欠損帯と0と負値とShort範囲外は040で拒否する(Integer source) {
        assertThatThrownBy(() -> mapper.requireContractBandNo(source)).isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(EntitlementErrorCode.PLAN_PRICE_NOT_CONFIGURED);
    }

    static Stream<Arguments> catalogAmounts() {
        return Stream.concat(validContractAmounts(), Stream.of(
                Arguments.of(null, null), Arguments.of(-1L, null),
                Arguments.of((long) Integer.MAX_VALUE + 1L, null), Arguments.of(Long.MAX_VALUE, null)));
    }

    static Stream<Arguments> validContractAmounts() {
        return Stream.of(Arguments.of(0L, 0), Arguments.of(1L, 1), Arguments.of(19_999_998L, 19_999_998),
                Arguments.of((long) Integer.MAX_VALUE - 1L, Integer.MAX_VALUE - 1),
                Arguments.of((long) Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    static Stream<Arguments> invalidContractAmounts() {
        return Stream.of(Arguments.of((Long) null), Arguments.of(-1L),
                Arguments.of((long) Integer.MAX_VALUE + 1L), Arguments.of(Long.MAX_VALUE));
    }

    static Stream<Arguments> validBands() {
        return Stream.of(Arguments.of(1, (short) 1), Arguments.of(10, (short) 10),
                Arguments.of((int) Short.MAX_VALUE - 1, (short) (Short.MAX_VALUE - 1)),
                Arguments.of((int) Short.MAX_VALUE, Short.MAX_VALUE));
    }

    static Stream<Arguments> invalidBands() {
        return Stream.of(Arguments.of((Integer) null), Arguments.of(-1), Arguments.of(0),
                Arguments.of((int) Short.MAX_VALUE + 1), Arguments.of(Integer.MAX_VALUE));
    }
}
