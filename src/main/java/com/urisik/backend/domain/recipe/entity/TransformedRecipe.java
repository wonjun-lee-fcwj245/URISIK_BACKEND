package com.urisik.backend.domain.recipe.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "transformed_recipe",
        indexes = {
                @Index(name = "idx_tr_base_recipe", columnList = "base_recipe_id"),
                @Index(name = "idx_tr_family_room", columnList = "family_room_id"),
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@org.hibernate.annotations.DynamicUpdate
public class TransformedRecipe {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 원본 레시피 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "base_recipe_id", nullable = false)
    private Recipe baseRecipe;

    /** 가족방 기준 */
    @Column(name = "family_room_id", nullable = false)
    private Long familyRoomId;

    /** AI가 생성한 제목 */
    @Column(nullable = false)
    private String title;

    /** 알레르기 안전성 재검증 결과 */
    @Column(nullable = false)
    private boolean validationStatus = false;

    /** 리뷰 / 평점 / 위시리스트 */
    @Column(nullable = false)
    private int reviewCount = 0;

    @Column(nullable = false)
    private double avgScore = 0.0;

    // 점수 합계. 평균을 매번 반올림해 다시 계산에 쓰면 오차가 쌓이므로 합계를 저장하고 평균은 합계/개수로 계산한다
    @Column(name = "score_sum", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long scoreSum = 0L;

    @Column(nullable = false)
    private int wishCount = 0;

    /** 변형된 재료 / 조리법 */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String ingredientsRaw;

    @Lob
    @Column(columnDefinition = "MEDIUMTEXT", nullable = false)
    private String instructionsRaw;

    /** 알레르기 대체 요약(JSON) */
    @Lob
    @Column(columnDefinition = "MEDIUMTEXT")
    private String substitutionSummaryJson;

    @Column(name = "image_url")
    private String imageUrl;

    @Version
    private Long version;

    /* =========================
       생성자
       ========================= */
    public TransformedRecipe(
            Recipe baseRecipe,
            Long familyRoomId,
            String title,
            String ingredientsRaw,
            String instructionsRaw,
            String substitutionSummaryJson,
            String imageUrl
    ) {
        this.baseRecipe = baseRecipe;
        this.familyRoomId = familyRoomId;
        this.title = title;
        this.ingredientsRaw = ingredientsRaw;
        this.instructionsRaw = instructionsRaw;
        this.substitutionSummaryJson = substitutionSummaryJson;
        this.imageUrl = imageUrl;
    }


    public void updateValidationStatus(boolean status) {
        this.validationStatus = status;
    }
}


