package dm.dracolich.deckBuilder.integration.helpers;

import dm.dracolich.deckBuilder.dto.error.ErrorCodes;
import dm.dracolich.forge.error.ApiError;
import dm.dracolich.forge.exception.ResponseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.ArrayList;
import java.util.List;

public final class ErrorUtil {

    private ErrorUtil() {
    }

    public static ResponseException libraryNotFound(String message, List<ApiError> upstreamErrors) {
        var error = new ApiError(ErrorCodes.DMD030);
        return new ResponseException("MTG Library lookup failed: " + message,
                List.of(error), HttpStatus.NOT_FOUND);
    }

    public static ResponseException libraryClientError(String message, List<ApiError> upstreamErrors) {
        var error = new ApiError(ErrorCodes.DMD031);
        return new ResponseException("MTG Library request rejected: " + message,
                List.of(error), HttpStatus.BAD_REQUEST);
    }

    public static ResponseException libraryUnavailable() {
        var error = new ApiError(ErrorCodes.DMD032);
        return new ResponseException("MTG Library API unavailable",
                List.of(error), HttpStatus.SERVICE_UNAVAILABLE);
    }

    /**
     * ai-api answered with a 4xx. Two of those are about the player's request and keep their
     * status: 422 (the model declined) and 429 (rate limited). Any other 4xx means deck-builder
     * sent something ai-api would not take, which is this service's fault and not the caller's,
     * so it becomes a 502.
     *
     * <p>ai-api's own error codes follow {@code DMD040}, so a client can tell the cases apart.</p>
     */
    public static ResponseException aiRejected(HttpStatusCode upstreamStatus, String message,
                                               List<ApiError> upstreamErrors) {
        HttpStatus status = switch (upstreamStatus.value()) {
            case 422 -> HttpStatus.UNPROCESSABLE_CONTENT;
            case 429 -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_GATEWAY;
        };
        return new ResponseException(ErrorCodes.DMD040.format(message),
                withUpstream(ErrorCodes.DMD040, upstreamErrors), status);
    }

    /** ai-api answered with a 5xx, or with something that is not its error envelope. */
    public static ResponseException aiUnavailable(List<ApiError> upstreamErrors) {
        return new ResponseException(ErrorCodes.DMD041.getMessage(),
                withUpstream(ErrorCodes.DMD041, upstreamErrors), HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static List<ApiError> withUpstream(ErrorCodes own, List<ApiError> upstreamErrors) {
        List<ApiError> errors = new ArrayList<>();
        errors.add(new ApiError(own));
        if (upstreamErrors != null) errors.addAll(upstreamErrors);
        return List.copyOf(errors);
    }
}
