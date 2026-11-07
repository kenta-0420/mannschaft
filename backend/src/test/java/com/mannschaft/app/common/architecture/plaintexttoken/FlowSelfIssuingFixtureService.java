package com.mannschaft.app.common.architecture.plaintexttoken;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * fixture（番人3）: 乱数の値の流れが「メソッド内の分岐の合流・配列の別名」を通る形と、「メソッドをまたぐ」既知の限界の形。
 *
 * <p>陽性（違反として検出されるべき。メソッド内の流れ）:</p>
 * <ul>
 *   <li>{@link #issueWithBranchMerge(boolean)}: if/else の一方で乱数、他方で固定値を代入して合流</li>
 *   <li>{@link #issueWithBranchMergeOverwrite(boolean)}: 乱数を代入したあと、分岐の中で固定値に上書きする枝がある</li>
 *   <li>{@link #issueWithTernary(boolean)}: 三項演算子で乱数と固定値が合流</li>
 *   <li>{@link #issueWithArrayAlias()}: 乱数を詰める配列の別名から値を取り出す</li>
 *   <li>{@link #issueWithAliasKeptInBranch(boolean)}: 別名のあと、分岐の中で別名の変数に新しい配列を代入する枝がある</li>
 *   <li>{@link #issueWithAliasKeptByTernary(boolean)}: 別名のあと、三項演算子で別名の変数へ新しい配列を代入する</li>
 *   <li>{@link #issueWithLoopCarriedRandom()}: ループの1周目の終わりに詰めた乱数が、2周目の先頭で token 系に届く</li>
 *   <li>{@link #issueWithLoopCarriedAlias()}: ループの1周目の終わりに作った配列の別名が2周目へ持ち越され、
 *       2周目に別名側へ詰めた乱数が元の配列から token 系に届く</li>
 *   <li>{@link #issueWithTaintInCatch(Object)}: try の途中（乱数を詰めた直後）の例外で catch へ移り、詰めた配列が token 系に届く</li>
 *   <li>{@link #issueWithTaintInFinally(Object)}: 同じ形を finally で書く（例外経路の finally の複製に届く）</li>
 *   <li>{@link #issueWithTaintInNestedTry(Object)}: 内側の catch が受けない例外が外側の catch へ抜け、詰めた配列が token 系に届く</li>
 *   <li>{@link #issueWithLongArrayAssignInExpression()}: {@code long} の配列要素への代入式の値（DUP2_X2）が token 系に届く</li>
 *   <li>{@link #issueWithDoubleArrayAssignInExpression()}: {@code double} の配列要素への代入式の値（DUP2_X2）が token 系に届く</li>
 *   <li>{@link #issueWithTwoDimensionalArray()}: 2次元配列の内側の要素へ乱数を書き、読み出した値が token 系に届く</li>
 *   <li>{@link #issueWithRowStoredThenTainted()}: 内側の配列を外側へ格納してから内側に乱数を書き、外側から読む</li>
 *   <li>{@link #issueWithRowTaintedThenStored()}: 内側の配列に乱数を書いてから外側へ格納し、外側から読む</li>
 *   <li>{@link #issueWithThreeDimensionalAlias()}: 3次元に重ねた配列の最も内側に乱数を書き、最も外側から読む</li>
 *   <li>{@link #issueWithCopiedAliasWrite()}: 外側から取り出した内側の配列（別名のローカル変数）へ書き、元の変数から読む</li>
 *   <li>{@link #issueWithBranchMergedAlias(boolean)}: 分岐で合流した別名を外側へ格納し、外側経由で書いて元の変数から読む</li>
 *   <li>{@link #issueWithSplitOfRandom()}: 乱数の文字列を split した配列（値自体に印がある配列）の要素が token 系に届く</li>
 *   <li>{@link #issueWithReturnedArrayWrite()}: メソッドの戻り値の配列に乱数を書き、同じ配列から読む</li>
 *   <li>{@link #issueWithArgumentArrayWrite(String[])}: 引数の配列に乱数を書き、同じ配列から読む</li>
 *   <li>{@link #issueWithFieldArrayReadIntoLocal()}: フィールドから読み出したローカル配列に乱数を書き、同じローカル配列から読む</li>
 *   <li>{@link #issueWithCastArrayWrite()}: CHECKCAST で得た配列に乱数を書き、同じ配列から読む</li>
 *   <li>{@link #issueWithTwiceCastObject(Object)}: 同じ Object 引数を2回配列へキャストし、一方に書いて他方から読む</li>
 * </ul>
 * <p>陰性（検出されてはならない）:</p>
 * <ul>
 *   <li>{@link #ternaryCleanTokenWithCorrelationUuid(boolean)}: UUID は相関 ID にだけ届き、token 系は固定値同士の三項演算子</li>
 *   <li>{@link #separateArraysDoNotShareTaint()}: 別の配列に乱数を入れても、token 系へ渡す配列には伝わらない</li>
 * </ul>
 * <p>既知の偽陰性（<b>検出されないことを固定</b>する。追うのはメソッド内のローカル変数の流れだけで、次の4つは追わない。
 * 将来検出できるようになったら、試験の期待値を反転する）:</p>
 * <ul>
 *   <li>メソッドをまたぐ流れ: {@link #issueViaGeneratorHelper()}（乱数の生成ヘルパ {@link #newRandomToken()} の戻り値を
 *       token 系に詰める）と {@link #issueViaWriterHelper()}（乱数を書込みヘルパ {@link #writeToken} へ渡す）</li>
 *   <li>フィールド経由: {@link #issueViaField()}（乱数をフィールドへ入れ、読み戻して token 系に詰める。
 *       フィールドから読み出したローカル配列に書いて同じメソッド内で読む流れは範囲内で、陽性
 *       {@link #issueWithFieldArrayReadIntoLocal()} で検出する）</li>
 *   <li>ラムダの本体: {@link #issueViaLambdaBody()}（乱数を捕捉したラムダの本体で token 系に詰める）</li>
 *   <li>リフレクション: {@link #issueViaReflection()}（{@link Method#invoke} で token 系の setter を呼ぶ）</li>
 * </ul>
 * <p>既知の偽陽性（<b>検出されることを固定</b>する。配列の別名を union-find の同値類で保守的に近似するため、
 * 共通の格納先を通じて別々の配列が同じ類に併合され、乱数を受けていない配列にも印が及ぶ。番人は安全側に倒す方針で許容する。
 * 誤検出したときは、配列を共通の格納先に入れずに分けるか、{@code @PlaintextTokenDebt} を付ける）:</p>
 * <ul>
 *   <li>{@link #cleanRowSharingBoxWithRandomRow()}: 乱数を入れた配列ときれいな配列を同じ外側の配列へ格納し、きれいな配列から token 系に詰める</li>
 * </ul>
 */
public class FlowSelfIssuingFixtureService {

    private static final int RANDOM_BYTE_LENGTH = 32;

    private static final String FIXED_VALUE = "fixed";

    private static final String OTHER_FIXED_VALUE = "other";

    private final SecureRandom secureRandom = new SecureRandom();

    /** フィールドから読み出したローカル配列の fixture 用。 */
    private final String[] sharedSlots = new String[1];

    public PlaintextTokenFixtureEntity issueWithBranchMerge(boolean external) {
        String value;
        if (external) {
            value = UUID.randomUUID().toString();
        } else {
            value = FIXED_VALUE;
        }
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(value);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithBranchMergeOverwrite(boolean fixed) {
        String value = UUID.randomUUID().toString();
        if (fixed) {
            value = FIXED_VALUE;
        }
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(value);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithTernary(boolean external) {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(external ? UUID.randomUUID().toString() : FIXED_VALUE);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithArrayAlias() {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        byte[] alias = bytes;
        secureRandom.nextBytes(bytes);
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(HexFormat.of().formatHex(alias));
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithAliasKeptInBranch(boolean flag) {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        byte[] alias = bytes;
        if (flag) {
            alias = new byte[RANDOM_BYTE_LENGTH];
        }
        secureRandom.nextBytes(bytes);
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(HexFormat.of().formatHex(alias));
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithAliasKeptByTernary(boolean flag) {
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        byte[] alias = bytes;
        alias = flag ? new byte[RANDOM_BYTE_LENGTH] : alias;
        secureRandom.nextBytes(bytes);
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(HexFormat.of().formatHex(alias));
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithLoopCarriedRandom() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        for (int i = 0; i < 2; i++) {
            entity.setToken(HexFormat.of().formatHex(bytes));
            secureRandom.nextBytes(bytes);
        }
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithLoopCarriedAlias() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        byte[] alias = new byte[RANDOM_BYTE_LENGTH];
        for (int i = 0; i < 2; i++) {
            secureRandom.nextBytes(alias);
            entity.setToken(HexFormat.of().formatHex(bytes));
            alias = bytes;
        }
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithTaintInCatch(Object input) {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        try {
            secureRandom.nextBytes(bytes);
            Objects.requireNonNull(input);
            bytes = new byte[RANDOM_BYTE_LENGTH];
        } catch (RuntimeException e) {
            entity.setToken(HexFormat.of().formatHex(bytes));
        }
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithTaintInFinally(Object input) {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        try {
            secureRandom.nextBytes(bytes);
            Objects.requireNonNull(input);
            bytes = new byte[RANDOM_BYTE_LENGTH];
        } finally {
            entity.setToken(HexFormat.of().formatHex(bytes));
        }
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithTaintInNestedTry(Object input) {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        byte[] bytes = new byte[RANDOM_BYTE_LENGTH];
        try {
            try {
                secureRandom.nextBytes(bytes);
                Objects.requireNonNull(input);
                bytes = new byte[RANDOM_BYTE_LENGTH];
            } catch (IllegalStateException inner) {
                bytes = new byte[RANDOM_BYTE_LENGTH];
            }
        } catch (RuntimeException outer) {
            entity.setToken(HexFormat.of().formatHex(bytes));
        }
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithLongArrayAssignInExpression() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        long[] values = new long[1];
        entity.setToken(Long.toString(values[0] = UUID.randomUUID().getMostSignificantBits()));
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithDoubleArrayAssignInExpression() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        double[] values = new double[1];
        entity.setToken(Double.toString(values[0] = UUID.randomUUID().getMostSignificantBits()));
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithTwoDimensionalArray() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[][] box = new String[1][1];
        box[0][0] = UUID.randomUUID().toString();
        entity.setToken(box[0][0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithRowStoredThenTainted() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] row = new String[1];
        String[][] box = {row};
        row[0] = UUID.randomUUID().toString();
        entity.setToken(box[0][0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithRowTaintedThenStored() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] row = new String[1];
        row[0] = UUID.randomUUID().toString();
        String[][] box = {row};
        entity.setToken(box[0][0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithThreeDimensionalAlias() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] row = new String[1];
        String[][] plane = {row};
        String[][][] cube = {plane};
        row[0] = UUID.randomUUID().toString();
        entity.setToken(cube[0][0][0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithCopiedAliasWrite() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] row = new String[1];
        String[][] box = {row};
        String[] copy = box[0];
        copy[0] = UUID.randomUUID().toString();
        entity.setToken(row[0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithBranchMergedAlias(boolean flag) {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] first = new String[1];
        String[] second = new String[1];
        String[][] box = new String[1][];
        box[0] = flag ? first : second;
        box[0][0] = UUID.randomUUID().toString();
        entity.setToken(first[0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity separateArraysDoNotShareTaint() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] other = new String[1];
        other[0] = UUID.randomUUID().toString();
        String[] tokenBox = new String[1];
        tokenBox[0] = FIXED_VALUE;
        entity.setToken(tokenBox[0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithSplitOfRandom() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] parts = UUID.randomUUID().toString().split("-");
        entity.setToken(parts[0]);
        return entity;
    }

    /** 既知の偽陽性（保守的近似）: cleanRow は乱数を受けないが、randomRow と同じ box へ格納したため同じ類になり検出される。 */
    public PlaintextTokenFixtureEntity cleanRowSharingBoxWithRandomRow() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] randomRow = new String[1];
        String[] cleanRow = {FIXED_VALUE};
        String[][] box = {randomRow, cleanRow};
        randomRow[0] = UUID.randomUUID().toString();
        entity.setToken(cleanRow[0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithReturnedArrayWrite() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] parts = FIXED_VALUE.split("-");
        parts[0] = UUID.randomUUID().toString();
        entity.setToken(parts[0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithArgumentArrayWrite(String[] slots) {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        slots[0] = UUID.randomUUID().toString();
        entity.setToken(slots[0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithFieldArrayReadIntoLocal() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] local = sharedSlots;
        local[0] = UUID.randomUUID().toString();
        entity.setToken(local[0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithCastArrayWrite() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        Object raw = java.lang.reflect.Array.newInstance(String.class, 1);
        String[] slots = (String[]) raw;
        slots[0] = UUID.randomUUID().toString();
        entity.setToken(slots[0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity issueWithTwiceCastObject(Object slots) {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String[] writer = (String[]) slots;
        String[] reader = (String[]) slots;
        writer[0] = UUID.randomUUID().toString();
        entity.setToken(reader[0]);
        return entity;
    }

    public PlaintextTokenFixtureEntity ternaryCleanTokenWithCorrelationUuid(boolean flag) {
        HashedTokenFixtureEntity correlated = new HashedTokenFixtureEntity();
        correlated.setCorrelationId(UUID.randomUUID().toString());
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(flag ? FIXED_VALUE : OTHER_FIXED_VALUE);
        return entity;
    }

    /** 乱数の生成ヘルパ。Entity に書かないので、それ自体は違反でない。 */
    public String newRandomToken() {
        return UUID.randomUUID().toString();
    }

    /** 既知の偽陰性: 乱数を別メソッドで作り、戻り値を token 系に詰める（メソッドをまたぐ流れ）。 */
    public PlaintextTokenFixtureEntity issueViaGeneratorHelper() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(newRandomToken());
        return entity;
    }

    /** 書込みヘルパ。乱数かどうかを知らないので、それ自体は違反でない。 */
    public void writeToken(PlaintextTokenFixtureEntity entity, String value) {
        entity.setToken(value);
    }

    /** 既知の偽陰性: 乱数を書込みヘルパへ渡し、そちらで token 系に詰める（メソッドをまたぐ流れ）。 */
    public PlaintextTokenFixtureEntity issueViaWriterHelper() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        writeToken(entity, UUID.randomUUID().toString());
        return entity;
    }

    /** フィールド経由の受け渡し先（既知の限界の fixture 用）。 */
    private String pendingValue;

    /** 既知の偽陰性: 乱数をフィールドへ入れ、読み戻して token 系に詰める（フィールド経由の流れ）。 */
    public PlaintextTokenFixtureEntity issueViaField() {
        pendingValue = UUID.randomUUID().toString();
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        entity.setToken(pendingValue);
        return entity;
    }

    /** 既知の偽陰性: 乱数を捕捉したラムダの本体で token 系に詰める（ラムダの本体の流れ）。 */
    public PlaintextTokenFixtureEntity issueViaLambdaBody() {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        String value = UUID.randomUUID().toString();
        Runnable writer = () -> entity.setToken(value);
        writer.run();
        return entity;
    }

    /** 既知の偽陰性: リフレクションで token 系の setter を呼ぶ（リフレクションの流れ）。 */
    public PlaintextTokenFixtureEntity issueViaReflection()
            throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        PlaintextTokenFixtureEntity entity = new PlaintextTokenFixtureEntity();
        Method setter = PlaintextTokenFixtureEntity.class.getMethod("setToken", String.class);
        setter.invoke(entity, UUID.randomUUID().toString());
        return entity;
    }
}
