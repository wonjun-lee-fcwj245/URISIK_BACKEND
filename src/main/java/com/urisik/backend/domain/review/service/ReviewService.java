package com.urisik.backend.domain.review.service;

import com.urisik.backend.domain.familyroom.exception.FamilyRoomException;
import com.urisik.backend.domain.familyroom.exception.code.FamilyRoomErrorCode;
import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.repository.RecipeRepository;
import com.urisik.backend.domain.review.converter.ReviewConverter;
import com.urisik.backend.domain.review.dto.ReviewRequestDto;
import com.urisik.backend.domain.review.dto.ReviewResponseDto;
import com.urisik.backend.domain.review.entity.Review;
import com.urisik.backend.domain.review.exception.ReviewErrorCode;
import com.urisik.backend.domain.review.exception.ReviewException;
import com.urisik.backend.domain.review.repository.ReviewRepository;
import com.urisik.backend.global.apiPayload.code.GeneralErrorCode;
import com.urisik.backend.global.apiPayload.exception.GeneralException;
import com.urisik.backend.global.auth.exception.AuthenExcetion;
import com.urisik.backend.global.auth.exception.code.AuthErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final FamilyMemberProfileRepository familyMemberRepository;
    private final RecipeRepository recipeRepository;

    /**
     * 1. 리뷰 작성하기
     */
    @Caching(evict = {
            @CacheEvict(value = "recommendSafe", allEntries = true),
            @CacheEvict(value = "recommendHighScore", allEntries = true),
            @CacheEvict(value = "recommendSafeHighScore", allEntries = true),
            @CacheEvict(value = "recommendWish", allEntries = true)
    })
    @Transactional
    public ReviewResponseDto createReview(ReviewRequestDto requestDto, Long memberId, Long recipeId) {

        FamilyMemberProfile familyMember = getFamilyMember(memberId);

        Recipe recipe = recipeRepository.findById(recipeId)
                .orElseThrow(() -> new GeneralException(GeneralErrorCode.NOT_FOUND));

        // 리뷰 중복 작성 확인
        if (reviewRepository.existsByFamilyMemberProfileAndRecipe(familyMember, recipe)) {
            throw new ReviewException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
        }

        // 데이터 저장
        Review review = ReviewConverter.toReview(familyMember, recipe, requestDto);
        reviewRepository.save(review);

        // 메뉴에 대한 평균 별점 반영 + 리뷰 개수 1 증가
        Integer newScore = review.getScore();
        recipe.updateReviewCount();
        recipe.updateAvgScore(newScore);

        return ReviewConverter.toReviewResponseDto(review, recipe.getAvgScore());

    }


    private FamilyMemberProfile getFamilyMember(Long memberId) {

        if (memberId == null) {
            throw new AuthenExcetion(AuthErrorCode.TOKEN_NOT_VALID);}

        return familyMemberRepository.findByMember_Id(memberId)
                .orElseThrow(() -> new FamilyRoomException(FamilyRoomErrorCode.MEMBER_NOT_FOUND));
    }
}
