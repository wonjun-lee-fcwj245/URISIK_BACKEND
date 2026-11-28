package com.urisik.backend.domain.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import com.urisik.backend.domain.recipe.infrastructure.external.foodsafety.FoodSafetyRecipeClientImpl;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** #33: 환경 변수로 넣은 설정이 적용되려면 인스턴스 이름이 소문자여야 한다(Map 키 바인딩이 소문자). */
class FoodSafetyResilienceInstanceNameTest {

    @Test
    void retryAndCircuitBreakerUseLowercaseInstanceName() {
        Method[] annotated = Arrays.stream(FoodSafetyRecipeClientImpl.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Retry.class) || m.isAnnotationPresent(CircuitBreaker.class))
                .toArray(Method[]::new);
        assertThat(annotated).isNotEmpty();
        for (Method m : annotated) {
            if (m.isAnnotationPresent(Retry.class)) {
                String name = m.getAnnotation(Retry.class).name();
                assertThat(name).isEqualTo(name.toLowerCase()).isEqualTo("foodsafetyapi");
            }
            if (m.isAnnotationPresent(CircuitBreaker.class)) {
                String name = m.getAnnotation(CircuitBreaker.class).name();
                assertThat(name).isEqualTo(name.toLowerCase()).isEqualTo("foodsafetyapi");
            }
        }
    }
}
