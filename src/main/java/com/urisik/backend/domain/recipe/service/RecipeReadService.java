package com.urisik.backend.domain.recipe.service;

import com.urisik.backend.domain.allergy.enums.Allergen;
import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.recipe.converter.RecipeDetailConverter;
import com.urisik.backend.domain.recipe.converter.RecipeTextParser;
import com.urisik.backend.domain.recipe.dto.RecipeStepDetailDTO;
import com.urisik.backend.domain.recipe.dto.res.AllergyWarningDTO;
import com.urisik.backend.domain.recipe.dto.res.RecipeDetailResponseDTO;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.entity.RecipeExternalMetadata;
import com.urisik.backend.domain.recipe.entity.RecipeStep;
import com.urisik.backend.domain.recipe.enums.RecipeErrorCode;
import com.urisik.backend.domain.recipe.enums.SourceType;
import com.urisik.backend.domain.recipe.infrastructure.external.foodsafety.FoodSafetyRecipeClient;
import com.urisik.backend.domain.recipe.infrastructure.external.foodsafety.dto.FoodSafetyRecipeResponse;
import com.urisik.backend.domain.recipe.repository.RecipeExternalMetadataRepository;
import com.urisik.backend.domain.recipe.repository.RecipeRepository;
import com.urisik.backend.domain.recipe.repository.RecipeStepRepository;
import com.urisik.backend.global.apiPayload.code.GeneralErrorCode;
import com.urisik.backend.global.apiPayload.exception.GeneralException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class RecipeReadService {

    private final RecipeRepository recipeRepository;
    private final RecipeExternalMetadataRepository metadataRepository;
    private final FoodSafetyRecipeClient foodSafetyRecipeClient;
    private final RecipeStepRepository recipeStepRepository;

    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final AllergyRiskService allergyRiskService;
    private final RecipeElasticSearchService recipeElasticSearchService;

    /**
     * 내부 레시피 상세 조회 (이미 DB에 있는 recipe)
     * - 로그인 사용자 기준
     * - 가족방 알레르기 판별 포함
     */
    @Transactional(readOnly = true)
    public RecipeDetailResponseDTO getRecipeDetail(
            Long recipeId,
            Long loginUserId
    ) {
        // 1. 레시피 조회
        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() ->
                        new GeneralException(RecipeErrorCode.RECIPE_NOT_FOUND));

        // 2️. 외부 메타데이터 조회 (없을 수도 있음)
        RecipeExternalMetadata meta =
                metadataRepository.findByRecipe_Id(recipe.getId())
                        .orElse(null);

        if (meta == null && recipe.getSourceType() == SourceType.EXTERNAL_API) {
            // 외부 레시피인데 메타가 없으면 강제 보완
            loadOrCreateByExternalId(recipe.getSourceRef());

            // 다시 조회
            meta = metadataRepository.findByRecipe_Id(recipe.getId())
                    .orElse(null);
        }

        // 3️. 로그인 사용자 → 가족방 조회
        FamilyMemberProfile profile =
                familyMemberProfileRepository.findByMember_Id(loginUserId)
                        .orElseThrow(() ->
                                new GeneralException(GeneralErrorCode.NOT_FOUND));

        Long familyRoomId = profile.getFamilyRoom().getId();

        // 4. 재료 파싱
        List<String> ingredients =
                RecipeTextParser.parseIngredients(recipe.getIngredientsRaw());

        // 5️. 가족 기준 알레르기 판별
        List<Allergen> risky =
                allergyRiskService.detectRiskAllergens(familyRoomId, ingredients);

        // 6️. AllergyWarningDTO 생성
        RecipeDetailResponseDTO.AllergyWarningDTO warning =
                risky.isEmpty()
                        ? new RecipeDetailResponseDTO.AllergyWarningDTO(
                        false,
                        List.of()
                )
                        : new RecipeDetailResponseDTO.AllergyWarningDTO(
                        true,
                        risky.stream()
                                .map(Allergen::getKoreanName)
                                .toList()
                );

        // 7. recipe_step 테이블에서 단계 조회
        List<RecipeStep> stepEntities =
                recipeStepRepository
                        .findAllByRecipe_IdInOrderByRecipe_IdAscStepOrderAsc(
                                List.of(recipe.getId())
                        );

        // 8. Step → DTO 변환 (imageUrl 포함)
        List<RecipeStepDetailDTO> stepDtos =
                stepEntities.stream()
                        .map(step -> new RecipeStepDetailDTO(
                                step.getStepOrder(),
                                step.getDescription(),
                                step.getImageUrl()
                        ))
                        .toList();

        return RecipeDetailConverter.toDetailDto(
                recipe,
                meta,
                warning,
                ingredients,
                stepDtos
        );
    }

    /**
     * 외부 API 레시피 상세 정보 내부 저장
     * (이 메서드는 알레르기 판별 X — 저장 전용)
     */
    @Transactional
    public Recipe loadOrCreateByExternalId(String rcpSeq) {

        return recipeRepository.findBySourceRef(rcpSeq)
                .orElseGet(() -> {

                    FoodSafetyRecipeResponse.Row row =
                            foodSafetyRecipeClient.fetchOneByRcpSeq(rcpSeq);

                    if (row == null) {
                        throw new GeneralException(
                                RecipeErrorCode.EXTERNAL_RECIPE_NOT_FOUND,
                                "외부 레시피를 찾을 수 없습니다. rcpSeq=" + rcpSeq
                        );
                    }

                    String title = requiredText(row.getRcpNm(), "RCP_NM");
                    String ingredientsRaw =
                            requiredText(row.getIngredientsRaw(), "RCP_PARTS_DTLS");
                    String instructionsRaw =
                            requiredText(joinManuals(row), "MANUAL01~20");

                    Recipe recipe = new Recipe(
                            title,
                            ingredientsRaw,
                            instructionsRaw,
                            SourceType.EXTERNAL_API,
                            nullableText(row.getRcpSeq())
                    );

                    Recipe saved = recipeRepository.save(recipe);

                    RecipeExternalMetadata meta = new RecipeExternalMetadata(
                            saved,
                            emptyIfNull(row.getCategory()),
                            emptyIfNull(row.getServingWeight()),
                            safeInt(row.getCalorie()),
                            safeInt(row.getCarbohydrate()),
                            safeInt(row.getProtein()),
                            safeInt(row.getFat()),
                            safeInt(row.getSodium()),
                            emptyIfNull(row.getImageSmall()),
                            emptyIfNull(row.getImageLarge())
                    );

                    metadataRepository.save(meta);

                    // ES 실시간 동기화
                    recipeElasticSearchService.indexRecipe(saved, meta);

                    return saved;
                });
    }

    /* ================= 내부 헬퍼 메서드 ================= */

    private String joinManuals(FoodSafetyRecipeResponse.Row row) {
        return Stream.of(
                        row.getManual01(), row.getManual02(), row.getManual03(),
                        row.getManual04(), row.getManual05(), row.getManual06(),
                        row.getManual07(), row.getManual08(), row.getManual09(),
                        row.getManual10(), row.getManual11(), row.getManual12(),
                        row.getManual13(), row.getManual14(), row.getManual15(),
                        row.getManual16(), row.getManual17(), row.getManual18(),
                        row.getManual19(), row.getManual20()
                )
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private Integer safeInt(String s) {
        try {
            if (s == null || s.isBlank()) return null;
            return (int) Double.parseDouble(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String requiredText(String s, String fieldName) {
        if (s == null) {
            throw new GeneralException(
                    RecipeErrorCode.EXTERNAL_RECIPE_NOT_FOUND,
                    fieldName + " is null"
            );
        }
        String t = s.trim();
        if (t.isBlank()) {
            throw new GeneralException(
                    RecipeErrorCode.EXTERNAL_RECIPE_NOT_FOUND,
                    fieldName + " is blank"
            );
        }
        return t;
    }

    private String nullableText(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    private String emptyIfNull(String s) {
        if (s == null) return "";
        String t = s.trim();
        return t.isBlank() ? "" : t;
    }
}
