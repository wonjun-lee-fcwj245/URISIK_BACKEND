package com.urisik.backend.domain.recipe.repository;

import com.urisik.backend.domain.recipe.document.RecipeDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

public interface RecipeDocumentRepository extends ElasticsearchRepository<RecipeDocument, String> {
}
