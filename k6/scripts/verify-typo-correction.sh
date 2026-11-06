#!/bin/bash
# ES+Nori+자모 오타 보정(fuzziness) 동작 확인 스크립트
# 사전 조건: docker compose -f docker-compose.local.yml up -d (ES가 실행 중이어야 함)
# 실행: bash k6/scripts/verify-typo-correction.sh

ES_URL="${ES_URL:-http://localhost:9200}"
INDEX="recipes"

echo "============================================"
echo "  ES+Nori+자모 오타 보정 검증 스크립트"
echo "============================================"
echo ""

# 0) ES 상태 확인
echo ">> [0] ES 클러스터 상태 확인"
curl -s "${ES_URL}/_cluster/health?pretty"
echo ""

echo ">> [0] Nori 플러그인 확인"
curl -s "${ES_URL}/_cat/plugins?v"
echo ""

echo ">> [0] titleJamo 매핑 확인"
curl -s "${ES_URL}/${INDEX}/_mapping" | python -c "
import sys, json
d = json.load(sys.stdin)
props = d.get('recipes',{}).get('mappings',{}).get('properties',{})
if 'titleJamo' in props:
    print('  titleJamo 필드 존재: OK')
    print('  analyzer:', props['titleJamo'].get('analyzer','(없음)'))
else:
    print('  titleJamo 필드 없음: FAIL — 인덱스 재생성 필요')
" 2>/dev/null
echo ""

# 1) Nori 토큰 분석
echo "============================================"
echo "  1. Nori 토큰 분석 (Analyzer)"
echo "============================================"

for WORD in "김치찌게" "김치찌개" "순두부찌개" "마파두부"; do
  echo ""
  echo ">> 분석 대상: ${WORD}"
  curl -s "${ES_URL}/${INDEX}/_analyze" \
    -H 'Content-Type: application/json' \
    -d "{\"analyzer\":\"nori\",\"text\":\"${WORD}\"}" | python -m json.tool 2>/dev/null || \
  curl -s "${ES_URL}/${INDEX}/_analyze" \
    -H 'Content-Type: application/json' \
    -d "{\"analyzer\":\"nori\",\"text\":\"${WORD}\"}"
  echo ""
done

# 2) 자모 분석기 토큰 분석
echo "============================================"
echo "  2. Jamo 분석기 토큰 분석"
echo "============================================"

for WORD in "ㄱㅣㅁㅊㅣㅉㅣㄱㅐ" "ㄱㅣㅁㅊㅣㅉㅣㄱㅔ"; do
  echo ""
  echo ">> 분석 대상: ${WORD}"
  curl -s "${ES_URL}/${INDEX}/_analyze" \
    -H 'Content-Type: application/json' \
    -d "{\"analyzer\":\"jamo\",\"text\":\"${WORD}\"}" | python -m json.tool 2>/dev/null || \
  curl -s "${ES_URL}/${INDEX}/_analyze" \
    -H 'Content-Type: application/json' \
    -d "{\"analyzer\":\"jamo\",\"text\":\"${WORD}\"}"
  echo ""
done

# 3) Nori-only 오타 검색 (fuzziness 없음)
echo "============================================"
echo "  3. Nori-only 오타 검색 ('김치찌게', fuzziness 없음)"
echo "============================================"
echo ""

RESULT_NORI=$(curl -s "${ES_URL}/${INDEX}/_search" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "multi_match": {
        "query": "김치찌게",
        "fields": ["title^2", "ingredientsRaw"]
      }
    },
    "size": 5
  }')

HITS_NORI=$(echo "${RESULT_NORI}" | python -c "import sys,json; d=json.load(sys.stdin); print(d['hits']['total']['value'])" 2>/dev/null || echo "parse error")
echo "결과 건수 (Nori-only): ${HITS_NORI}"
echo "${RESULT_NORI}" | python -c "
import sys, json
d = json.load(sys.stdin)
for h in d['hits']['hits'][:5]:
    print(f\"  - {h['_source'].get('title','?')} (score: {h['_score']})\")" 2>/dev/null
echo ""

# 4) titleJamo fuzziness AUTO 오타 검색
echo "============================================"
echo "  4. titleJamo fuzziness AUTO 오타 검색 ('김치찌게'→자모)"
echo "============================================"
echo ""

RESULT_JAMO=$(curl -s "${ES_URL}/${INDEX}/_search" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "match": {
        "titleJamo": {
          "query": "ㄱㅣㅁㅊㅣㅉㅣㄱㅔ",
          "fuzziness": "AUTO", "boost": 3
        }
      }
    },
    "size": 5
  }')

HITS_JAMO=$(echo "${RESULT_JAMO}" | python -c "import sys,json; d=json.load(sys.stdin); print(d['hits']['total']['value'])" 2>/dev/null || echo "parse error")
echo "결과 건수 (titleJamo fuzziness AUTO): ${HITS_JAMO}"
echo "${RESULT_JAMO}" | python -c "
import sys, json
d = json.load(sys.stdin)
for h in d['hits']['hits'][:5]:
    print(f\"  - {h['_source'].get('title','?')} (score: {h['_score']})\")" 2>/dev/null
echo ""

# 5) Nori+Jamo 조합 (앱 실제 쿼리 구조)
echo "============================================"
echo "  5. Nori+Jamo bool should (앱 실제 쿼리)"
echo "============================================"
echo ""

RESULT_COMBINED=$(curl -s "${ES_URL}/${INDEX}/_search" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "bool": {
        "should": [
          {
            "multi_match": {
              "query": "김치찌게",
              "fields": ["title^2", "ingredientsRaw"]
            }
          },
          {
            "match": {
              "titleJamo": {
                "query": "ㄱㅣㅁㅊㅣㅉㅣㄱㅔ",
                "fuzziness": "AUTO", "boost": 3
              }
            }
          }
        ]
      }
    },
    "size": 5
  }')

