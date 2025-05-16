package io.redis.movies.searcher;

import com.redis.om.spring.annotations.EnableRedisDocumentRepositories;
import com.redis.om.spring.annotations.EnableRedisEnhancedRepositories;
import io.redis.movies.searcher.core.service.MovieService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;


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
