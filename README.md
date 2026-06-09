# 🍽️ URISIK 
### 알레르기 가족 모두가 안전하게, 
### 한 식탁에서 즐겁게 먹을 수 있도록 돕는 맞춤형 식단 관리 서비스 
<img width="1886" height="1128" alt="1" src="https://github.com/user-attachments/assets/4d1c60af-e55a-4979-9121-08a7d64b6aca" />

---

## 📌 Project Overview

URISIK은 가족 구성원의 서로 다른 알레르기를 고려하여  
**레시피별 알레르기 위험을 자동 판별**하고,  
필요 시 **식감과 맛이 유사한 대체 재료로 레시피를 자동 변형/생성**하는  
AI 기반 맞춤형 식단 관리 플랫폼입니다.

---

## 🎯 Problem & Solution 
### ❗ Problem 
- 가족 구성원마다 서로 다른 알레르기로 인해 식단 선택이 복잡함
- 레시피를 고를 때마다 성분을 직접 확인해야 하는 반복 작업
- 알레르기 재료를 제외하면 선택 가능한 메뉴가 급격히 줄어듦
- 대체 가능한 식재료와 조리법에 대한 신뢰 가능한 정보 부족
### ✅ Solution 
- 가족방 단위 알레르기 정보 통합 관리
- 레시피별 알레르기 위험 자동 판별
- 단순 제외가 아닌, 식감·맛이 유사한 대체재 기반 레시피 자동 생성
- 안전성 + 선호도 + 사용자 행동 데이터를 결합한 추천 로직

---

## ✨ Key Features

- 🛡️ **알레르기 자동 판별** (가족 구성원별 알레르기 기반 위험 계산)
- 🔁 **대체 레시피 제공** (대체 재료 + 조리 단계 자동 변형)
- ⭐ **안전 / 별점 / 위시리스트 기반 사용자 맞춤 추천**
- 👨‍👩‍👧 **가족 단위 알레르기 관리** (가족방 단위 프로필/선호 관리)
- 📅 **알레르기 고려 주간 식단 생성**
- ❤️ **위시리스트 기능**
- 📝 **리뷰 및 평점 기반 추천 고도화**

---

## 🚀 Technical Highlights

- 🤖 **AI 기반 레시피 변형 생성 및 식단 생성**
  - 알레르기·중복 제한이 반영된 후보군 기반 생성 파이프라인 설계
  - 알레르기 위험 재료 탐지 → 대체 재료 추천 → 조리 단계 자동 변형 파이프라인 설계
  - 프롬프트 → AI 호출 → 파싱 → 도메인 검증 → fallback으로 이어지는 단계적 책임 분리 로직 설계
    - JSON 스키마 강제 및 도메인 검증기 분리를 통한 안정적인 AI 응답 파싱
    - AI 타임아웃 관리 및 명시적 예외 전파·fallback 로직 설계

- 🧵 **비동기 처리로 UX 개선**
  - 이미지 생성 등 비용 큰 작업을 `@Async`로 분리
  - 사용자 체감 응답 속도 개선

- ☁️ **운영 환경 배포 자동화**
  - Docker + GitHub Actions + Nginx 기반 CI/CD
 
- 📊 **성능 측정 및 병목 분석 기반 개선**
	- k6 기반 p95 기준 성능 측정
	- stage / candidate / assign 로그 분리로 AI generation 병목 구조적 식별
	- 프롬프트·후보군·temperature 최적화를 실험하고 코드 레벨에 반영
 
---

## 📱 Screen Preview

<img width="1886" height="3481" alt="step1" src="https://github.com/user-attachments/assets/5f52beae-048c-4129-81db-ab4381335c25" />
<img width="1886" height="6031" alt="step2" src="https://github.com/user-attachments/assets/ceb3548a-5e0b-4171-96b0-e7e1b12a695e" />
<img width="1886" height="3075" alt="step3" src="https://github.com/user-attachments/assets/48ff594a-d8ac-4254-8657-947cf3f1a4db" />
<img width="1886" height="2408" alt="step4" src="https://github.com/user-attachments/assets/768ee56a-6b99-4dce-a93a-bee4b7176bb8" />

---

## 🛠 Tech Stack

### 🛠 Backend & Framework
<div>
  <img src="https://img.shields.io/badge/Java 17-007396?style=flat-square&logo=java&logoColor=white">
  <img src="https://img.shields.io/badge/Gradle-02303A?style=flat-square&logo=gradle&logoColor=white">
  <img src="https://img.shields.io/badge/Spring Boot-6DB33F?style=flat-square&logo=springboot&logoColor=white">
  <img src="https://img.shields.io/badge/Spring Data JPA-6DB33F?style=flat-square&logo=hibernate&logoColor=white">
  <img src="https://img.shields.io/badge/Spring Security-6DB33F?style=flat-square&logo=springsecurity&logoColor=white">
  <img src="https://img.shields.io/badge/OAuth2-000000?style=flat-square&logo=oauth&logoColor=white">
</div>

