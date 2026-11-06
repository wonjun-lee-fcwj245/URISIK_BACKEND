package com.urisik.backend.global.util;

/**
 * 한글 유니코드 자모 분리 유틸리티.
 * <p>
 * 한글 음절(가-힣)을 초성·중성·종성으로 분해한다.
 * Elasticsearch fuzziness가 자모 단위로 동작하도록 하기 위해 사용한다.
 * </p>
 * <pre>
 * "김치찌개" → "ㄱㅣㅁㅊㅣㅉㅣㄱㅐ"
 * "김치찌게" → "ㄱㅣㅁㅊㅣㅉㅣㄱㅔ" (1자모 차이 → fuzziness AUTO 매칭 가능)
 * </pre>
 */
public final class JamoUtils {

    private static final char HANGUL_BASE = 0xAC00;
    private static final char HANGUL_LAST = 0xD7A3;

    private static final char[] CHOSEONG = {
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ',
            'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };

    private static final char[] JUNGSEONG = {
            'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ',
            'ㅙ', 'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ'
    };

    private static final char[] JONGSEONG = {
            0, 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ',
            'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ',
            'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };

    private JamoUtils() {
    }

    /**
     * 문자열 내 한글 음절을 자모로 분해한다. 비한글 문자(공백 포함)는 그대로 유지한다.
     */
    public static String decompose(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        StringBuilder sb = new StringBuilder(text.length() * 3);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= HANGUL_BASE && ch <= HANGUL_LAST) {
                int code = ch - HANGUL_BASE;
                int choIdx = code / (21 * 28);
                int jungIdx = (code % (21 * 28)) / 28;
                int jongIdx = code % 28;

                sb.append(CHOSEONG[choIdx]);
                sb.append(JUNGSEONG[jungIdx]);
                if (jongIdx != 0) {
                    sb.append(JONGSEONG[jongIdx]);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
