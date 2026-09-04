package com.urisik.backend.domain.search.service;

import com.urisik.backend.domain.search.converter.PopularKeywordConverter;
import com.urisik.backend.domain.search.dto.PopularKeywordResponse;
import com.urisik.backend.domain.search.entity.PopularKeyword;
import com.urisik.backend.domain.search.repository.PopularKeywordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PopularKeywordService {

    private final PopularKeywordRepository popularKeywordRepository;

    @Cacheable(value = "popularKeywords", key = "'all'")
    public PopularKeywordResponse getPopularKeywords() {

        List<PopularKeyword> list =
                popularKeywordRepository.findAllByOrderByRankAsc();

        return PopularKeywordConverter.toResponse(list);
    }
}