HITS_COMBINED=$(echo "${RESULT_COMBINED}" | python -c "import sys,json; d=json.load(sys.stdin); print(d['hits']['total']['value'])" 2>/dev/null || echo "parse error")
echo "결과 건수 (Nori+Jamo): ${HITS_COMBINED}"
echo "${RESULT_COMBINED}" | python -c "
import sys, json
d = json.load(sys.stdin)
for h in d['hits']['hits'][:5]:
    print(f\"  - {h['_source'].get('title','?')} (score: {h['_score']})\")" 2>/dev/null
echo ""

# 6) 정상 키워드 검색 (비교 기준)
echo "============================================"
echo "  6. 정상 키워드 검색 ('김치찌개') - 비교 기준"
echo "============================================"
echo ""

RESULT_EXACT=$(curl -s "${ES_URL}/${INDEX}/_search" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "bool": {
        "should": [
          {
            "multi_match": {
              "query": "김치찌개",
              "fields": ["title^2", "ingredientsRaw"]
            }
          },
          {
            "match": {
              "titleJamo": {
                "query": "ㄱㅣㅁㅊㅣㅉㅣㄱㅐ",
                "fuzziness": "AUTO", "boost": 3
              }
            }
          }
        ]
      }
    },
    "size": 5
  }')

HITS_EXACT=$(echo "${RESULT_EXACT}" | python -c "import sys,json; d=json.load(sys.stdin); print(d['hits']['total']['value'])" 2>/dev/null || echo "parse error")
echo "결과 건수 (정상 키워드): ${HITS_EXACT}"
echo "${RESULT_EXACT}" | python -c "
import sys, json
d = json.load(sys.stdin)
for h in d['hits']['hits'][:5]:
    print(f\"  - {h['_source'].get('title','?')} (score: {h['_score']})\")" 2>/dev/null
echo ""

# 7) 추가 오타 케이스 테스트
echo "============================================"
echo "  7. 추가 오타 케이스 검증"
echo "============================================"
echo ""

declare -A TYPO_CASES
TYPO_CASES["된쟁찌개"]="ㄷㅚㄴㅈㅐㅇㅉㅣㄱㅐ"
TYPO_CASES["제육복음"]="ㅈㅔㅇㅠㄱㅂㅗㄱㅇㅡㅁ"
TYPO_CASES["돈가스"]="ㄷㅗㄴㄱㅏㅅㅡ"

for TYPO in "${!TYPO_CASES[@]}"; do
  JAMO="${TYPO_CASES[$TYPO]}"
  RESULT=$(curl -s "${ES_URL}/${INDEX}/_search" \
    -H 'Content-Type: application/json' \
    -d "{
      \"query\": {
        \"bool\": {
          \"should\": [
            {\"multi_match\": {\"query\": \"${TYPO}\", \"fields\": [\"title^2\", \"ingredientsRaw\"]}},
            {\"match\": {\"titleJamo\": {\"query\": \"${JAMO}\", \"fuzziness\": \"AUTO\", \"boost\": 3}}}
          ]
        }
      },
      \"size\": 3
    }")
  HITS=$(echo "${RESULT}" | python -c "import sys,json; d=json.load(sys.stdin); print(d['hits']['total']['value'])" 2>/dev/null || echo "parse error")
  TOP1=$(echo "${RESULT}" | python -c "
import sys, json
d = json.load(sys.stdin)
hits = d['hits']['hits']
print(hits[0]['_source']['title'] if hits else '(없음)')" 2>/dev/null || echo "parse error")
  echo "  오타 '${TYPO}' → ${HITS}건, 1위: ${TOP1}"
done
echo ""

# 8) 판정
echo "============================================"
echo "  8. 판정 결과"
echo "============================================"
echo ""
echo "  Nori-only 오타 '김치찌게' 결과:           ${HITS_NORI}건"
echo "  titleJamo fuzziness AUTO 오타 결과:        ${HITS_JAMO}건"
echo "  Nori+Jamo 조합 (앱 실제 쿼리) 결과:       ${HITS_COMBINED}건"
echo "  정상 '김치찌개' 결과:                      ${HITS_EXACT}건"
echo ""
TOP1_COMBINED=$(echo "${RESULT_COMBINED}" | python -c "
import sys, json
d = json.load(sys.stdin)
hits = d['hits']['hits']
print(hits[0]['_source']['title'] if hits else '')" 2>/dev/null || echo "")

if [ "${HITS_JAMO}" != "0" ] && [ "${HITS_JAMO}" != "parse error" ]; then
  echo "  >> 자모 fuzziness AUTO가 오타 보정에 효과가 있습니다!"
  echo "  >> Nori-only(${HITS_NORI}건)과 비교해 titleJamo(${HITS_JAMO}건)가 오타를 보정합니다."
  # 조합 쿼리의 1위 제목에 "김치찌개"가 포함되는지 확인
  if echo "${TOP1_COMBINED}" | grep -q "김치찌개"; then
    echo "  >> 1위 제목 '${TOP1_COMBINED}'에 '김치찌개' 포함: PASS"
  else
    echo "  >> 1위 제목 '${TOP1_COMBINED}'에 '김치찌개' 미포함: WARN — 순위 확인 필요"
  fi
else
  echo "  >> 자모 fuzziness AUTO가 효과 없음 — 설정 확인 필요"
fi
echo ""
