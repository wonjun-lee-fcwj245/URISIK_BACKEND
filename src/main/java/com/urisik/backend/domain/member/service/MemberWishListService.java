package com.urisik.backend.domain.member.service;


import com.urisik.backend.domain.allergy.enums.Allergen;
import com.urisik.backend.domain.familyroom.repository.FamilyWishListExclusionRepository;
import com.urisik.backend.domain.member.dto.req.WishListRequest;
import com.urisik.backend.domain.member.dto.res.WishListResponse;
import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.entity.Member;
import com.urisik.backend.domain.member.entity.MemberTransformedRecipeWish;
import com.urisik.backend.domain.member.entity.MemberWishList;
import com.urisik.backend.domain.member.exception.MemberException;
import com.urisik.backend.domain.member.exception.code.MemberErrorCode;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.member.repo.MemberTransformedRecipeWishRepository;
import com.urisik.backend.domain.member.repo.MemberWishListRepository;
import com.urisik.backend.domain.recipe.converter.RecipeTextParser;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import com.urisik.backend.domain.recipe.enums.FoodSafety;
import com.urisik.backend.domain.recipe.repository.RecipeRepository;
import com.urisik.backend.domain.recipe.repository.TransformedRecipeRepository;
import com.urisik.backend.domain.recipe.service.AllergyRiskService;
import com.urisik.backend.domain.review.entity.Review;
import com.urisik.backend.domain.review.entity.TransformedRecipeReview;
import com.urisik.backend.domain.review.repository.ReviewRepository;
import com.urisik.backend.domain.review.repository.TransformedRecipeReviewRepository;
import com.urisik.backend.global.auth.exception.AuthenExcetion;
import com.urisik.backend.global.auth.exception.code.AuthErrorCode;
import com.urisik.backend.global.kafka.producer.KafkaEventProducer;
import com.urisik.backend.global.util.IngredientParser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class MemberWishListService {
    //파서
    private final IngredientParser ingredientParser;
    // 레포
    private final RecipeRepository recipeRepository;
    private final MemberWishListRepository memberWishListRepository;
    private final FamilyMemberProfileRepository familyMemberProfileRepository;
    private final FamilyWishListExclusionRepository familyWishListExclusionRepository;
    private final TransformedRecipeRepository transformedRecipeRepository;
    private final MemberTransformedRecipeWishRepository memberTransformedRecipeWishRepository;
    private final ReviewRepository reviewRepository;
    private final AllergyRiskService allergyRiskService;
    private final TransformedRecipeReviewRepository transformedRecipeReviewRepository;
    private final KafkaEventProducer kafkaEventProducer;

    @Transactional
    public WishListResponse.PostWishes addWishItems
            (Long memberId, Long familyRoomId, WishListRequest.PostWishes req) {

        FamilyMemberProfile profile = familyMemberProfileRepository
                .findByFamilyRoom_IdAndMember_Id(familyRoomId, memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY));

        // unique constraint로 중복 방지 — check-then-act 제거
        try {
            if (req.getRecipeId() != null) {
                for (Long recipeId : req.getRecipeId()) {
                    Recipe recipe = recipeRepository.findById(recipeId)
                            .orElseThrow(() -> new MemberException(MemberErrorCode.NO_RECIPE));

                    profile.addWish(MemberWishList.of(recipe));

                    // 가족 위시리스트 삭제DB 업데이트
                    familyWishListExclusionRepository.deleteByFamilyRoom_IdAndRecipeId(
                            profile.getFamilyRoom().getId(),
                            recipeId
                    );
                }
            }
            if (req.getTransformedRecipeId() != null) {
                for (Long recipeId : req.getTransformedRecipeId()) {
                    TransformedRecipe recipe = transformedRecipeRepository.findById(recipeId)
                            .orElseThrow(() -> new MemberException(MemberErrorCode.NO_TRANS_RECIPE));

                    profile.addTransWish(MemberTransformedRecipeWish.of(recipe));

                    // 가족 위시리스트 삭제DB 업데이트
                    familyWishListExclusionRepository.deleteByFamilyRoom_IdAndTransformedRecipeId(
                            profile.getFamilyRoom().getId(),
                            recipeId
                    );
                }
            }

            // flush해서 unique constraint 위반 즉시 감지
            memberWishListRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (e.getMostSpecificCause() instanceof java.sql.SQLIntegrityConstraintViolationException sqlEx
                    && sqlEx.getErrorCode() == 1062) {
                throw new MemberException(MemberErrorCode.WISH_ALREADY_IN);
            }
            throw e;
        }

        // atomic UPDATE 쿼리로 wishCount 갱신
        if (req.getRecipeId() != null) {
            for (Long recipeId : req.getRecipeId()) {
                recipeRepository.incrementWishCount(recipeId);
            }
        }
        if (req.getTransformedRecipeId() != null) {
            for (Long recipeId : req.getTransformedRecipeId()) {
                transformedRecipeRepository.incrementWishCount(recipeId);
            }
        }

        kafkaEventProducer.sendCacheEvict(
                List.of("recommendSafe", "recommendHighScore", "recommendSafeHighScore", "recommendWish")
        );

        return WishListResponse.PostWishes.builder()
                .isPosted(true)
                .build();
    }


    @Transactional
    public WishListResponse.DeleteWishes deleteWishItems
            (Long memberId,Long familyRoomId, WishListRequest.DeleteWishes req) {

        FamilyMemberProfile profile = familyMemberProfileRepository
                .findByFamilyRoom_IdAndMember_Id(familyRoomId, memberId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY));


        long deleted =0;
        long deletedTrans = 0;
        if(req.getRecipeId() != null) {

            List<Long> recipeIds = req.getRecipeId();
            long existsCount =
                    memberWishListRepository.countByFamilyMemberProfile_IdAndRecipe_IdIn(profile.getId(), recipeIds);
            if (existsCount != recipeIds.size()) {
                // 정책: 하나라도 없으면 전체 실패
                throw new MemberException(MemberErrorCode.WISH_NOT_FOUND);
                // 에러코드 새로 만드는 걸 추천: "요청한 위시 중 일부가 존재하지 않음"
            }

            deleted =
                    memberWishListRepository.deleteByFamilyMemberProfile_IdAndRecipe_IdIn(
                            profile.getId(),
                            req.getRecipeId()
                    );
            memberWishListRepository.decreaseWishCount(req.getRecipeId());
        }
        if(req.getTransformedRecipeId() != null) {

            List<Long> transIds = req.getTransformedRecipeId();
            long existsCount =
                    memberTransformedRecipeWishRepository.countByFamilyMemberProfile_IdAndRecipe_IdIn(profile.getId(), transIds);
            if (existsCount != transIds.size()) {
                throw new MemberException(MemberErrorCode.TRANS_WISH_NOT_FOUND);
            }

            deletedTrans =
                    memberTransformedRecipeWishRepository.deleteByFamilyMemberProfile_IdAndRecipe_IdIn(
                            profile.getId(),
                            req.getTransformedRecipeId()
                    );
            memberTransformedRecipeWishRepository.decreaseWishCount(req.getTransformedRecipeId());
        }

        kafkaEventProducer.sendCacheEvict(
                List.of("recommendSafe", "recommendHighScore", "recommendSafeHighScore", "recommendWish")
        );

        return WishListResponse.DeleteWishes.builder()
                .isDeleted(true)
                .deletedNum(deleted)
                .deletedTransNum(deletedTrans)
                .build();
    }


    public WishListResponse.GetWishes getMyWishes
            (Long familyRoomId, Long memberId, Long profileId, Long cursor, int size) {

        //1) 검증
        // 요청자가 자신의 프로필을 요청하는 경우
        if (profileId == -1) {
            FamilyMemberProfile mine = familyMemberProfileRepository.findByMember_Id(memberId).orElseThrow(
                    () -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY)
            );
            profileId = mine.getId();
        }

        // 요청 대상의 프로필이 있는가?
        boolean exists_req_pro = familyMemberProfileRepository.existsById(profileId);
        if (!exists_req_pro) {
            throw new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY);
        }

        // 요청자가 방안에 있는지?
        boolean exists = familyMemberProfileRepository.existsByFamilyRoom_IdAndMember_Id(familyRoomId, memberId);
        if (!exists) {
            throw new MemberException(MemberErrorCode.NOT_YOUR_ROOM);
        }

        FamilyMemberProfile profile = familyMemberProfileRepository
                .findByFamilyRoom_IdAndId(familyRoomId,profileId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY));


        // 2) 커서 페이징 조회 (size+1)
        int limit = Math.min(size, 50);
        PageRequest pageable = PageRequest.of(0, limit + 1);

        List<MemberWishList> rows = (cursor == null)
                ? memberWishListRepository.findFirstPage(profile.getId(), pageable)
                : memberWishListRepository.findNextPage(profile.getId(), cursor, pageable);

        boolean hasNext = rows.size() > limit;
        if (hasNext) rows = rows.subList(0, limit);

        Long nextCursor = rows.isEmpty() ? null : rows.get(rows.size() - 1).getId();

        // 3) DTO 변환 (WishItem 리스트)


        List<WishListResponse.WishItem> items = rows.stream()
                .map(w -> {
                    List<Allergen> risks = allergyRiskService.detectRiskAllergensForOne(
                            profile.getId(),
                            RecipeTextParser.parseIngredients(w.getRecipe().getIngredientsRaw())
                    );

                    FoodSafety safety = (risks != null && !risks.isEmpty())
                            ? FoodSafety.DANGEROUS
                            : FoodSafety.SAFETY;

                    return WishListResponse.WishItem.builder()
                            .wishId(w.getId())
                            .recipeId(w.getRecipe().getId())
                            .recipeName(w.getRecipe().getTitle())
                            .foodImage(w.getRecipe().getRecipeExternalMetadata().getImageSmallUrl())
                            .category(w.getRecipe().getRecipeExternalMetadata().getCategory())
                            .avgScore(w.getRecipe().getAvgScore())
                            .recipeIngredients(RecipeTextParser.parseIngredients(w.getRecipe().getIngredientsRaw()))
                            .foodSafety(safety)
                            .build();
                })
                .toList(); // 자바 16 미만이면 아래로 교체

        // 4) 응답
        return WishListResponse.GetWishes.builder()
                .items(items)
                .nextCursor(hasNext ? nextCursor : null)
                .hasNext(hasNext)
                .build();
    }


    public WishListResponse.GetTransWishes getMyTransWishes
            (Long familyRoomId, Long memberId, Long profileId, Long cursor, int size) {

    //1) 검증
        // 요청자가 자신의 프로필을 요청하는 경우
        if (profileId == -1) {
            FamilyMemberProfile mine = familyMemberProfileRepository.findByMember_Id(memberId).orElseThrow(
                    () -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY)
            );
            profileId = mine.getId();
        }

        // 요청 대상의 프로필이 있는가?
        boolean exists_req_pro = familyMemberProfileRepository.existsById(profileId);
        if (!exists_req_pro) {
            throw new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY);
        }

        // 요청자가 방안에 있는지?
        boolean exists = familyMemberProfileRepository.existsByFamilyRoom_IdAndMember_Id(familyRoomId, memberId);
        if (!exists) {
            throw new MemberException(MemberErrorCode.NOT_YOUR_ROOM);
        }

        FamilyMemberProfile profile = familyMemberProfileRepository
                .findByFamilyRoom_IdAndId(familyRoomId,profileId)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_PROFILE_IN_FAMILY));



        // 2) 커서 페이징 조회 (size+1)
        int limit = Math.min(size, 50);
        PageRequest pageable = PageRequest.of(0, limit + 1);

        List<MemberTransformedRecipeWish> rows = (cursor == null)
                ? memberTransformedRecipeWishRepository.findFirstPage(profile.getId(), pageable)
                : memberTransformedRecipeWishRepository.findNextPage(profile.getId(), cursor, pageable);

        boolean hasNext = rows.size() > limit;
        if (hasNext) rows = rows.subList(0, limit);

        Long nextCursor = rows.isEmpty() ? null : rows.get(rows.size() - 1).getId();

        // 3) DTO 변환 (WishItem 리스트)
        List<WishListResponse.TransWishItem> items = rows.stream()
                .map(w -> {
                    List<Allergen> risks = allergyRiskService.detectRiskAllergensForOne(
                            profile.getId(),
                            RecipeTextParser.parseIngredients(w.getRecipe().getIngredientsRaw())
                    );

                    FoodSafety safety = (risks != null && !risks.isEmpty())
                            ? FoodSafety.DANGEROUS
                            : FoodSafety.SAFETY;

                    return WishListResponse.TransWishItem.builder()
                            .wishId(w.getId())
                            .transformedRecipeId(w.getRecipe().getId())
                            .transformedRecipeName(w.getRecipe().getTitle())
                            .foodImage(w.getRecipe().getImageUrl())
                            .category(w.getRecipe().getBaseRecipe().getRecipeExternalMetadata().getCategory())
                            .avgScore(w.getRecipe().getAvgScore())
                            .recipeIngredients(RecipeTextParser.parseIngredients(w.getRecipe().getIngredientsRaw()))
                            .foodSafety(safety)
                            .build();
                })
                .toList(); // 자바 16 미만이면 아래로 교체


        // 4) 응답
        return WishListResponse.GetTransWishes.builder()
                .items(items)
                .nextCursor(hasNext ? nextCursor : null)
                .hasNext(hasNext)
                .build();
    }
    public WishListResponse.Recommendation getMyRecommendation
            (Long familyRoomId, Long memberId) {

        //1. 프로필이 있는지 검색
        FamilyMemberProfile profile = familyMemberProfileRepository.findByMember_Id(memberId)
                .orElseThrow(() -> new AuthenExcetion(AuthErrorCode.NO_MEMBER));

        //2. profile.getID + 평점 4이상인 recipe_review 찾기 이떄 recipe_review와 레시피 둘다 가져오기.
       Long recipeId = reviewRepository.findRandomHighScoreReviewId(profile.getId(),4)
                .orElseThrow(() -> new MemberException(MemberErrorCode.NO_REVIEW));

       Review review = reviewRepository.findByIdWithRecipe(recipeId).orElseThrow(
               () -> new MemberException(MemberErrorCode.NO_RECIPE)
       );

       Long transformedRecipeId = transformedRecipeReviewRepository.findRandomHighScoreReviewId(profile.getId(),4)
               .orElseThrow(() -> new MemberException(MemberErrorCode.NO_TRANS_REVIEW));

       TransformedRecipeReview transformedRecipeReview = transformedRecipeReviewRepository.
               findByIdWithRecipe(transformedRecipeId).orElseThrow(
               () -> new MemberException(MemberErrorCode.NO_TRANS_RECIPE)
       );

        //3.  recipe에 있는 ingredient 필드 파싱 해서 재료들 리스트로 저장. 재료들을 부분으로 가지고 있는 ingredient필드가 있는 레시피 검색== 레시피 리스트로 반환.

        List<String> ingredients = new ArrayList<>(ingredientParser.parseIngredients(review.getRecipe().getIngredientsRaw()));
        ingredients.addAll(ingredientParser.parseIngredients(transformedRecipeReview.getTransformedRecipe().getIngredientsRaw()));

        Collections.shuffle(ingredients);

        // 4) 재료별 검색해서 최대 5개 모으기(중복 제거)
        List<Recipe> picked = new ArrayList<>();
        Set<Long> pickedIds = new HashSet<>();

        for (String ing : ingredients) {
            if (picked.size() >= 5) break;

            List<Long> exclude = pickedIds.isEmpty() ? null : new ArrayList<>(pickedIds);

            // 재료 하나당 최대 5개만 뽑고, 그 중에서 중복 제거하며 추가
            List<Recipe> found = recipeRepository.findByIngredientLikeExcludeIdsRandom(
                    ing,
                    exclude,
                    PageRequest.of(0, 5)
            );

            for (Recipe r : found) {
                if (pickedIds.add(r.getId())) {
                    picked.add(r);
                    if (picked.size() >= 5) break;
                }
            }
        }
        List<String> recommendations = new ArrayList<>();
        for(Recipe r : picked) {
            recommendations.add(r.getTitle());
        }


        //4. 이름들을 Recommendation DTO로 만들고 반환.
        return WishListResponse.Recommendation.builder()
                .recipeName(recommendations)
                .build();
    }

}
