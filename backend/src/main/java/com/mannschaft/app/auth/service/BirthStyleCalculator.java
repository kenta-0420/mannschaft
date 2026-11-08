package com.mannschaft.app.auth.service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 本人カナを固定変換表で派生数へ変換する。原情報を戻り値や例外へ含めない。 */
@Service
public class BirthStyleCalculator {
    private static final String NORMALIZATION_VERSION = "nfkc-hepburn-v1";
    private static final String RULE_VERSION = "digit-reduce-v1";
    private static final Map<String, String> KANA = kanaTable();

    public BirthNumbers calculate(LocalDate date, String lastNameKana, String firstNameKana) {
        if (date == null || date.getYear() < 1 || date.getYear() > 9999) throw invalid();
        int dateSum = digitSum(date.getYear()) + digitSum(date.getMonthValue()) + digitSum(date.getDayOfMonth());
        String letters = romanize(lastNameKana) + romanize(firstNameKana);
        int nameSum = 0;
        for (int i = 0; i < letters.length(); i++) {
            nameSum = Math.addExact(nameSum, (letters.charAt(i) - 'A') % 9 + 1);
        }
        return new BirthNumbers(reduce(dateSum), reduce(nameSum), dateSum, nameSum,
                NORMALIZATION_VERSION, RULE_VERSION);
    }

    /** 長音は直前の母音、撥音はN、促音は次の子音（CHはT）を固定採用する。 */
    public String romanize(String kana) {
        if (kana == null) throw invalid();
        String normalized = Normalizer.normalize(kana, Normalizer.Form.NFKC);
        StringBuilder reading = new StringBuilder();
        normalized.codePoints().forEach(cp -> {
            if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
                reading.appendCodePoint(cp >= 0x3041 && cp <= 0x3096 ? cp + 0x60 : cp);
            }
        });
        if (reading.isEmpty()) throw invalid();
        StringBuilder result = new StringBuilder();
        boolean geminate = false;
        for (int i = 0; i < reading.length();) {
            char current = reading.charAt(i);
            if (current == 'ッ') {
                if (geminate) throw invalid();
                geminate = true;
                i++;
                continue;
            }
            if (current == 'ー') {
                if (geminate || result.isEmpty() || !isVowel(result.charAt(result.length() - 1))) throw invalid();
                result.append(result.charAt(result.length() - 1));
                i++;
                continue;
            }
            String syllable = i + 1 < reading.length() ? KANA.get(reading.substring(i, i + 2)) : null;
            int width = syllable == null ? 1 : 2;
            if (syllable == null) syllable = KANA.get(reading.substring(i, i + 1));
            if (syllable == null) throw invalid();
            if (geminate) {
                char consonant = syllable.charAt(0);
                if (isVowel(consonant) || consonant == 'N') throw invalid();
                result.append(syllable.startsWith("CH") ? 'T' : consonant);
                geminate = false;
            }
            result.append(syllable);
            i += width;
        }
        if (geminate) throw invalid();
        return result.toString();
    }

    private static boolean isVowel(char value) {
        return value == 'A' || value == 'I' || value == 'U' || value == 'E' || value == 'O';
    }
    private static int digitSum(int value) {
        int sum = 0;
        while (value > 0) { sum += value % 10; value /= 10; }
        return sum;
    }
    private static int reduce(int value) {
        while (value > 9) value = digitSum(value);
        return value;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("出生プロフィールの読みまたは日付が不正です");
    }
    private static Map<String, String> kanaTable() {
        Map<String, String> table = new HashMap<>();
        String[] rows = {
            "ア:A イ:I ウ:U エ:E オ:O",
            "カ:KA キ:KI ク:KU ケ:KE コ:KO ガ:GA ギ:GI グ:GU ゲ:GE ゴ:GO",
            "サ:SA シ:SHI ス:SU セ:SE ソ:SO ザ:ZA ジ:JI ズ:ZU ゼ:ZE ゾ:ZO",
            "タ:TA チ:CHI ツ:TSU テ:TE ト:TO ダ:DA ヂ:JI ヅ:ZU デ:DE ド:DO",
            "ナ:NA ニ:NI ヌ:NU ネ:NE ノ:NO",
            "ハ:HA ヒ:HI フ:FU ヘ:HE ホ:HO バ:BA ビ:BI ブ:BU ベ:BE ボ:BO パ:PA ピ:PI プ:PU ペ:PE ポ:PO",
            "マ:MA ミ:MI ム:MU メ:ME モ:MO ヤ:YA ユ:YU ヨ:YO",
            "ラ:RA リ:RI ル:RU レ:RE ロ:RO ワ:WA ヰ:I ヱ:E ヲ:O ン:N ヴ:VU",
            "キャ:KYA キュ:KYU キョ:KYO ギャ:GYA ギュ:GYU ギョ:GYO",
            "シャ:SHA シュ:SHU ショ:SHO シェ:SHE ジャ:JA ジュ:JU ジョ:JO ジェ:JE",
            "チャ:CHA チュ:CHU チョ:CHO チェ:CHE ヂャ:JA ヂュ:JU ヂョ:JO",
            "ニャ:NYA ニュ:NYU ニョ:NYO ヒャ:HYA ヒュ:HYU ヒョ:HYO",
            "ビャ:BYA ビュ:BYU ビョ:BYO ピャ:PYA ピュ:PYU ピョ:PYO",
            "ミャ:MYA ミュ:MYU ミョ:MYO リャ:RYA リュ:RYU リョ:RYO",
            "イェ:YE ウァ:WA ウィ:WI ウェ:WE ウォ:WO",
            "クァ:KWA クィ:KWI クェ:KWE クォ:KWO グァ:GWA グィ:GWI グェ:GWE グォ:GWO",
            "スィ:SI ズィ:ZI ティ:TI テュ:TYU トゥ:TU ディ:DI デュ:DYU ドゥ:DU",
            "ツァ:TSA ツィ:TSI ツェ:TSE ツォ:TSO",
            "ファ:FA フィ:FI フェ:FE フォ:FO フャ:FYA フュ:FYU フョ:FYO",
            "ヴァ:VA ヴィ:VI ヴェ:VE ヴォ:VO ヴャ:VYA ヴュ:VYU ヴョ:VYO"
        };
        for (String row : rows) {
            for (String pair : row.split(" ")) {
                String[] item = pair.split(":");
                table.put(item[0], item[1]);
            }
        }
        return Map.copyOf(table);
    }
    /** 元姓名・カナ・日付を含めない派生値のみ。 */
    public record BirthNumbers(int lifePathNumber, int nameNumber, int dateSum, int nameSum,
                               String normalizationVersion, String ruleVersion) {}
}
