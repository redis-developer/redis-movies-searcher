package io.redis.movies.searcher.core.repository;

import com.redis.om.spring.repository.RedisEnhancedRepository;
import io.redis.movies.searcher.core.domain.Keyword;

public interface KeywordRepository extends RedisEnhancedRepository<Keyword, String> {
}
