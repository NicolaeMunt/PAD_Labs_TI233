package md.utm.demo.game;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;

@Document(collection = "games")
@CompoundIndex(name = "uq_title_dev",
               def = "{'title': 1, 'developer': 1}",
               unique = true)
public class Game {

    @Id
    private String id;

    private String title;

    private String developer;

    @Indexed
    private Genre genre;

    private int releaseYear;

    private Instant deletedAt;

    private Instant createdAt;
    private Instant updatedAt;

    protected Game() {
        // required by Spring Data for document → object mapping
    }

    public Game(String title, String developer, Genre genre, int releaseYear) {
        this.title = title;
        this.developer = developer;
        this.genre = genre;
        this.releaseYear = releaseYear;
    }

    public void update(String title, String developer, Genre genre, int releaseYear) {
        this.title = title;
        this.developer = developer;
        this.genre = genre;
        this.releaseYear = releaseYear;
    }

    public String getId()        { return id; }
    public String getTitle()     { return title; }
    public String getDeveloper() { return developer; }
    public Genre getGenre()      { return genre; }
    public int getReleaseYear()  { return releaseYear; }
    public void softDelete() {
        this.deletedAt = Instant.now();
    }
}