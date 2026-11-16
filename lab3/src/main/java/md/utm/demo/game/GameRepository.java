package md.utm.demo.game;

import java.util.List;  
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface GameRepository extends MongoRepository<Game, String> {

    // derived from the method name — no implementation needed
    Page<Game> findByGenre(Genre genre, Pageable pageable);

    Optional<Game> findByTitleIgnoreCase(String title);

    boolean existsByTitleIgnoreCaseAndDeveloperIgnoreCase(String title, String developer);

    Optional<Game> findByIdAndDeletedAtIsNull(String id);

    Page<Game> findByDeletedAtIsNull(Pageable pageable);

    Page<Game> findByGenreAndDeletedAtIsNull(Genre genre, Pageable pageable);

    boolean existsByTitleIgnoreCaseAndDeveloperIgnoreCaseAndDeletedAtIsNull(
            String title, String developer);
    @Query("{ 'releaseYear': { $gte: ?0 }, 'genre': { $in: ?1 } }")
    Page<Game> findModernByGenres(int fromYear, List<Genre> genres, Pageable pageable);
}