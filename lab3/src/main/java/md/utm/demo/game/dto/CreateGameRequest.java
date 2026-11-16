package md.utm.demo.game.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import md.utm.demo.game.Genre;

public record CreateGameRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank @Size(max = 120) String developer,
        @NotNull (message = "Genre is required") Genre genre,
        @Min(1958) @Max(2100) int releaseYear
) { }