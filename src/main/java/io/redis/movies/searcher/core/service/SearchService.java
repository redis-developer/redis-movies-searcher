package io.redis.movies.searcher.core.service;

import org.springframework.data.util.Pair;
import com.redis.om.spring.search.stream.EntityStream;
import io.redis.movies.searcher.core.domain.*;
import io.redis.movies.searcher.core.dto.MovieDTO;
import io.redis.movies.searcher.core.repository.KeywordRepository;
import io.redis.movies.searcher.core.repository.MovieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchService {

    private static final Logger logger = LoggerFactory.getLogger(SearchService.class);
    private static final String INDEX_NAME = "movie_index";

    private final EntityStream entityStream;
    private final KeywordRepository keywordRepository;
    private final MovieRepository movieRepository;
    private final StringRedisTemplate redisTemplate;

    public SearchService(EntityStream entityStream, KeywordRepository keywordRepository,
                         MovieRepository movieRepository, StringRedisTemplate redisTemplate) {
        this.entityStream = entityStream;
        this.keywordRepository = keywordRepository;
        this.movieRepository = movieRepository;
        this.redisTemplate = redisTemplate;
    }

    public Pair<List<MovieDTO>, ResultType> manualHybridSearch(String query, Integer limit) {
        logger.info("Received query: {}", query);
        logger.info("-------------------------");
        final int resultLimit = (limit == null) ? DEFAULT_RESULT_LIMIT : limit;

        // Execute FTS search
        var ftsSearchStartTime = System.currentTimeMillis();
        List<Movie> ftsMovies = entityStream.of(Movie.class)
                .filter(Movie$.TITLE.eq(query).or(Movie$.TITLE.containing(query)))
                .limit(resultLimit)
                .sorted(Comparator.comparing(Movie::getTitle))
                .collect(Collectors.toList());

        var ftsSearchEndTime = System.currentTimeMillis();
        logger.info("FTS search took {} ms", ftsSearchEndTime - ftsSearchStartTime);

        // Convert FTS results to DTOs
        List<MovieDTO> ftsMovieDTOs = convertToDTOs(ftsMovies);

        // If FTS results are sufficient, return them immediately
        if (ftsMovies.size() >= resultLimit) {
            return Pair.of(ftsMovieDTOs, ResultType.FTS);
        }

        // Create the embedding query
        var embeddingStartTime = System.currentTimeMillis();
        byte[] queryAsVector = getQueryAsVector(query);
        var embeddingEndTime = System.currentTimeMillis();
        logger.info("Embedding took {} ms", embeddingEndTime - embeddingStartTime);

        // Execute VSS search
        var vssSearchStartTime = System.currentTimeMillis();
        List<Movie> vssMovies = entityStream.of(Movie.class)
                .filter(Movie$.PLOT_EMBEDDING.knn(resultLimit, queryAsVector))
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

        return Pair.of(convertToDTOs(uniqueMovies), ftsMovies.isEmpty() ? ResultType.VSS : ResultType.HYBRID);
    }

    public Pair<List<MovieDTO>, ResultType> nativeHybridSearch(String query, Integer limit) {
        logger.info("Received query: {}", query);
        logger.info("-------------------------");
        final int resultLimit = (limit == null) ? DEFAULT_RESULT_LIMIT : limit;

        // Create the embedding for the query
        var embeddingStartTime = System.currentTimeMillis();
        byte[] queryAsVector = getQueryAsVector(query);
        float[] vectorAsFloat = bytesToFloats(queryAsVector);
        var embeddingEndTime = System.currentTimeMillis();
        logger.info("Embedding took {} ms", embeddingEndTime - embeddingStartTime);

        var hybridSearchStartTime = System.currentTimeMillis();
        List<Movie> movies = entityStream.of(Movie.class)
                .hybridSearch(
                        query,                    // text query
                        Movie$.TITLE,             // text field to search
                        vectorAsFloat,            // query embedding as float[]
                        Movie$.PLOT_EMBEDDING,    // vector field to search
                        0.0f                      // alpha: 30% vector, 70% text
                )
                .limit(resultLimit)
                .collect(Collectors.toList());
        ResultType resultType = ResultType.HYBRID;
        var hybridSearchEndTime = System.currentTimeMillis();

        logger.info("Hybrid search took {} ms", hybridSearchEndTime - hybridSearchStartTime);
        logger.info("Found {} movies", movies.size());

        return Pair.of(convertToDTOs(movies), resultType);
    }

    public Pair<List<MovieDTO>, ResultType> rawHybridSearch(String query, Integer limit) {
        logger.info("[RAW] Received query: {}", query);
        logger.info("[RAW] -------------------------");
        final int resultLimit = (limit == null) ? DEFAULT_RESULT_LIMIT : limit;

        // Create the embedding for the query
        var embeddingStartTime = System.currentTimeMillis();
        byte[] queryAsVector = getQueryAsVector(query);
        var embeddingEndTime = System.currentTimeMillis();
        logger.info("[RAW] Embedding took {} ms", embeddingEndTime - embeddingStartTime);

        var hybridSearchStartTime = System.currentTimeMillis();

        // Build the FT.HYBRID command
        // FT.HYBRID movie_index
        //   SEARCH "query"
        //   VSIM plotEmbedding $BLOB KNN 2 K <limit>
        //   COMBINE LINEAR 2 ALPHA 0.0 BETA 1.0
        //   PARAMS 2 BLOB <vector_bytes>

        // Alpha=0.0 means 100% text weight (matching nativeHybridSearch with alpha=0.0f)
        String searchQuery = escapeQuery(query);

        logger.info("[RAW] Executing FT.HYBRID {} SEARCH \"{}\" VSIM @plotEmbedding $BLOB KNN 2 K {} COMBINE LINEAR {} ALPHA 0.0 BETA 1.0 LIMIT 0 {} PARAMS 2 BLOB <{} bytes>",
                INDEX_NAME, searchQuery, resultLimit, resultLimit, resultLimit, queryAsVector.length);

        // Build the command arguments for FT.HYBRID
        byte[][] args = new byte[][] {
            INDEX_NAME.getBytes(StandardCharsets.UTF_8),
            "SEARCH".getBytes(StandardCharsets.UTF_8),
            searchQuery.getBytes(StandardCharsets.UTF_8),
            "VSIM".getBytes(StandardCharsets.UTF_8),
            "@plotEmbedding".getBytes(StandardCharsets.UTF_8),
            "$BLOB".getBytes(StandardCharsets.UTF_8),
            "KNN".getBytes(StandardCharsets.UTF_8),
            "2".getBytes(StandardCharsets.UTF_8),  // count of KNN args
            "K".getBytes(StandardCharsets.UTF_8),
            String.valueOf(resultLimit).getBytes(StandardCharsets.UTF_8),
            "COMBINE".getBytes(StandardCharsets.UTF_8),
            "LINEAR".getBytes(StandardCharsets.UTF_8),
            String.valueOf(resultLimit).getBytes(StandardCharsets.UTF_8),  // count for LINEAR
            "ALPHA".getBytes(StandardCharsets.UTF_8),
            "0.0".getBytes(StandardCharsets.UTF_8),  // 0% vector weight
            "BETA".getBytes(StandardCharsets.UTF_8),
            "1.0".getBytes(StandardCharsets.UTF_8),  // 100% text weight
            "LIMIT".getBytes(StandardCharsets.UTF_8),
            "0".getBytes(StandardCharsets.UTF_8),  // offset
            String.valueOf(resultLimit).getBytes(StandardCharsets.UTF_8),  // num results
            "PARAMS".getBytes(StandardCharsets.UTF_8),
            "2".getBytes(StandardCharsets.UTF_8),
            "BLOB".getBytes(StandardCharsets.UTF_8),
            queryAsVector
        };

        Object rawResult = redisTemplate.execute((RedisConnection connection) ->
            connection.execute("FT.HYBRID", args)
        );

        @SuppressWarnings("unchecked")
        List<Object> results = (rawResult instanceof List) ? (List<Object>) rawResult : null;

        var hybridSearchEndTime = System.currentTimeMillis();
        logger.info("[RAW] Hybrid search took {} ms", hybridSearchEndTime - hybridSearchStartTime);

        // Parse the results
        List<MovieDTO> movieDTOs = parseRawHybridResults(results);
        logger.info("[RAW] Found {} movies", movieDTOs.size());

        return Pair.of(movieDTOs, ResultType.HYBRID);
    }

    /**
     * Escapes special characters in the query for RediSearch.
     */
    private String escapeQuery(String query) {
        // Escape special RediSearch characters: ,.<>{}[]"':;!@#$%^&*()-+=~
        return query.replaceAll("([,.<>{}\\[\\]\"':;!@#$%^&*()\\-+=~\\\\])", "\\\\$1")
                    .replaceAll("\\s+", "\\ ");  // Escape spaces
    }

    /**
     * Parses the raw FT.HYBRID results and converts them to MovieDTOs.
     *
     * FT.HYBRID RESP3 response format (Map):
     * - total_results: Integer
     * - results: Array of maps containing document information (id, extra_attributes, etc.)
     * - execution_time: double
     * - warnings: Array
     *
     * The response comes as a flat list: [key1, value1, key2, value2, ...]
     */
    @SuppressWarnings("unchecked")
    private List<MovieDTO> parseRawHybridResults(List<Object> results) {
        List<MovieDTO> movies = new ArrayList<>();

        if (results == null || results.isEmpty()) {
            logger.warn("[RAW] No results returned from FT.HYBRID");
            return movies;
        }

        logger.info("[RAW] Raw result type: {}", results.getClass().getName());
        logger.info("[RAW] Raw results size: {}", results.size());

        // Parse the flat map response: [key1, value1, key2, value2, ...]
        // Look for "results" key which contains the actual documents
        for (int i = 0; i < results.size(); i += 2) {
            if (i + 1 >= results.size()) break;

            String key = extractString(results.get(i));
            Object value = results.get(i + 1);

            logger.info("[RAW] Key: {}, Value type: {}", key, value != null ? value.getClass().getName() : "null");

            if ("total_results".equals(key)) {
                long totalCount = extractLong(value);
                logger.info("[RAW] Total matching documents: {}", totalCount);
            } else if ("results".equals(key) && value instanceof List) {
                List<Object> resultsList = (List<Object>) value;
                logger.info("[RAW] Results array size: {}", resultsList.size());

                // Each result is a map with: id, extra_attributes, etc.
                for (int j = 0; j < resultsList.size(); j++) {
                    Object resultObj = resultsList.get(j);
                    logger.info("[RAW] Result[{}] type: {}", j, resultObj != null ? resultObj.getClass().getName() : "null");

                    String docId = null;
                    if (resultObj instanceof List) {
                        List<Object> resultMap = (List<Object>) resultObj;
                        logger.info("[RAW] Result[{}] is List with size: {}", j, resultMap.size());
                        // Log each element in the result list
                        for (int k = 0; k < resultMap.size(); k++) {
                            Object elem = resultMap.get(k);
                            String elemStr = extractString(elem);
                            logger.info("[RAW] Result[{}][{}] = {} (type: {})", j, k, elemStr,
                                elem != null ? elem.getClass().getSimpleName() : "null");
                        }
                        docId = extractDocumentId(resultMap);
                    } else if (resultObj instanceof Map) {
                        Map<Object, Object> resultMap = (Map<Object, Object>) resultObj;
                        logger.info("[RAW] Result[{}] is Map with keys: {}", j, resultMap.keySet());
                        Object idObj = resultMap.get("id");
                        if (idObj == null) {
                            idObj = resultMap.get("id".getBytes(StandardCharsets.UTF_8));
                        }
                        docId = extractString(idObj);
                    }

                    if (docId != null) {
                        logger.info("[RAW] Found document: {}", docId);
                        String idStr = docId.replace("movie:", "");
                        try {
                            int movieId = Integer.parseInt(idStr);
                            movieRepository.findById(movieId).ifPresent(movie ->
                                movies.add(convertToDTO(movie))
                            );
                        } catch (NumberFormatException e) {
                            logger.warn("[RAW] Could not parse movie ID from key: {}", docId);
                        }
                    } else {
                        logger.warn("[RAW] Could not extract document ID from result[{}]", j);
                    }
                }
            }
        }

        return movies;
    }

    /**
     * Extracts a string from various response types (byte[], String).
     */
    private String extractString(Object obj) {
        if (obj instanceof byte[]) {
            return new String((byte[]) obj, StandardCharsets.UTF_8);
        } else if (obj instanceof String) {
            return (String) obj;
        }
        return obj != null ? obj.toString() : null;
    }

    /**
     * Extracts a long value from various response types.
     */
    private long extractLong(Object obj) {
        if (obj instanceof Long) {
            return (Long) obj;
        } else if (obj instanceof Integer) {
            return ((Integer) obj).longValue();
        } else if (obj instanceof byte[]) {
            return Long.parseLong(new String((byte[]) obj, StandardCharsets.UTF_8));
        } else if (obj instanceof String) {
            return Long.parseLong((String) obj);
        }
        return 0;
    }

    /**
     * Extracts the document ID from a result map (flat list format).
     */
    private String extractDocumentId(List<Object> resultMap) {
        for (int i = 0; i < resultMap.size(); i += 2) {
            if (i + 1 >= resultMap.size()) break;
            String key = extractString(resultMap.get(i));
            // FT.HYBRID uses "__key" instead of "id" for the document key
            if ("id".equals(key) || "__key".equals(key)) {
                return extractString(resultMap.get(i + 1));
            }
        }
        return null;
    }

    private float[] bytesToFloats(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] floats = new float[bytes.length / 4];
        for (int i = 0; i < floats.length; i++) {
            floats[i] = buffer.getFloat();
        }
        return floats;
    }

    private byte[] getQueryAsVector(String query) {
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

    private static final Integer DEFAULT_RESULT_LIMIT = 4;
}
