package com.urisik.backend.global.util;

import java.util.HashMap;
import java.util.Map;

/**
 * QWERTY 영문 자판 입력을 한글로 변환하는 유틸리티.
 * <p>
 * 사용자가 한영 전환 없이 영문 자판 상태로 한글을 입력한 경우,
 * 예: "rlaclWlro" → "김치찌개"
 * </p>
 */
public final class QwertyToKoreanConverter {

    private static final Map<Character, Character> KEY_MAP = new HashMap<>();

    static {
        // 자음 (소문자)
        KEY_MAP.put('r', 'ㄱ'); KEY_MAP.put('s', 'ㄴ'); KEY_MAP.put('e', 'ㄷ');
        KEY_MAP.put('f', 'ㄹ'); KEY_MAP.put('a', 'ㅁ'); KEY_MAP.put('q', 'ㅂ');
        KEY_MAP.put('t', 'ㅅ'); KEY_MAP.put('d', 'ㅇ'); KEY_MAP.put('w', 'ㅈ');
        KEY_MAP.put('c', 'ㅊ'); KEY_MAP.put('z', 'ㅋ'); KEY_MAP.put('x', 'ㅌ');
        KEY_MAP.put('v', 'ㅍ'); KEY_MAP.put('g', 'ㅎ');
        // 자음 (대문자 = 쌍자음)
        KEY_MAP.put('R', 'ㄲ'); KEY_MAP.put('E', 'ㄸ'); KEY_MAP.put('Q', 'ㅃ');
        KEY_MAP.put('T', 'ㅆ'); KEY_MAP.put('W', 'ㅉ');
        // 모음
        KEY_MAP.put('k', 'ㅏ'); KEY_MAP.put('o', 'ㅐ'); KEY_MAP.put('i', 'ㅑ');
        KEY_MAP.put('O', 'ㅒ'); KEY_MAP.put('j', 'ㅓ'); KEY_MAP.put('p', 'ㅔ');
        KEY_MAP.put('u', 'ㅕ'); KEY_MAP.put('P', 'ㅖ'); KEY_MAP.put('h', 'ㅗ');
        KEY_MAP.put('y', 'ㅛ'); KEY_MAP.put('n', 'ㅜ'); KEY_MAP.put('b', 'ㅠ');
        KEY_MAP.put('m', 'ㅡ'); KEY_MAP.put('l', 'ㅣ');
        // Shift+일반키 → 소문자와 동일 자모 (쌍자음·ㅒ·ㅖ 외)
        KEY_MAP.put('S', 'ㄴ'); KEY_MAP.put('F', 'ㄹ'); KEY_MAP.put('A', 'ㅁ');
        KEY_MAP.put('D', 'ㅇ'); KEY_MAP.put('C', 'ㅊ'); KEY_MAP.put('Z', 'ㅋ');
        KEY_MAP.put('X', 'ㅌ'); KEY_MAP.put('V', 'ㅍ'); KEY_MAP.put('G', 'ㅎ');
        KEY_MAP.put('K', 'ㅏ'); KEY_MAP.put('I', 'ㅑ'); KEY_MAP.put('J', 'ㅓ');
        KEY_MAP.put('U', 'ㅕ'); KEY_MAP.put('H', 'ㅗ'); KEY_MAP.put('Y', 'ㅛ');
        KEY_MAP.put('N', 'ㅜ'); KEY_MAP.put('B', 'ㅠ'); KEY_MAP.put('M', 'ㅡ');
        KEY_MAP.put('L', 'ㅣ');
    }

    // 초성 배열 (Unicode 순서)
    private static final char[] CHO = {
            'ㄱ','ㄲ','ㄴ','ㄷ','ㄸ','ㄹ','ㅁ','ㅂ','ㅃ','ㅅ','ㅆ','ㅇ','ㅈ','ㅉ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
    };
    private static final char[] JUNG = {
            'ㅏ','ㅐ','ㅑ','ㅒ','ㅓ','ㅔ','ㅕ','ㅖ','ㅗ','ㅘ','ㅙ','ㅚ','ㅛ','ㅜ','ㅝ','ㅞ','ㅟ','ㅠ','ㅡ','ㅢ','ㅣ'
    };
    private static final char[] JONG = {
            0,'ㄱ','ㄲ','ㄳ','ㄴ','ㄵ','ㄶ','ㄷ','ㄹ','ㄺ','ㄻ','ㄼ','ㄽ','ㄾ','ㄿ','ㅀ','ㅁ','ㅂ','ㅄ','ㅅ','ㅆ','ㅇ','ㅈ','ㅊ','ㅋ','ㅌ','ㅍ','ㅎ'
    };

