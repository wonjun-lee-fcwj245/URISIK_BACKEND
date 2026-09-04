package com.urisik.backend.domain.allergy.entity;

import com.urisik.backend.domain.allergy.enums.Allergen;
import com.urisik.backend.domain.member.entity.FamilyMemberProfile;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_allergy", indexes = {
        @Index(name = "idx_ma_family_member_profile", columnList = "family_member_profile")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberAllergy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_member_profile")
    private FamilyMemberProfile familyMemberProfile;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Allergen allergen;

    public MemberAllergy(FamilyMemberProfile familyMemberProfile, Allergen allergen) {
        this.familyMemberProfile = familyMemberProfile;
        this.allergen = allergen;
    }

    public static MemberAllergy of(Allergen allergen) {
        MemberAllergy a = new MemberAllergy();
        a.allergen = allergen;
        return a;
    }

    public void setFamilyMemberProfile(FamilyMemberProfile profile) {
        this.familyMemberProfile = profile;
    }

}
