package md.utm.demo.common;

public class DuplicateGameException extends RuntimeException {
    public DuplicateGameException(String title) {
        super("Game already exists: " + title);
    }
}