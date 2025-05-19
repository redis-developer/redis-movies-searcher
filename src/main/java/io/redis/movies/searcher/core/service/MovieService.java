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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
public class MovieService {

    private static final Logger log = LoggerFactory.getLogger(MovieService.class);

    private final MovieRepository movieRepository;
    private final MovieDataRepository movieDataRepository;
    private final RedisTemplate redisTemplate;

    public MovieService(MovieRepository movieRepository,
                        MovieDataRepository movieDataRepository,
                        RedisTemplate redisTemplate) {
        this.movieRepository = movieRepository;
        this.movieDataRepository = movieDataRepository;
        this.redisTemplate = redisTemplate;
    }

    public void importMovies() {
        log.info("Starting processing the movies available at Redis...");

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

        if (!movies.isEmpty()) {
            log.info("Loaded {} records into memory. Saving them all...", movies.size());
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
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

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
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
