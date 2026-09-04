package com.urisik.backend.domain.search.batch;

import com.urisik.backend.domain.search.entity.PopularKeyword;
import com.urisik.backend.domain.search.enums.RankChange;
import com.urisik.backend.domain.search.repository.PopularKeywordRepository;
import com.urisik.backend.domain.search.repository.SearchLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class PopularKeywordBatch {

    private final SearchLogRepository searchLogRepository;
    private final PopularKeywordRepository popularKeywordRepository;

    @CacheEvict(value = "popularKeywords", allEntries = true)
    @Scheduled(cron = "0 0 */3 * * *") // 3시간마다 실행
    @Transactional
    public void aggregate() {

        LocalDateTime windowEnd = LocalDateTime.now();
        LocalDateTime windowStart = windowEnd.minusHours(24);

        // 이전 순위 저장
        Map<String, Integer> previousRanks =
                popularKeywordRepository.findAll()
                        .stream()
                        .collect(Collectors.toMap(
                                PopularKeyword::getKeyword,
                                PopularKeyword::getRank
                        ));

        // 최근 24시간 Top 8 조회
        List<Object[]> results =
                searchLogRepository.findTopKeywords(
                        windowStart,
                        windowEnd,
                        PageRequest.of(0, 8)
                );

        // 기존 데이터 삭제
        popularKeywordRepository.deleteAllInBatch();

        int rank = 1;

        for (Object[] row : results) {

            String keyword = (String) row[0];
            long count = ((Number) row[1]).longValue();

            RankChange change;

            if (!previousRanks.containsKey(keyword)) {
                change = RankChange.NEW;
            } else {
                int previousRank = previousRanks.get(keyword);
                int diff = previousRank - rank;

                if (diff > 0) change = RankChange.UP;
                else if (diff < 0) change = RankChange.DOWN;
                else change = RankChange.SAME;
            }

            PopularKeyword entity = new PopularKeyword(
                    keyword,
                    count,
                    rank++,
                    change,
                    windowStart,
                    windowEnd
            );

            popularKeywordRepository.save(entity);
        }
    }
}
