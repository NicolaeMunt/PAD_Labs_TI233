package md.utm.demo.game;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import md.utm.demo.common.DuplicateGameException;
import md.utm.demo.common.NotFoundException;
import md.utm.demo.game.dto.CreateGameRequest;
import md.utm.demo.game.dto.GameResponse;

@Service
public class GameService {

    private final GameRepository repository;

    public GameService(GameRepository repository) {
        this.repository = repository;
    }

    public Page<GameResponse> findAll(Genre genre, Pageable pageable) {
        Page<Game> games = (genre == null)
                ? repository.findByDeletedAtIsNull(pageable)
                : repository.findByGenreAndDeletedAtIsNull(genre, pageable);
        return games.map(GameResponse::from);
    }
    @Cacheable(value = "games", key = "#id")
    public GameResponse findById(String id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .map(GameResponse::from)
                .orElseThrow(() -> new NotFoundException("Game " + id + " not found"));
    }

    public GameResponse create(CreateGameRequest request) {
        if (repository.existsByTitleIgnoreCaseAndDeveloperIgnoreCaseAndDeletedAtIsNull(
                request.title(), request.developer())) {
            throw new DuplicateGameException(request.title());
        }
        Game game = new Game(request.title(), request.developer(),
                request.genre(), request.releaseYear());
        return GameResponse.from(repository.save(game));
    }
    @CacheEvict(value = "games", key = "#id")
    public GameResponse update(String id, CreateGameRequest request) {
        Game game = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("Game " + id + " not found"));

        game.update(request.title(), request.developer(),
                request.genre(), request.releaseYear());
        return GameResponse.from(repository.save(game));
    }

    @CacheEvict(value = "games", key = "#id")
    public void delete(String id) {
        Game game = repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new NotFoundException("Game " + id + " not found"));

        game.softDelete();
        repository.save(game);
    }
}