package com.urisik.backend.global.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QwertyToKoreanConverterTest {

    @Test
    void 영문자판_한글입력은_변환한다() {
        assertThat(QwertyToKoreanConverter.isEnglishKeyboard("rlaclWlro")).isTrue();
        assertThat(QwertyToKoreanConverter.convert("rlaclWlro")).isEqualTo("김치찌개");

        assertThat(QwertyToKoreanConverter.isEnglishKeyboard("ehsRktm")).isTrue();
        assertThat(QwertyToKoreanConverter.convert("ehsRktm")).isEqualTo("돈까스");
    }

    @Test
    void 정상_영어단어는_변환하지_않는다() {
        // "steak" → convert 결과 "ㄴㅅㄷ마" (낱자모 포함) → isEnglishKeyboard = false
        assertThat(QwertyToKoreanConverter.isEnglishKeyboard("steak")).isFalse();
        assertThat(QwertyToKoreanConverter.isEnglishKeyboard("bacon")).isFalse();
        assertThat(QwertyToKoreanConverter.isEnglishKeyboard("salad")).isFalse();
    }

    @Test
    void 한글_입력은_변환대상이_아니다() {
        assertThat(QwertyToKoreanConverter.isEnglishKeyboard("김치찌개")).isFalse();
        assertThat(QwertyToKoreanConverter.isEnglishKeyboard("된장찌개")).isFalse();
    }

    @Test
    void 빈_문자열과_null은_false() {
        assertThat(QwertyToKoreanConverter.isEnglishKeyboard(null)).isFalse();
        assertThat(QwertyToKoreanConverter.isEnglishKeyboard("")).isFalse();
        assertThat(QwertyToKoreanConverter.isEnglishKeyboard("  ")).isFalse();
    }
}
