package io.redis.movies.searcher.data.repository;

import com.redis.om.spring.repository.RedisDocumentRepository;
import io.redis.movies.searcher.data.domain.MovieData;

public interface MovieDataRepository extends RedisDocumentRepository<MovieData, Integer> {
}
