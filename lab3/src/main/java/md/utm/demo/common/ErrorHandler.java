package md.utm.demo.common;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.accept.ContentNegotiationManager;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Turns exceptions into RFC 7807 problem responses. The body format (JSON or XML)
 * follows what the client asked for, the same way successful responses do.
 */
// highest precedence so these handlers win over Spring's built-in problem-details advice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    private final ContentNegotiationManager negotiation;

    public ErrorHandler(ContentNegotiationManager negotiation) {
        this.negotiation = negotiation;
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(NotFoundException e, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, e.getMessage(), request);
    }

    @ExceptionHandler(DuplicateGameException.class)
    ResponseEntity<ProblemDetail> conflict(DuplicateGameException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, e.getMessage(), request);
    }

    // lost the race against another create: the unique index rejected the insert
    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> duplicateKey(DuplicateKeyException e, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT,
                "A game with this title and developer already exists", request);
    }

    // the game was changed by someone else between our read and our save
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> concurrentUpdate(OptimisticLockingFailureException e,
                                                   HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT,
                "The game was modified concurrently, please retry", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> badRequest(HttpMessageNotReadableException e,
                                             HttpServletRequest request) {
        log.error("Bad request: " + e.getMessage(), e);
        return problem(HttpStatus.BAD_REQUEST, "Invalid request body", request);
    }

    // @Valid failed: list every broken field
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> invalid(MethodArgumentNotValidException e,
                                          HttpServletRequest request) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, detail, request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail,
                                                  HttpServletRequest request) {
        MediaType type = wantsXml(request)
                ? MediaType.APPLICATION_PROBLEM_XML
                : MediaType.APPLICATION_PROBLEM_JSON;
        return ResponseEntity.status(status).contentType(type)
                .body(ProblemDetail.forStatusAndDetail(status, detail));
    }

    /** True when the client's most preferred concrete type is XML; JSON is the default. */
    private boolean wantsXml(HttpServletRequest request) {
        List<MediaType> accepted;
        try {
            accepted = negotiation.resolveMediaTypes(new ServletWebRequest(request));
        } catch (HttpMediaTypeNotAcceptableException ex) {
            return false;
        }
        for (MediaType type : accepted) {            // already sorted by preference
            if (type.getSubtype().contains("xml")) return true;
            if (type.getSubtype().contains("json")) return false;
        }
        return false;
    }
}
