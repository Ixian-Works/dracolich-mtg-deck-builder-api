package dm.dracolich.deckBuilder.integration;

import dm.dracolich.ai.dto.SessionDto;
import dm.dracolich.ai.dto.records.ChatRequest;
import dm.dracolich.ai.dto.records.SessionCreateRequest;
import dm.dracolich.forge.error.ApiError;
import dm.dracolich.forge.response.DmdResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static dm.dracolich.deckBuilder.integration.helpers.ErrorUtil.aiRejected;
import static dm.dracolich.deckBuilder.integration.helpers.ErrorUtil.aiUnavailable;

@Component
@RequiredArgsConstructor
public class DracolichAiClient {
    private final WebClient aiApiWebClient;

    private static final String PATH_SESSION = "/agent/session";
    private static final String PATH_CHAT = "/agent/chat";

    /**
     * Creates a new ai-api session. An ANALYSIS session needs a non-empty deck list; a BUILD
     * session needs a commander name and keeps nothing else of the deck.
     */
    public Mono<SessionDto> createSession(SessionCreateRequest request) {
        return aiApiWebClient.post()
                .uri(PATH_SESSION)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleAiClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleAiServerError)
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<SessionDto>>() { })
                .map(DmdResponse::getPayload);
    }

    /**
     * Fetches a session by id from ai-api. Used after streaming completes to retrieve
     * structured fields (cardSuggestions, future analysisIssues) the AI populated via tool calls.
     */
    public Mono<SessionDto> getSession(String sessionId) {
        return aiApiWebClient.get()
                .uri(PATH_SESSION + "/{id}", sessionId)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleAiClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleAiServerError)
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<SessionDto>>() { })
                .map(DmdResponse::getPayload);
    }

    /**
     * Streams the AI's response to a chat message via SSE pass-through.
     * Each emission is one chunk of the AI's response text.
     */
    public Flux<String> chatStream(ChatRequest request) {
        return aiApiWebClient.post()
                .uri(PATH_CHAT)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(request)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleAiClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleAiServerError)
                .bodyToFlux(String.class);
    }

    private Mono<? extends Throwable> handleAiClientError(ClientResponse response) {
        return upstreamEnvelope(response)
                .map(dmd -> aiRejected(
                        response.statusCode(),
                        dmd.getMessage() != null ? dmd.getMessage() : "AI call failed",
                        upstreamErrors(dmd)));
    }

    private Mono<? extends Throwable> handleAiServerError(ClientResponse response) {
        return upstreamEnvelope(response)
                .map(dmd -> aiUnavailable(upstreamErrors(dmd)));
    }

    /**
     * ai-api answers a failure with forge's envelope. Whatever stands in front of it may not: a
     * proxy's 502 is an HTML page. A body that isn't an envelope reads as an empty one.
     */
    private static Mono<DmdResponse<Object>> upstreamEnvelope(ClientResponse response) {
        return response.bodyToMono(new ParameterizedTypeReference<DmdResponse<Object>>() { })
                .onErrorResume(unreadable -> Mono.empty())
                .defaultIfEmpty(new DmdResponse<>());
    }

    private static List<ApiError> upstreamErrors(DmdResponse<Object> dmd) {
        return dmd.getErrors() != null ? dmd.getErrors() : List.of();
    }
}