    // 복합 모음: (first, second) → combined
    private static final Map<Long, Character> COMPOUND_JUNG = new HashMap<>();

    static {
        COMPOUND_JUNG.put(pack('ㅗ', 'ㅏ'), 'ㅘ');
        COMPOUND_JUNG.put(pack('ㅗ', 'ㅐ'), 'ㅙ');
        COMPOUND_JUNG.put(pack('ㅗ', 'ㅣ'), 'ㅚ');
        COMPOUND_JUNG.put(pack('ㅜ', 'ㅓ'), 'ㅝ');
        COMPOUND_JUNG.put(pack('ㅜ', 'ㅔ'), 'ㅞ');
        COMPOUND_JUNG.put(pack('ㅜ', 'ㅣ'), 'ㅟ');
        COMPOUND_JUNG.put(pack('ㅡ', 'ㅣ'), 'ㅢ');
    }

    // 복합 종성: (first, second) → combined
    private static final Map<Long, Character> COMPOUND_JONG = new HashMap<>();

    static {
        COMPOUND_JONG.put(pack('ㄱ', 'ㅅ'), 'ㄳ');
        COMPOUND_JONG.put(pack('ㄴ', 'ㅈ'), 'ㄵ');
        COMPOUND_JONG.put(pack('ㄴ', 'ㅎ'), 'ㄶ');
        COMPOUND_JONG.put(pack('ㄹ', 'ㄱ'), 'ㄺ');
        COMPOUND_JONG.put(pack('ㄹ', 'ㅁ'), 'ㄻ');
        COMPOUND_JONG.put(pack('ㄹ', 'ㅂ'), 'ㄼ');
        COMPOUND_JONG.put(pack('ㄹ', 'ㅅ'), 'ㄽ');
        COMPOUND_JONG.put(pack('ㄹ', 'ㅌ'), 'ㄾ');
        COMPOUND_JONG.put(pack('ㄹ', 'ㅍ'), 'ㄿ');
        COMPOUND_JONG.put(pack('ㄹ', 'ㅎ'), 'ㅀ');
        COMPOUND_JONG.put(pack('ㅂ', 'ㅅ'), 'ㅄ');
    }

    // 복합 종성 분해: combined → [first, second]
    private static final Map<Character, char[]> SPLIT_JONG = new HashMap<>();

    static {
        SPLIT_JONG.put('ㄳ', new char[]{'ㄱ', 'ㅅ'});
        SPLIT_JONG.put('ㄵ', new char[]{'ㄴ', 'ㅈ'});
        SPLIT_JONG.put('ㄶ', new char[]{'ㄴ', 'ㅎ'});
        SPLIT_JONG.put('ㄺ', new char[]{'ㄹ', 'ㄱ'});
        SPLIT_JONG.put('ㄻ', new char[]{'ㄹ', 'ㅁ'});
        SPLIT_JONG.put('ㄼ', new char[]{'ㄹ', 'ㅂ'});
        SPLIT_JONG.put('ㄽ', new char[]{'ㄹ', 'ㅅ'});
        SPLIT_JONG.put('ㄾ', new char[]{'ㄹ', 'ㅌ'});
        SPLIT_JONG.put('ㄿ', new char[]{'ㄹ', 'ㅍ'});
        SPLIT_JONG.put('ㅀ', new char[]{'ㄹ', 'ㅎ'});
        SPLIT_JONG.put('ㅄ', new char[]{'ㅂ', 'ㅅ'});
    }

    private QwertyToKoreanConverter() {
    }

