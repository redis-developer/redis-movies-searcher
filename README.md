# Building Context-Aware Applications with Java, Vector Searches, and Redis

Welcome to JNation 2025 👋🏻

![jnation.png](images/jnation.png)

On behalf of the [JNation](https://jnation.pt) organizers, it's my genuine pleasure to be your instructor today. My name is [Ricardo Ferreira](https://github.com/riferrei), and I lead the developer relations team at [Redis](https://redis.io).

In this workshop, you will learn how to design and develop an application that makes use of vector databases to handle semantic search. You will learn how to prepare the dataset for storage, how to process JSON documents and generate embeddings, how to index them into Redis, and how to implement searches and aggregations on top of it. You must be comfortable with [Java](https://www.java.com/en) and have a basic understanding of [Redis](https://redis.io/open-source).

## Part 1: Getting everything ready

The goal of this section is to get the moving parts from this project ready to be used. You will take care of the steps necessary to get the application running. This means you will make sure the application dependencies are ready, build the code from scratch, and make sure the dataset the application will handle is in a good shape.

### Task 1: Installing dependencies

#### ⏰ Estimated time: **15 minutes**

This workshop requires the following dependencies to be installed on your machine:

- [JDK 21+](https://docs.aws.amazon.com/corretto/latest/corretto-21-ug/downloads-list.html)
- [Maven 3.9+](https://maven.apache.org/download.cgi)
- [Docker](https://www.docker.com/get-started)
- [Redis Insight](https://redis.io/docs/latest/operate/redisinsight/install)
- [RIOT](https://redis.github.io/riot/#_install)

It is recommended to use IDEs like [VS Code](https://code.visualstudio.com), [IntelliJ IDEA](https://www.jetbrains.com/idea/) or [Eclipse](https://www.eclipse.org/downloads/packages/) to work with the code. As a Java developer, you can use any IDE you feel comfortable with. Please make sure to have one installed before proceeding.

### Task 2: Building and running the application

#### ⏰ Estimated time: **20 minutes**

```bash
git clone https://github.com/redis-developer/redis-movies-searcher.git -b jnation-2025-workshop
```

```bash
mvn clean package
```

```bash
docker compose up -d
```

```bash
mvn spring-boot:run
```

http://localhost:8080/redis-movies-searcher

![app-mvp.png](images/app-mvp.png)

![ri-start.png](images/ri-start.png)

### Task 3: Importing the dataset into Redis

#### ⏰ Estimated time: **5 minutes**

```bash
cd ${project_dir}/data
```

```bash
riot file-import \
    --var counter="new java.lang.Integer(1)" \
    --proc id="#counter++" \
    --proc plot="info.plot" \
    --proc releaseDate="info.release_date" \
    --proc rating="info.rating" \
    --proc actors="remove('info').actors" \
    movies.json json.set --keyspace import:movie --key id
```

![ri-ds-loaded.png](images/ri-ds-loaded.png)

### Task 4: Finding and removing duplicated movies

#### ⏰ Estimated time: **10 minutes**

```bash
FT.CREATE imported_movies_index ON JSON PREFIX 1 "import:movie:" SCHEMA
  $.title AS title TEXT WEIGHT 1
  $.year AS year NUMERIC SORTABLE UNF
  $.plot AS plot TEXT WEIGHT 1
  $.releaseDate AS releaseDate TAG SEPARATOR "|"
  $.rating AS rating NUMERIC SORTABLE UNF
  $.actors[*] AS actors TAG SEPARATOR "|"
  $.id AS id NUMERIC SORTABLE UNF
```

![ri-create-idx.png](images/ri-create-idx.png)

```bash
FT.INFO 'imported_movies_index'
```

![ri-inspect-idx.png](images/ri-inspect-idx.png)

![ri-search-matrix.png](images/ri-search-matrix.png)

![ri-dupl-movies.png](images/ri-dupl-movies.png)

```bash
EVAL "local result = redis.call('FT.AGGREGATE', 'imported_movies_index', '*', 'LOAD', '2', '@title', '@id', 'GROUPBY', '1', '@title', 'REDUCE', 'COUNT', '0', 'AS', 'count', 'REDUCE', 'TOLIST', '1', '@id', 'AS', 'ids', 'FILTER', '@count > 1') local deletion_count = 0 local index = 2 while index <= #result do local group = result[index] local title_idx, ids_idx = nil, nil for i = 1, #group, 2 do if group[i] == 'title' then title_idx = i elseif group[i] == 'ids' then ids_idx = i end end if ids_idx then local ids_list = group[ids_idx + 1] for i = 2, #ids_list do local key_name = 'import:movie:' .. ids_list[i] redis.call('DEL', key_name) deletion_count = deletion_count + 1 end end index = index + 1 end return 'Deleted ' .. deletion_count .. ' duplicate movies'" 0
```

![ri-remove-dupl.png](images/ri-remove-dupl.png)

### Task 5: Fixing data glitches in the dataset

#### ⏰ Estimated time: **10 minutes**

```bash
FT.SEARCH imported_movies_index "@title:\"Justice League\""
```

```bash
JSON.SET import:movie:1321 $.actors '["Ben Affleck", "Gal Gadot", "Jason Momoa", "Henry Cavill", "Ezra Miller"]'
```

## Part 2: Preparing the dataset for searches

In this section, you will implement a functionality that will prepare the dataset for searches. You will write code in Java to process the existing dataset in such a way that creates the building blocks required for the searches, such as schemas, indexes, and embeddings.

### Task 1: Persistence layer for the existing data

#### ⏰ Estimated time: **10 minutes**

```java
package io.redis.movies.searcher.data.domain;

import com.redis.om.spring.annotations.Document;
import com.redis.om.spring.annotations.Indexed;
import com.redis.om.spring.annotations.Searchable;
import org.springframework.data.annotation.Id;

import java.util.List;

@Document(value = "import:movie", indexName = "imported_movies_index")
public class MovieData {

    @Id
    private int id;

    @Searchable
    private String title;

    @Indexed(sortable = true)
    private int year;

    @Searchable
    private String plot;

    @Indexed
    private String releaseDate;

    @Indexed(sortable = true)
    private double rating;

    @Indexed
    private List<String> actors;
    
    // Create getters and setters for all fields

}
```

```java
package io.redis.movies.searcher.data.repository;

import com.redis.om.spring.repository.RedisDocumentRepository;
import io.redis.movies.searcher.data.domain.MovieData;

public interface MovieDataRepository extends RedisDocumentRepository<MovieData, Integer> {
}
```

### Task 2: Persistence layer for the generated data

#### ⏰ Estimated time: **10 minutes**

```java
package io.redis.movies.searcher.core.domain;

import com.redis.om.spring.annotations.*;
import com.redis.om.spring.indexing.DistanceMetric;
import com.redis.om.spring.indexing.VectorType;
import io.redis.movies.searcher.data.domain.MovieData;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import redis.clients.jedis.search.schemafields.VectorField;

import java.util.List;

@RedisHash("movie")
public class Movie {

    @Id
    private int id;

    @Searchable
    private String title;

    @Indexed(sortable = true)
    private int year;

    @Vectorize(
            destination = "plotEmbedding",
            embeddingType = EmbeddingType.SENTENCE
    )
    private String plot;

    @Indexed(
            schemaFieldType = SchemaFieldType.VECTOR,
            algorithm = VectorField.VectorAlgorithm.FLAT,
            type = VectorType.FLOAT32,
            dimension = 384,
            distanceMetric = DistanceMetric.COSINE,
            initialCapacity = 10
    )
    private byte[] plotEmbedding;

    @Indexed
    private String releaseDate;

    @Indexed(sortable = true)
    private double rating;

    @Indexed
    private List<String> actors;

    public Movie() {}

    public Movie(int id, String title, int year, String plot,
                 String releaseDate, double rating, List<String> actors) {
        this.id = id;
        this.title = title;
        this.year = year;
        this.plot = plot;
        this.releaseDate = releaseDate;
        this.rating = rating;
        this.actors = actors;
    }

    public static Movie fromData(MovieData movieAsJSON) {
        return new Movie(movieAsJSON.getId(), movieAsJSON.getTitle(),
                movieAsJSON.getYear(), movieAsJSON.getPlot(),
                movieAsJSON.getReleaseDate(), movieAsJSON.getRating(),
                movieAsJSON.getActors());
    }

    // Create getters and setters for all fields

}
```

```java
package io.redis.movies.searcher.core.repository;

import com.redis.om.spring.repository.RedisEnhancedRepository;
import io.redis.movies.searcher.core.domain.Movie;

public interface MovieRepository extends RedisEnhancedRepository<Movie, Integer> {
}
```

```java
package io.redis.movies.searcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import com.redis.om.spring.annotations.EnableRedisDocumentRepositories;
import com.redis.om.spring.annotations.EnableRedisEnhancedRepositories;

@SpringBootApplication
@EnableRedisDocumentRepositories(basePackages = {"io.redis.movies.searcher.data*"})
@EnableRedisEnhancedRepositories(basePackages = {"io.redis.movies.searcher.core*"})
public class RedisMoviesSearcher {

    private static final Logger log = LoggerFactory.getLogger(RedisMoviesSearcher.class);

    public static void main(String[] args) {
        SpringApplication.run(RedisMoviesSearcher.class, args);
    }

}
```

### Task 3: Implementing the dataset processing

#### ⏰ Estimated time: **40 minutes**

```java
package io.redis.movies.searcher.core.service;

import io.redis.movies.searcher.core.domain.Movie;
import io.redis.movies.searcher.core.repository.MovieRepository;
import io.redis.movies.searcher.data.repository.MovieDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class MovieService {

    private static final Logger log = LoggerFactory.getLogger(MovieService.class);

    private final MovieRepository movieRepository;
    private final MovieDataRepository movieDataRepository;
    private final RedisTemplate redisTemplate;

    public MovieService(MovieRepository movieRepository,
                        MovieDataRepository movieDataRepository, RedisTemplate redisTemplate) {
        this.movieRepository = movieRepository;
        this.movieDataRepository = movieDataRepository;
        this.redisTemplate = redisTemplate;
    }

    public void importMovies() {
        log.info("Starting processing the movies available at Redis...");

        // Collect all the keys to be imported...
        Set<String> allMovieKeys = new HashSet<>();
        try (Cursor<byte[]> cursor = redisTemplate.getConnectionFactory().getConnection()
                .scan(ScanOptions.scanOptions().match("import:movie:*").count(1000).build())) {
            while (cursor.hasNext()) {
                allMovieKeys.add(new String(cursor.next(), StandardCharsets.UTF_8));
            }
        }

        log.info("Found {} records with the key prefix 'import:movie'", allMovieKeys.size());
        if (allMovieKeys.isEmpty()) {
            return;
        }

        var startTime = Instant.now();
        // First phase: Load all movie data in parallel (no API calls here)
        List<Movie> movies = allMovieKeys.parallelStream()
                .map(key -> {
                    try {
                        Integer id = Integer.parseInt(key.split(":")[2]);
                        var movieData = movieDataRepository.findById(id).orElse(null);
                        if (movieData != null && !movieRepository.existsById(movieData.getId())) {
                            return Movie.fromData(movieData);
                        }
                    } catch (Exception e) {
                        log.warn("Error processing key {}: {}", key, e.getMessage());
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // Second phase: Save with optimized concurrency
        if (!movies.isEmpty()) {
            log.info("Loaded {} records into memory. Saving them all...", movies.size());
            int processors = Runtime.getRuntime().availableProcessors();
            ExecutorService executor = Executors.newFixedThreadPool(processors * 2);

            final int batchSize = 500;
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            AtomicInteger savedCounter = new AtomicInteger(0);

            for (int i = 0; i < movies.size(); i += batchSize) {
                int end = Math.min(i + batchSize, movies.size());
                List<Movie> batch = movies.subList(i, end);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        movieRepository.saveAll(batch);
                        int totalSaved = savedCounter.addAndGet(batch.size());
                        if (totalSaved % 500 == 0 || totalSaved == movies.size()) {
                            double percentComplete = (totalSaved * 100.0) / movies.size();
                            log.info("Saved {}/{} movies ({}%)",
                                    totalSaved, movies.size(),
                                    String.format("%.1f", percentComplete));
                        }
                    } catch (Exception ex) {
                        log.error("Error saving batch: {}", ex.getMessage(), ex);
                    }
                }, executor);

                futures.add(future);
            }

            // Wait for all futures to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // Shutdown the executor
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        var duration = Duration.between(startTime, Instant.now());
        double seconds = duration.toMillis() / 1000.0;
        log.info("Processing complete: {} source keys loaded, saved {} records in {} seconds",
                allMovieKeys.size(),
                movies.size(),
                String.format("%.2f", seconds));
    }

    public boolean isDataLoaded() {
        return movieRepository.count() > 1;
    }
}
```

```java
package io.redis.movies.searcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.redis.movies.searcher.core.service.MovieService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import com.redis.om.spring.annotations.EnableRedisDocumentRepositories;
import com.redis.om.spring.annotations.EnableRedisEnhancedRepositories;

@SpringBootApplication
@EnableRedisDocumentRepositories(basePackages = {"io.redis.movies.searcher.data*"})
@EnableRedisEnhancedRepositories(basePackages = {"io.redis.movies.searcher.core*"})
public class RedisMoviesSearcher {

    private static final Logger log = LoggerFactory.getLogger(RedisMoviesSearcher.class);

    public static void main(String[] args) {
        SpringApplication.run(RedisMoviesSearcher.class, args);
    }

    @Bean
    CommandLineRunner loadData(MovieService movieService) {
        return args -> {
            if (movieService.isDataLoaded()) {
                log.info("Movies already loaded. Skipping data load.");
                return;
            }
            movieService.importMovies();
        };
    }

}
```

```bash
2025-05-18T11:25:25.358-04:00  INFO 59172 --- [           main] i.r.m.s.core.service.MovieService        : Starting processing the movies available at Redis...
2025-05-18T11:25:25.371-04:00  INFO 59172 --- [           main] i.r.m.s.core.service.MovieService        : Found 4527 records with the key prefix 'import:movie'
2025-05-18T11:25:25.673-04:00  INFO 59172 --- [           main] i.r.m.s.core.service.MovieService        : Loaded 4527 records into memory. Saving them all...
2025-05-18T11:25:38.775-04:00  INFO 59172 --- [pool-1-thread-1] i.r.m.s.core.service.MovieService        : Saved 4527/4527 movies (100.0%)
2025-05-18T11:25:38.776-04:00  INFO 59172 --- [           main] i.r.m.s.core.service.MovieService        : Processing complete: 4527 source keys loaded, saved 4527 records in 13.40 seconds
```

```bash
FT.DROPINDEX 'imported_movies_index' DD
```

## Part 3: Implementing the Searches

In this section, you will implement the search functionality in the application. It will allow users to search for movies using details of the movies like title and actors, as well as search based on the plot. You will implement the code to optimize the searches for maximum efficiency, combining different search strategies and caching computationally expensive operations when necessary.

### Task 1: Implementing the Full-Text Search

#### ⏰ Estimated time: **15 minutes**

```java
package io.redis.movies.searcher.core.service;

import ai.djl.util.Pair;
import com.redis.om.spring.search.stream.EntityStream;
import io.redis.movies.searcher.core.domain.*;
import io.redis.movies.searcher.core.dto.MovieDTO;
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

    public SearchService(EntityStream entityStream) {
        this.entityStream = entityStream;
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

        return new Pair<>(convertToDTOs(ftsMovies), ResultType.FTS);
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
```

### Task 2: Implementing the Vector Similarity Search

#### ⏰ Estimated time: **30 minutes**

```java
package io.redis.movies.searcher.core.service;

import ai.djl.util.Pair;
import com.redis.om.spring.search.stream.EntityStream;
import com.redis.om.spring.vectorize.Embedder;
import io.redis.movies.searcher.core.domain.*;
import io.redis.movies.searcher.core.dto.MovieDTO;
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
    private final Embedder embedder;

    public SearchService(EntityStream entityStream, Embedder embedder) {
        this.entityStream = entityStream;
        this.embedder = embedder;
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
        return embedder.getTextEmbeddingsAsBytes(List.of(query), Movie$.PLOT).getFirst();
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
```

### Task 3: Optimizing the Vector Similarity Search

#### ⏰ Estimated time: **15 minutes**

```java
package io.redis.movies.searcher.core.domain;

import com.redis.om.spring.annotations.*;
import com.redis.om.spring.indexing.DistanceMetric;
import com.redis.om.spring.indexing.VectorType;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import redis.clients.jedis.search.schemafields.VectorField;

@RedisHash(value = "keyword")
public class Keyword {

    @Id
    private String id;

    @Searchable
    @Vectorize(
            destination = "embedding",
            embeddingType = EmbeddingType.SENTENCE
    )
    private String value;

    @Indexed(
            schemaFieldType = SchemaFieldType.VECTOR,
            algorithm = VectorField.VectorAlgorithm.FLAT,
            type = VectorType.FLOAT32,
            dimension = 384,
            distanceMetric = DistanceMetric.COSINE,
            initialCapacity = 10
    )
    private byte[] embedding;

    public Keyword() {}

    public Keyword(String value) {
        this.value = value;
    }
    
    // Create getters and setters for all fields

}
```

```java
package io.redis.movies.searcher.core.repository;

import com.redis.om.spring.repository.RedisEnhancedRepository;
import io.redis.movies.searcher.core.domain.Keyword;

public interface KeywordRepository extends RedisEnhancedRepository<Keyword, String> {
}
```

```java
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
```

## Part 4: Moving the dataset to the cloud

In this section, you will replace your local database with one running in the cloud. You will replicate the entire data from one database to another and change the application to point to the new database. By the end, you should be able to use the application just like before with no breaking changes.

### Task 1: Create an account on Redis Cloud

#### ⏰ Estimated time: **5 minutes**

https://redis.io/try-free

![rc-welcome.png](images/rc-welcome.png)

### Task 2: Create an 30MB free database

#### ⏰ Estimated time: **5 minutes**

![rc-subscription.png](images/rc-subscription.png)

![rc-db-details.png](images/rc-db-details.png)

![rc-confirm.png](images/rc-confirm.png)

![rc-connect-1.png](images/rc-connect-1.png)

![rc-connect-2.png](images/rc-connect-2.png)

### Task 3: Replicate the entire dataset

#### ⏰ Estimated time: **10 minutes**

```bash
riot replicate \
  redis://localhost:6379 \
  redis://default:password@public-endpoint
```

### Task 4: Point the application to the cloud

#### ⏰ Estimated time: **10 minutes**

```bash
export REDIS_HOST=public-endpoint-without-port
```

```bash
export REDIS_PORT=public-endpoint-only-port
```

```bash
export REDIS_USERNAME=default
```

```bash
export REDIS_PASSWORD=password
```

```java
server.port=8081

spring.data.redis.host=${REDIS_HOST:localhost}
spring.data.redis.port=${REDIS_PORT:6379}

spring.data.redis.username=${REDIS_USERNAME:default}
spring.data.redis.password=${REDIS_PASSWORD}

redis.om.spring.ai.enabled=true
redis.om.spring.ai.embedding-batch-size=2048
redis.om.spring.repository.query.limit=50000
```

```bash
mvn spring-boot:run
```
