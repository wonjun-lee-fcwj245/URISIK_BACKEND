package com.urisik.backend.domain.recipe.infrastructure.external.foodsafety;

import com.urisik.backend.domain.recipe.infrastructure.external.foodsafety.dto.FoodSafetyRecipeResponse;
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

    @Override
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





    @Override
    @Cacheable(value = "externalRecipeSearch", key = "#keyword + ':' + #startIdx + ':' + #endIdx")
    public List<FoodSafetyRecipeResponse.Row> searchByName(String keyword, int startIdx, int endIdx) {
        // 외부 API 필터: RCP_NM
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
}
