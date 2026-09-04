package com.urisik.backend.domain.review.service;

import com.urisik.backend.domain.familyroom.exception.FamilyRoomException;
import com.urisik.backend.domain.familyroom.exception.code.FamilyRoomErrorCode;
import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import com.urisik.backend.domain.member.repo.FamilyMemberProfileRepository;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.domain.recipe.entity.TransformedRecipe;
import com.urisik.backend.domain.recipe.repository.RecipeRepository;
import com.urisik.backend.domain.recipe.repository.TransformedRecipeRepository;
import com.urisik.backend.domain.review.converter.ReviewConverter;
import com.urisik.backend.domain.review.converter.TransformedReviewConverter;
import com.urisik.backend.domain.review.dto.ReviewRequestDto;
import com.urisik.backend.domain.review.dto.ReviewResponseDto;
import com.urisik.backend.domain.review.entity.Review;
import com.urisik.backend.domain.review.entity.TransformedRecipeReview;
import com.urisik.backend.domain.review.exception.ReviewErrorCode;
import com.urisik.backend.domain.review.exception.ReviewException;
import com.urisik.backend.domain.review.repository.ReviewRepository;
import com.urisik.backend.domain.review.repository.TransformedRecipeReviewRepository;
import com.urisik.backend.global.apiPayload.code.GeneralErrorCode;
import com.urisik.backend.global.apiPayload.exception.GeneralException;
import com.urisik.backend.global.auth.exception.AuthenExcetion;
import com.urisik.backend.global.auth.exception.code.AuthErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TransformedReviewService {

    private final TransformedRecipeReviewRepository reviewRepository;
    private final FamilyMemberProfileRepository familyMemberRepository;
    private final TransformedRecipeRepository transformedRecipeRepository;

    /**
     * 변형 레시피 리뷰 작성하기
     */
    @Transactional
    public ReviewResponseDto createReview(
            ReviewRequestDto requestDto,
            Long memberId,
            Long transformedRecipeId
    ) {

        FamilyMemberProfile familyMember = getFamilyMember(memberId);

        TransformedRecipe recipe =
                transformedRecipeRepository.findById(transformedRecipeId)
                        .orElseThrow(() ->
                                new GeneralException(GeneralErrorCode.NOT_FOUND)
                        );

        // 데이터 저장 — unique constraint로 중복 방지
        TransformedRecipeReview review =
                TransformedReviewConverter.toReview(familyMember, recipe, requestDto);
        try {
            reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            if (e.getMostSpecificCause() instanceof java.sql.SQLIntegrityConstraintViolationException sqlEx
                    && sqlEx.getErrorCode() == 1062) {
                throw new ReviewException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
            }
            throw e;
        }

        // atomic UPDATE 쿼리로 카운터 갱신 (clearAutomatically=true로 영속성 컨텍스트 자동 비움)
        int newScore = review.getScore();
        transformedRecipeRepository.incrementReviewCount(transformedRecipeId);
        transformedRecipeRepository.updateAvgScore(transformedRecipeId, newScore);

        // 영속성 컨텍스트가 비워졌으므로 DB에서 최신 값 조회
        double updatedAvgScore = transformedRecipeRepository.findById(transformedRecipeId)
                .map(TransformedRecipe::getAvgScore)
                .orElse(0.0);

        return TransformedReviewConverter.toReviewResponseDto(
                review,
                updatedAvgScore
        );
    }

    private FamilyMemberProfile getFamilyMember(Long memberId) {

        if (memberId == null) {
            throw new AuthenExcetion(AuthErrorCode.TOKEN_NOT_VALID);
        }

        return familyMemberRepository.findByMember_Id(memberId)
                .orElseThrow(() ->
                        new FamilyRoomException(FamilyRoomErrorCode.MEMBER_NOT_FOUND)
                );
    }
}
