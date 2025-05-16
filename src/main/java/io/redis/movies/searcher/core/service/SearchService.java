package io.redis.movies.searcher.core.service;

import ai.djl.util.Pair;
import com.redis.om.spring.search.stream.EntityStream;
import io.redis.movies.searcher.core.domain.*;
import io.redis.movies.searcher.core.dto.MovieDTO;
import io.redis.movies.searcher.core.repository.KeywordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);

    private final EntityStream entityStream;
    private final KeywordRepository keywordRepository;

    public SearchService(EntityStream entityStream, KeywordRepository keywordRepository) {
        this.entityStream = entityStream;
        this.keywordRepository = keywordRepository;
    }

    public Pair<List<MovieDTO>, ResultType> searchMovies(String query, Integer limit) {
        logger.info("Received query: {}", query);
        logger.info("-------------------------");
        final int resultLimit = (limit == null) ? 3 : limit;

        // Execute FTS search
        var ftsSearchStartTime = System.currentTimeMillis();
        List<Movie> ftsMovies = entityStream.of(Movie.class)
                .filter(
                        Movie$.TITLE.eq(query).or(Movie$.TITLE.containing(query)).or(
                                ((Predicate<? super String>) Movie$.ACTORS.containsAll(query)))

                )
                .limit(resultLimit)
                .sorted(Comparator.comparing(Movie::getTitle))
                .collect(Collectors.toList());

        var ftsSearchEndTime = System.currentTimeMillis();
        logger.info("FTS search took {} ms", ftsSearchEndTime - ftsSearchStartTime);

        // Convert FTS results to DTOs
        List<MovieDTO> ftsMovieDTOs = convertToDTOs(ftsMovies);

        // If FTS results are sufficient, return them immediately
        if (ftsMovies.size() >= resultLimit) {
            return new Pair<>(ftsMovieDTOs, ResultType.FTS);
        }

        // Create the embedding
        var embeddingStartTime = System.currentTimeMillis();
        byte[] embeddedQuery = getQueryEmbeddingAsByteArray(query);
        var embeddingEndTime = System.currentTimeMillis();
        logger.info("Embedding took {} ms", embeddingEndTime - embeddingStartTime);

        // Execute VSS search
        var vssSearchStartTime = System.currentTimeMillis();
        List<Movie> vssMovies = entityStream.of(Movie.class)
                .filter(Movie$.PLOT_EMBEDDING.knn(resultLimit, embeddedQuery))
                .limit(resultLimit)
                .sorted(Movie$._PLOT_EMBEDDING_SCORE)
                .collect(Collectors.toList());
        var vssSearchEndTime = System.currentTimeMillis();
        logger.info("VSS search took {} ms", vssSearchEndTime - vssSearchStartTime);

        // Combine results
        LinkedHashMap<Integer, Movie> uniqueMoviesMap = new LinkedHashMap<>();
        ftsMovies.forEach(movie -> uniqueMoviesMap.put(movie.getId(), movie));
        vssMovies.forEach(movie -> uniqueMoviesMap.putIfAbsent(movie.getId(), movie));

        // Limit and convert combined results to DTOs
        List<Movie> uniqueMovies = uniqueMoviesMap.values().stream()
                .limit(resultLimit)
                .collect(Collectors.toList());

        return new Pair<>(convertToDTOs(uniqueMovies), ftsMovies.isEmpty() ? ResultType.VSS : ResultType.HYBRID);
    }

    private byte[] getQueryEmbeddingAsByteArray(String query) {
        return entityStream.of(Keyword.class)
                .filter(Keyword$.VALUE.containing(query))
                .findFirst()
                .map(Keyword::getEmbedding)
                .orElseGet(() -> keywordRepository.save(new Keyword(query)).getEmbedding());
    }

    private List<MovieDTO> convertToDTOs(List<Movie> movies) {
        return movies.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    private MovieDTO convertToDTO(Movie movie) {
        return new MovieDTO(
                movie.getTitle(),
                movie.getYear(),
                movie.getPlot(),
                movie.getRating(),
                movie.getActors().toArray(new String[0])
        );
    }

}
