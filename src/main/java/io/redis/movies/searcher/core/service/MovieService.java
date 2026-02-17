package io.redis.movies.searcher.core.service;

import io.redis.movies.searcher.core.domain.Movie;
import io.redis.movies.searcher.core.repository.MovieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class MovieService {

    private static final Logger log = LoggerFactory.getLogger(MovieService.class);
    private static final String KEY_PREFIX = "movie:";

    private final MovieRepository movieRepository;
    private final RedisTemplate<String, Object> redisTemplate;

    public MovieService(MovieRepository movieRepository, RedisTemplate<String, Object> redisTemplate) {
        this.movieRepository = movieRepository;
        this.redisTemplate = redisTemplate;
    }

    public void regenerateMissingEmbeddings() {
        log.info("Scanning for movies with missing embeddings...");
        var startTime = Instant.now();

        // Scan for all movie keys using SCAN command
        List<Integer> movieIds = new ArrayList<>();
        ScanOptions scanOptions = ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(1000).build();
        try (Cursor<String> cursor = redisTemplate.scan(scanOptions)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String idStr = key.substring(KEY_PREFIX.length());
                try {
                    movieIds.add(Integer.parseInt(idStr));
                } catch (NumberFormatException e) {
                    log.warn("Skipping invalid key: {}", key);
                }
            }
        }
        log.info("Found {} movie keys in Redis", movieIds.size());

        // Find movies missing embeddings and with a plot value
        List<Movie> moviesWithoutEmbeddings = new ArrayList<>();
        for (Integer id : movieIds) {
            movieRepository.findById(id).ifPresent(movie -> {
                if (movie.getPlot() != null && !movie.getPlot().isBlank() && movie.getPlotEmbedding() == null) {
                    moviesWithoutEmbeddings.add(movie);
                }
            });
        }

        log.info("Found {} movies without embeddings.", moviesWithoutEmbeddings.size());
        if (moviesWithoutEmbeddings.isEmpty()) {
            return;
        }

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final int batchSize = 500;
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            AtomicInteger savedCounter = new AtomicInteger(0);

            for (int i = 0; i < moviesWithoutEmbeddings.size(); i += batchSize) {
                int end = Math.min(i + batchSize, moviesWithoutEmbeddings.size());
                List<Movie> batch = moviesWithoutEmbeddings.subList(i, end);

                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    try {
                        movieRepository.saveAll(batch);
                        int totalSaved = savedCounter.addAndGet(batch.size());
                        int previousMilestone = (totalSaved - batch.size()) / 1000;
                        int currentMilestone = totalSaved / 1000;
                        if (currentMilestone > previousMilestone || totalSaved == moviesWithoutEmbeddings.size()) {
                            double percentComplete = (totalSaved * 100.0) / moviesWithoutEmbeddings.size();
                            log.info("Regenerated embeddings for {}/{} movies ({}%)",
                                    totalSaved, moviesWithoutEmbeddings.size(),
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

        var duration = Duration.between(startTime, Instant.now());
        double seconds = duration.toMillis() / 1000.0;
        log.info("Embedding regeneration complete: {} movies processed in {} seconds",
                moviesWithoutEmbeddings.size(),
                String.format("%.2f", seconds));
    }
}
