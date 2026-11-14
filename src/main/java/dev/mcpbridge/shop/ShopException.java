package dev.mcpbridge.shop;

/** Errors from the shop backend. Messages are written for the end user / the model, never contain internals. */
public abstract sealed class ShopException extends RuntimeException
        permits ShopException.NotFound, ShopException.RuleViolation, ShopException.InvalidRequest, ShopException.Unavailable {

    protected ShopException(String message) {
        super(message);
    }

    /** The order, product or customer does not exist. */
    public static final class NotFound extends ShopException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** The request is well-formed but breaks a business rule (refund too large, stock would go negative, ...). */
    public static final class RuleViolation extends ShopException {
        public RuleViolation(String message) {
            super(message);
        }
    }

    /** Bad input from the caller. */
    public static final class InvalidRequest extends ShopException {
        public InvalidRequest(String message) {
            super(message);
        }
    }

    /** The upstream system could not be reached. */
    public static final class Unavailable extends ShopException {
        public Unavailable(String message) {
            super(message);
        }
    }
}
