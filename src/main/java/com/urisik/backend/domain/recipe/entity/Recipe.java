package com.urisik.backend.domain.recipe.entity;

import com.urisik.backend.domain.member.entity.MemberWishList;
import com.urisik.backend.domain.recipe.enums.SourceType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "recipe", indexes = {
        @Index(name = "idx_recipe_source_ref", columnList = "sourceRef"),
        @Index(name = "idx_recipe_title", columnList = "title")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@org.hibernate.annotations.DynamicUpdate
public class Recipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @OneToOne(mappedBy = "recipe", fetch = FetchType.LAZY, optional = false)
    private RecipeExternalMetadata recipeExternalMetadata;

    // 외부 API 원본 재료 문자열 그대로
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String ingredientsRaw;

    // 외부 API 단계들을 합친 원본 조리법 문자열 그대로
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String instructionsRaw;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceType sourceType;

    @Column(nullable = false)
    private int reviewCount = 0;

    //위시리스트 갯수
    @Column(nullable = false)
    private int wishCount = 0;


    @Column(nullable = false)
    private double avgScore = 0.0;

    // 점수 합계. 평균을 매번 반올림해 다시 계산에 쓰면 오차가 쌓이므로 합계를 저장하고 평균은 합계/개수로 계산한다
    @Column(name = "score_sum", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long scoreSum = 0L;

    // 외부 API RCP_SEQ 또는 AI 요청 ID
    @Column(length = 255)
    private String sourceRef;

    @Version
    private Long version;

    public Recipe(
            String title,
            String ingredientsRaw,
            String instructionsRaw,
            SourceType sourceType,
            String sourceRef
    ) {
        this.title = title;
        this.ingredientsRaw = ingredientsRaw;
        this.instructionsRaw = instructionsRaw;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
    }

}