    /**
     * 문자열이 영문 자판 한글 입력인지 판별한다.
     * 모든 문자가 2벌식 키맵에 존재하고,
     * 변환 결과가 전부 완성 음절(+공백)로만 구성될 때 true.
     *
     * "steak" → "ㄴㅅㄷ마" (낱자모 포함) → false
     * "rlaclWlro" → "김치찌개" (모두 완성 음절) → true
     */
    public static boolean isEnglishKeyboard(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != ' ' && !KEY_MAP.containsKey(ch)) {
                return false;
            }
        }
        // 변환 결과의 모든 문자가 완성 음절(0xAC00~0xD7A3) 또는 공백이어야 함
        String converted = convert(text);
        boolean hasSyllable = false;
        for (int i = 0; i < converted.length(); i++) {
            char ch = converted.charAt(i);
            if (ch == ' ') {
                continue;
            }
            if (ch >= 0xAC00 && ch <= 0xD7A3) {
                hasSyllable = true;
            } else {
                // 낱자모(ㄱ~ㅎ, ㅏ~ㅣ)나 기타 문자가 남아 있으면 영문 자판 입력이 아님
                return false;
            }
        }
        return hasSyllable;
    }

    /**
     * QWERTY 영문 입력을 한글로 변환한다.
     * 예: "rlaclWlro" → "김치찌개"
     */
    public static String convert(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }

        // 1) 영문 키 → 한글 자모 매핑
        char[] jamos = new char[text.length()];
        for (int i = 0; i < text.length(); i++) {
            Character mapped = KEY_MAP.get(text.charAt(i));
            jamos[i] = mapped != null ? mapped : text.charAt(i);
        }

        // 2) 자모 → 음절 조합 (2벌식 오토마톤)
        StringBuilder result = new StringBuilder();
        int cho = -1, jung = -1, jongIdx = 0;

        for (char j : jamos) {
            int ci = choIndex(j);
            int ji = jungIndex(j);

            if (ci >= 0) {
                // 자음 입력
                if (cho < 0) {
                    cho = ci;
                } else if (jung < 0) {
                    flushCho(result, cho);
                    cho = ci;
                } else if (jongIdx == 0) {
                    int jg = jongIndex(j);
                    if (jg > 0) {
                        jongIdx = jg;
                    } else {
                        result.append(compose(cho, jung, 0));
                        cho = ci; jung = -1; jongIdx = 0;
                    }
                } else {
                    Character compound = COMPOUND_JONG.get(pack(JONG[jongIdx], j));
                    if (compound != null) {
                        int compIdx = jongIndex(compound);
                        if (compIdx > 0) {
                            jongIdx = compIdx;
                            continue;
                        }
                    }
                    result.append(compose(cho, jung, jongIdx));
                    cho = ci; jung = -1; jongIdx = 0;
                }
            } else if (ji >= 0) {
                // 모음 입력
                if (cho < 0) {
                    result.append(JUNG[ji]);
                } else if (jung < 0) {
                    jung = ji;
                } else if (jongIdx == 0) {
                    Character compound = COMPOUND_JUNG.get(pack(JUNG[jung], j));
                    if (compound != null) {
                        jung = jungIndex(compound);
                    } else {
                        result.append(compose(cho, jung, 0));
                        cho = -1; jung = -1;
                        result.append(JUNG[ji]);
                    }
                } else {
                    // 종성이 있는데 모음이 오면 → 종성을 다음 초성으로
                    char jongChar = JONG[jongIdx];
                    char[] split = SPLIT_JONG.get(jongChar);
                    if (split != null) {
                        int firstJong = jongIndex(split[0]);
                        int newCho = choIndex(split[1]);
                        result.append(compose(cho, jung, firstJong));
                        cho = newCho; jung = ji; jongIdx = 0;
                    } else {
                        int newCho = choIndex(jongChar);
                        result.append(compose(cho, jung, 0));
                        cho = newCho >= 0 ? newCho : -1;
                        jung = ji;
                        jongIdx = 0;
                    }
                }
            } else {
                // 자모가 아닌 문자 (공백 등)
                flush(result, cho, jung, jongIdx);
                result.append(j);
                cho = -1; jung = -1; jongIdx = 0;
            }
        }

        flush(result, cho, jung, jongIdx);
        return result.toString();
    }

    private static void flush(StringBuilder sb, int cho, int jung, int jongIdx) {
        if (cho >= 0) {
            if (jung >= 0) {
                sb.append(compose(cho, jung, jongIdx));
            } else {
                flushCho(sb, cho);
            }
        }
    }

    private static void flushCho(StringBuilder sb, int cho) {
        sb.append(CHO[cho]);
    }

    private static char compose(int cho, int jung, int jong) {
        return (char) (0xAC00 + cho * 21 * 28 + jung * 28 + jong);
    }

    private static int choIndex(char ch) {
        for (int i = 0; i < CHO.length; i++) {
            if (CHO[i] == ch) return i;
        }
        return -1;
    }

    private static int jungIndex(char ch) {
        for (int i = 0; i < JUNG.length; i++) {
            if (JUNG[i] == ch) return i;
        }
        return -1;
    }

    private static int jongIndex(char ch) {
        for (int i = 1; i < JONG.length; i++) {
            if (JONG[i] == ch) return i;
        }
        return -1;
    }

    private static long pack(char a, char b) {
        return ((long) a << 16) | b;
    }
}
