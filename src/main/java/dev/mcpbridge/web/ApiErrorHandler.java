package dev.mcpbridge.web;

import dev.mcpbridge.shop.ShopException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

@RestControllerAdvice(basePackageClasses = ApiErrorHandler.class)
public class ApiErrorHandler {

    @ExceptionHandler(ShopException.class)
    ResponseEntity<Map<String, String>> shop(ShopException e) {
        HttpStatus status = switch (e) {
            case ShopException.NotFound n -> HttpStatus.NOT_FOUND;
            case ShopException.RuleViolation r -> HttpStatus.UNPROCESSABLE_ENTITY;
            case ShopException.InvalidRequest i -> HttpStatus.BAD_REQUEST;
            case ShopException.Unavailable u -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(Map.of("error", status.name(), "message", e.getMessage()));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<Map<String, String>> badRequest(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", e.getMessage()));
    }
}
