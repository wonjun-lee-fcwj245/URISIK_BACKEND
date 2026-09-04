package com.urisik.backend.domain.member.entity;
import com.urisik.backend.domain.recipe.entity.Recipe;
import com.urisik.backend.global.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity
@Table(name = "personal_wishList", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"family_member_profile_id", "recipe_id"})
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberWishList extends BaseEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_member_profile_id", nullable = false)
    private FamilyMemberProfile familyMemberProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipe_id", nullable = false)
    private Recipe recipe;



    public static MemberWishList of(Recipe recipe) {
        MemberWishList w = new MemberWishList();
        w.recipe = recipe;
        return w;
    }

    public void setFamilyMemberProfile(FamilyMemberProfile profile) {
        this.familyMemberProfile = profile;
    }
}


