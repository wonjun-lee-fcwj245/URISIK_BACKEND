package com.urisik.backend.domain.recipe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urisik.backend.domain.allergy.entity.AllergenAlternative;
import com.urisik.backend.domain.allergy.enums.Allergen;
import com.urisik.backend.domain.allergy.service.AllergySubstitutionService;
import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.recipe.converter.RecipeTextParser;
import com.urisik.backend.domain.recipe.converter.TransformedRecipeConverter;
import com.urisik.backend.domain.recipe.dto.RecipeStepDTO;
import com.urisik.backend.domain.recipe.dto.res.TransformedRecipeCreateResponseDTO;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.entity.RecipeExternalMetadata;
import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import com.urisik.backend.domain.recipe.entity.TransformedRecipeStepImage;
import com.urisik.backend.domain.recipe.enums.RecipeErrorCode;
import com.urisik.backend.domain.recipe.infrastructure.external.ai.AiJsonExtractor;
import com.urisik.backend.domain.recipe.infrastructure.external.ai.GeminiImagePromptBuilder;
import com.urisik.backend.domain.recipe.infrastructure.external.ai.GeminiPromptBuilder;
import com.urisik.backend.domain.recipe.infrastructure.external.ai.dto.GeminiTransformResult;
import com.urisik.backend.domain.recipe.repository.RecipeExternalMetadataRepository;
import com.urisik.backend.domain.recipe.repository.RecipeRepository;
import com.urisik.backend.domain.recipe.repository.TransformedRecipeRepository;
import com.urisik.backend.domain.recipe.repository.TransformedRecipeStepImageRepository;
import com.urisik.backend.global.ai.AiImageClient;
import com.urisik.backend.global.ai.GeminiClient;
import com.urisik.backend.global.apiPayload.code.GeneralErrorCode;
import com.urisik.backend.global.apiPayload.exception.GeneralException;
import com.urisik.backend.global.external.s3.S3Uploader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TransformedRecipeCreateService {

    private final RecipeRepository recipeRepository;
    private final RecipeExternalMetadataRepository metadataRepository;
    private final TransformedRecipeRepository transformedRecipeRepository;
    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final AllergySubstitutionService allergySubstitutionService;
    private final GeminiClient geminiClient;
    private final ObjectMapper objectMapper;

    private final AiImageClient aiImageClient;
    private final S3Uploader s3Uploader;
    private final TransformedRecipeStepImageRepository stepImageRepository;
    private final TransformedRecipeStepImageAsyncService stepImageAsyncService;
    private final RecipeElasticSearchService recipeElasticSearchService;

    public TransformedRecipeCreateResponseDTO create(
            Long recipeId,
            Long loginUserId
    ) {
        // 1. 원본 레시피
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new GeneralException(RecipeErrorCode.RECIPE_NOT_FOUND));

        // 2️. 가족방
        FamilyMemberProfile profile =
                familyMemberProfileRepository.findByMember_Id(loginUserId)
                        .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));
        Long familyRoomId = profile.getFamilyRoom().getId();

        // 3️. 재료 파싱
        List<String> ingredients =
                RecipeTextParser.parseIngredients(recipe.getIngredientsRaw());

        // 4️. 알레르기 대체 규칙 생성
        Map<Allergen, List<AllergenAlternative>> rules =
                allergySubstitutionService.generateSubstitutionRules(familyRoomId, ingredients);

        if (rules.isEmpty()) {
            return null;
        }

        // 5️. 프롬프트 생성
        String prompt = GeminiPromptBuilder.build(
                recipe,
                ingredients,
                RecipeTextParser.parseSteps(recipe.getInstructionsRaw()),
                rules
        );

        // 6️. Gemini 호출
        String aiText = geminiClient.generateJson(prompt);

       // 원본 로그 (디버깅용)
        log.info("[AI][RAW]\n{}", aiText);

        // JSON만 추출
        String jsonOnly = AiJsonExtractor.extractJson(aiText);

        // 7️. 파싱
        GeminiTransformResult result;
        try {
            result = objectMapper.readValue(jsonOnly, GeminiTransformResult.class);
        } catch (Exception e) {
            throw new GeneralException(
                    GeneralErrorCode.INTERNAL_SERVER_ERROR,
                    "Gemini 응답 파싱 실패"
            );
        }

        // 8️. 검증
        boolean valid = validate(result, rules);

        String finalImageUrl = generateRecipeImage(result, recipe);

        // 9️. 저장
        TransformedRecipe tr = new TransformedRecipe(
                recipe,
                familyRoomId,
                result.getTitle(),
                String.join("\n", result.getIngredients()),
                result.getSteps().stream()
                        .map(s -> s.getOrder() + ". " + s.getDescription())
                        .collect(Collectors.joining("\n")),
                toJson(result.getSubstitutionSummary()),
                finalImageUrl
        );
        tr.updateValidationStatus(valid);

        transformedRecipeRepository.save(tr);

        // ES 실시간 동기화
        RecipeExternalMetadata baseMeta = metadataRepository.findByRecipe_Id(recipe.getId()).orElse(null);
        recipeElasticSearchService.indexTransformedRecipe(tr, baseMeta);

        stepImageAsyncService.generateStepImagesAsync(
                tr.getId(),
                result.getTitle(),
                result.getSteps()
        );

        return TransformedRecipeConverter.toCreateResponse(
                tr,
                recipe,
                result
        );
    }

    /* =====================
       대표 이미지 생성
       ===================== */
    private String generateRecipeImage(
            GeminiTransformResult result,
            Recipe recipe
    ) {
        try {
            return aiImageClient.generateImage(
                            GeminiImagePromptBuilder.recipeImage(
                                    result.getTitle(),
                                    result.getIngredients()
                            )
                    )
                    .map(bytes ->
                            s3Uploader.uploadBytes(
                                    bytes,
                                    "recipe.png",
                                    "image/png",
                                    "transformed-recipe"
                            )
                    )
                    .orElseGet(() ->
                            metadataRepository.findByRecipe_Id(recipe.getId())
                                    .map(RecipeExternalMetadata::getImageLargeUrl)
                                    .orElse(null)
                    );
        } catch (Exception e) {
            log.warn("[AI][IMAGE] recipe image fallback", e);
            return metadataRepository.findByRecipe_Id(recipe.getId())
                    .map(RecipeExternalMetadata::getImageLargeUrl)
                    .orElse(null);
        }
    }

    /* =====================
       단계별 이미지 생성
       ===================== */
    private void generateStepImages(
            Long transformedRecipeId,
            GeminiTransformResult result
    ) {
        for (RecipeStepDTO step : result.getSteps()) {
            aiImageClient.generateImage(
                    GeminiImagePromptBuilder.stepImage(
                            result.getTitle(),
                            step
                    )
            ).ifPresent(bytes -> {
                String url = s3Uploader.uploadBytes(
                        bytes,
                        "step_" + step.getOrder() + ".png",
                        "image/png",
                        "transformed-recipe-step"
                );
                stepImageRepository.save(
                        new TransformedRecipeStepImage(
                                transformedRecipeId,
                                step.getOrder(),
                                url
                        )
                );
            });
        }
    }

    private boolean validate(
            GeminiTransformResult result,
            Map<Allergen, List<AllergenAlternative>> rules
    ) {
        if (result.getIngredients() == null || result.getSteps() == null) {
            return false;
        }

        // 1. 재료에 알레르기 재등장 여부
        for (Allergen allergen : rules.keySet()) {
            boolean found = result.getIngredients().stream()
                    .anyMatch(allergen::matchesIngredient);
            if (found) return false;
        }


        // 2. 조리 단계에 알레르기 재등장 여부
        for (Allergen allergen : rules.keySet()) {
            boolean found = result.getSteps().stream()
                    .anyMatch(step -> allergen.matchesIngredient(step.getDescription()));
            if (found) return false;
        }


        // 3️. step order 연속성
        int expectedOrder = 1;
        for (RecipeStepDTO step : result.getSteps()) {
            if (step.getOrder() != expectedOrder++) {
                return false;
            }
        }

        // 4️. substitutionSummary 일치 여부
        if (result.getSubstitutionSummary() == null ||
                result.getSubstitutionSummary().size() != rules.size()) {
            return false;
        }

        for (GeminiTransformResult.SubstitutionSummaryDTO dto
                : result.getSubstitutionSummary()) {

            Allergen allergen;
            try {
                allergen = Allergen.valueOf(dto.getAllergen());
            } catch (Exception e) {
                return false;
            }

            List<AllergenAlternative> expected = rules.get(allergen);
            if (expected == null) return false;

            boolean matched =
                    expected.stream().anyMatch(alt ->
                            alt.getIngredient().getName()
                                    .equals(dto.getReplacedWith())
                    );

            if (!matched) return false;
        }

        return true;
    }


    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }
}