### 🗄 Database
<div>
  <img src="https://img.shields.io/badge/MySQL-4479A1?style=flat-square&logo=mysql&logoColor=white">
  <img src="https://img.shields.io/badge/Amazon RDS-527FFF?style=flat-square&logo=amazonrds&logoColor=white">
</div>

### ☁ Cloud & Infrastructure
<div>
  <img src="https://img.shields.io/badge/AWS EC2-FF9900?style=flat-square&logo=amazonec2&logoColor=white">
  <img src="https://img.shields.io/badge/AWS S3-569A31?style=flat-square&logo=amazons3&logoColor=white">
  <img src="https://img.shields.io/badge/AWS Route53-FF9900?style=flat-square&logo=amazonroute53&logoColor=white">
  <img src="https://img.shields.io/badge/Linux-FCC624?style=flat-square&logo=linux&logoColor=black">
</div>

### 🚀 CI/CD & Deployment
<div>
  <img src="https://img.shields.io/badge/GitHub-181717?style=flat-square&logo=github&logoColor=white">
  <img src="https://img.shields.io/badge/GitHub Actions-2088FF?style=flat-square&logo=githubactions&logoColor=white">
  <img src="https://img.shields.io/badge/Docker-2496ED?style=flat-square&logo=docker&logoColor=white">
  <img src="https://img.shields.io/badge/Docker Hub-2496ED?style=flat-square&logo=dockerhub&logoColor=white">
  <img src="https://img.shields.io/badge/Nginx-009639?style=flat-square&logo=nginx&logoColor=white">
</div>

### 🧪 Testing & Performance
<div>
  <img src="https://img.shields.io/badge/Swagger-85EA2D?style=flat-square&logo=swagger&logoColor=black">
  <img src="https://img.shields.io/badge/Postman-FF6C37?style=flat-square&logo=postman&logoColor=white">
  <img src="https://img.shields.io/badge/JUnit5-25A162?style=flat-square&logo=junit5&logoColor=white">
  <img src="https://img.shields.io/badge/k6-7D64FF?style=flat-square&logo=k6&logoColor=white">
</div>

---

## 🏗 Architecture

<img width="1536" height="1024" alt="ChatGPT Image 2026년 2월 12일 오후 08_09_04" src="https://github.com/user-attachments/assets/748d218a-d34b-4b85-90b0-dae1d4d094ec" />



---

## 🗄 ERD

<img width="2100" height="2572" alt="URISIK (9)" src="https://github.com/user-attachments/assets/75dd01f8-ced0-4ffd-84d2-6f943e2367a4" />

---

## 📖 API Documentation

프로젝트의 전체 API 명세는 Notion을 통해 관리하고 있습니다.

### 🔗 Documentation

* Notion API Docs : https://app.notion.com/p/API-2e601bc06a948051a95bce07c8ad3433

---

## 📂 Directory Structure
```text
📦 src/main/java/com/urisik/backend
 ├──📁 domain          # 핵심 비즈니스 로직
 │   ├── 📁 allergy          # 알레르기 관련 패키지
 │   ├── 📁 familyroom       # 가족방 관련 패키지
 │   ├── 📁 mealplan         # 식단 계획 관련 패키지
 │   ├── 📁 member           # 사용자 관련 패키지
 │   ├── 📁 notification     # 알림 관련 패키지
 │   ├── 📁 recipe           # 레시피 관련 패키지
 │   ├── 📁 recommendation   # 추천 관련 패키지
 │   ├── 📁 review           # 리뷰 관련 패키지
 │   └── 📁 search           # 인기 검색어 관련 패키지
 │ 
 ├── 📁 global          # 공통 모듈
 │   ├── 📁 ai               # AI 연동 관련 패키지
 │   ├── 📁 apiPayload       # 공통 응답/에러/예외 처리 관련 패키지
 │   ├── 📁 auth             # 인증/인가 관련 패키지
 │   ├── 📁 config           # 공통 설정 정의 관련 패키지
 │   ├── 📁 external         # AWS S3 연동 관련 패키지
 │   ├── 📁 util             # 공통 유틸리티 관련 패키지
 │   └── 📄 BaseEntity.java  # 생성/수정 시간 자동 관리
 │ 
 └── 📄 UrisikBackendApplication.java

```

---

## Devloper
| 서정춘 | 이원준 | 이은채 | 허건우 |
|:------:|:------:|:------:|:------:|
| <img src="https://github.com/chunny-k.png" alt="서정춘" width="150"> | <img src="https://github.com/wonjun-lee-fcwj245.png" alt="이원준" width="150"> | <img src="https://github.com/euuunchae.png" alt="이은채" width="150"> | <img src="https://github.com/woo6629058.png" alt="허건우" width="150"> |
| BE | BE | BE | BE |
| [GitHub](https://github.com/chunny-k) | [GitHub](https://github.com/wonjun-lee-fcwj245) | [GitHub](https://github.com/euuunchae) | [GitHub](https://github.com/woo6629058) |

---

