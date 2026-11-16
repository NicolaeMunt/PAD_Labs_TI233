package md.utm.demo.game.dto;

import com.fasterxml.jackson.annotation.JsonRootName;

import md.utm.demo.game.Game;
import md.utm.demo.game.Genre;

@JsonRootName("game")
public record GameResponse(
        String id,
        String title,
        String developer,
        Genre genre,
        int releaseYear
) {
    public static GameResponse from(Game game) {
        return new GameResponse(
                game.getId(),
                game.getTitle(),
                game.getDeveloper(),
                game.getGenre(),
                game.getReleaseYear());
    }
}