package com.urisik.backend.domain.recipe.infrastructure.external.foodsafety;

import com.urisik.backend.domain.recipe.infrastructure.external.foodsafety.dto.FoodSafetyRecipeResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 식품안전나라 공공 API 클라이언트 구현체.
 *
 * <p>Resilience4j를 적용하여 외부 API 장애에 대응한다.</p>
 * <ul>
 *   <li>{@code @Retry}: 일시적 오류 시 최대 3회 재시도 (1초 간격)</li>
 *   <li>{@code @CircuitBreaker}: 연속 실패 시 서킷을 열어 API 호출을 차단하고 fallback 반환</li>
 * </ul>
 *
 * <p>어노테이션 순서: {@code @Retry}가 {@code @CircuitBreaker}보다 위에 위치하여
 * Retry가 먼저 실행되고, 재시도 모두 실패한 경우에만 CircuitBreaker가 실패로 기록한다.</p>
 *
 * @see io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
 * @see io.github.resilience4j.retry.annotation.Retry
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FoodSafetyRecipeClientImpl implements FoodSafetyRecipeClient {

    private final RestTemplate restTemplate;

    @Value("${foodsafety.api.key}")
    private String apiKey;

    @Value("${foodsafety.api.serviceId:COOKRCP01}")
    private String serviceId;

    private static final String BASE = "http://openapi.foodsafetykorea.go.kr/api";

    /**
     * RCP_SEQ로 외부 레시피 단건 조회.
     *
     * <p>장애 시 {@link #fetchOneByRcpSeqFallback}이 호출되어 null을 반환한다.</p>
     *
     * @param rcpSeq 식품안전나라 레시피 일련번호
     * @return 레시피 Row, 없거나 장애 시 null
     */
    @Override
    @Retry(name = "foodSafetyApi")
    @CircuitBreaker(name = "foodSafetyApi", fallbackMethod = "fetchOneByRcpSeqFallback")
    public FoodSafetyRecipeResponse.Row fetchOneByRcpSeq(String rcpSeq) {

        String url = String.format(
                "%s/%s/%s/json/1/10/RCP_SEQ=%s",
                BASE, apiKey, serviceId, rcpSeq
        );

        log.info("FoodSafety fetchOneByRcpSeq url = {}", url);

        ResponseEntity<FoodSafetyRecipeResponse> res =
                restTemplate.getForEntity(url, FoodSafetyRecipeResponse.class);

        FoodSafetyRecipeResponse body = res.getBody();

        if (body == null ||
                body.getCookrcp01() == null ||
                body.getCookrcp01().getRow() == null ||
                body.getCookrcp01().getRow().isEmpty()) {
            return null;
        }

        return body.getCookrcp01().getRow().stream()
                .filter(r -> rcpSeq.equals(r.getRcpSeq()))
                .findFirst()
                .orElse(null);
    }

    /**
     * {@link #fetchOneByRcpSeq} 실패 시 fallback. 서킷 OPEN 또는 재시도 소진 시 호출된다.
     */
    private FoodSafetyRecipeResponse.Row fetchOneByRcpSeqFallback(String rcpSeq, Throwable t) {
        log.warn("[FoodSafety] fetchOneByRcpSeq 실패, null 반환. rcpSeq={}, cause={}", rcpSeq, t.getMessage());
        return null;
    }

    /**
     * 레시피명으로 외부 API 검색.
     *
     * <p>장애 시 {@link #searchByNameFallback}이 호출되어 빈 리스트를 반환한다.
     * 캐시 히트 시에는 API 호출 없이 캐시된 결과를 반환한다.</p>
     *
     * @param keyword  검색 키워드 (레시피명)
     * @param startIdx 페이징 시작 인덱스
     * @param endIdx   페이징 끝 인덱스
     * @return 검색된 레시피 목록, 장애 시 빈 리스트
     */
    @Override
    @Retry(name = "foodSafetyApi")
    @CircuitBreaker(name = "foodSafetyApi", fallbackMethod = "searchByNameFallback")
    @Cacheable(value = "externalRecipeSearch", key = "#keyword + ':' + #startIdx + ':' + #endIdx")
    public List<FoodSafetyRecipeResponse.Row> searchByName(String keyword, int startIdx, int endIdx) {
        String filter = "RCP_NM=" + keyword;
        String encoded = URLEncoder.encode(filter, StandardCharsets.UTF_8);

        String url = String.format("%s/%s/%s/json/%d/%d/%s", BASE, apiKey, serviceId, startIdx, endIdx, encoded);

        ResponseEntity<FoodSafetyRecipeResponse> res =
                restTemplate.exchange(url, HttpMethod.GET, null, FoodSafetyRecipeResponse.class);

        FoodSafetyRecipeResponse body = res.getBody();
        if (body == null || body.getCookrcp01() == null || body.getCookrcp01().getRow() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(body.getCookrcp01().getRow());
    }

    /**
     * {@link #searchByName} 실패 시 fallback. 서킷 OPEN 또는 재시도 소진 시 호출된다.
     */
    private List<FoodSafetyRecipeResponse.Row> searchByNameFallback(String keyword, int startIdx, int endIdx, Throwable t) {
        log.warn("[FoodSafety] searchByName 실패, 빈 리스트 반환. keyword={}, cause={}", keyword, t.getMessage());
        return List.of();
    }
}
