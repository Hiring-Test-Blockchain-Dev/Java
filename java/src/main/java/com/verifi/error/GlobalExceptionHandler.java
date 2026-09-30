package com.verifi.error;

import com.verifi.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final AppProperties props;

    public GlobalExceptionHandler(AppProperties props) {
        this.props = props;
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> api(ApiException ex) {
        if (ex.status() >= 500) log.error(ex.getMessage(), ex);
        return ResponseEntity.status(ex.status()).body(body(ex.getMessage(), ex.code(), ex.status() >= 500 ? ex : null));
    }

    @ExceptionHandler(DateTimeParseException.class)
    public ResponseEntity<Map<String, Object>> badDate(DateTimeParseException ex) {
        return ResponseEntity.badRequest().body(body("Valid date required", "VALIDATION_ERROR", null));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<Map<String, Object>> missing(NoHandlerFoundException ex) {
        return ResponseEntity.status(404).body(body("Not found", "NOT_FOUND", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> fallback(Exception ex, HttpServletRequest request) {
        log.error("Request failed {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(500).body(body("Internal server error", "INTERNAL_ERROR", ex));
    }

    private Map<String, Object> body(String message, String code, Exception stackSource) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("code", code);
        if (!props.isProduction() && stackSource != null) {
            StringWriter writer = new StringWriter();
            stackSource.printStackTrace(new PrintWriter(writer));
            body.put("stack", writer.toString());
        }
        return body;
    }
}
