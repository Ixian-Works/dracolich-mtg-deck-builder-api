package dm.dracolich.deckBuilder.integration;

import dm.dracolich.forge.error.ApiError;
import dm.dracolich.forge.response.DmdResponse;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.GameFormatDto;
import dm.dracolich.mtgLibrary.dto.records.CardCollectionRecord;
import dm.dracolich.mtgLibrary.dto.records.CardCollectionRequest;
import dm.dracolich.mtgLibrary.dto.records.CardIdentifier;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static dm.dracolich.deckBuilder.integration.helpers.ErrorUtil.*;

@Component
@RequiredArgsConstructor
public class DracolichMtgLibraryClient {
    private final WebClient mtgLibraryWebClient;

    private static final String PATH_CARDS = "/cards";
    private static final String PATH_FORMATS = "/formats";
    /**
     * Retrieves card by given id number.
     *
     * @param id the card id
     * @return all the information for given card id
     */
    public Mono<CardDto> fetchCardById(String id) {
        return guarded(mtgLibraryWebClient.get()
                .uri(PATH_CARDS + "/{id}", id)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleLibraryClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleLibraryServerError)
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<CardDto>>() { })
                .map(DmdResponse::getPayload));
    }

    /**
     * Retrieves a card by its name (case-insensitive).
     *
     * @param name the card name
     * @return card data, or empty if not found (404 from upstream is mapped to libraryNotFound)
     */
    public Mono<CardDto> fetchCardByName(String name) {
        return guarded(mtgLibraryWebClient.get()
                .uri(uriBuilder -> uriBuilder.path(PATH_CARDS).queryParam("name", name).build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleLibraryClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleLibraryServerError)
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<CardDto>>() { })
                .map(DmdResponse::getPayload));
    }

    /**
     * Looks up many cards through the library's collection endpoint, at most
     * {@link CardCollectionRequest#MAX_IDENTIFIERS} per call. Returns a list aligned with
     * {@code identifiers}: the card for each one, or null where the library found none.
     */
    public Mono<List<CardDto>> resolveCards(List<CardIdentifier> identifiers) {
        if (identifiers.isEmpty()) {
            return Mono.just(List.of());
        }
        int max = CardCollectionRequest.MAX_IDENTIFIERS;
        List<List<CardIdentifier>> chunks = new ArrayList<>();
        for (int from = 0; from < identifiers.size(); from += max) {
            chunks.add(identifiers.subList(from, Math.min(from + max, identifiers.size())));
        }
        return Flux.fromIterable(chunks)
                .concatMap(chunk -> fetchCollection(chunk).map(record -> align(chunk, record)))
                .collectList()
                .map(parts -> {
                    List<CardDto> all = new ArrayList<>(identifiers.size());
                    parts.forEach(all::addAll);
                    return all;
                });
    }

    private Mono<CardCollectionRecord> fetchCollection(List<CardIdentifier> chunk) {
        return guarded(mtgLibraryWebClient.post()
                .uri(PATH_CARDS + "/collection")
                .bodyValue(new CardCollectionRequest(chunk))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleLibraryClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleLibraryServerError)
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<CardCollectionRecord>>() { })
                .map(DmdResponse::getPayload));
    }

    /**
     * Pairs each identifier with its card. The library answers with the cards found and the
     * identifiers not found, both in request order, so walking the request recovers the pairs.
     */
    static List<CardDto> align(List<CardIdentifier> request, CardCollectionRecord record) {
        List<CardDto> cards = record.cards() != null ? record.cards() : List.of();
        List<CardIdentifier> notFound = record.notFound() != null ? record.notFound() : List.of();
        List<CardDto> aligned = new ArrayList<>(request.size());
        int nextCard = 0;
        int nextMissing = 0;
        for (CardIdentifier identifier : request) {
            if (nextMissing < notFound.size() && notFound.get(nextMissing).equals(identifier)) {
                aligned.add(null);
                nextMissing++;
            } else {
                aligned.add(nextCard < cards.size() ? cards.get(nextCard++) : null);
            }
        }
        return aligned;
    }

    /**
     * A library that can't be reached, or doesn't answer within the configured timeouts, is
     * "unavailable" (DMD032), as a 5xx is.
     */
    private static <T> Mono<T> guarded(Mono<T> call) {
        return call.onErrorMap(e -> e instanceof WebClientRequestException || e instanceof TimeoutException,
                e -> libraryUnavailable());
    }

    /**
     * Retrieves game format by given code.
     *
     * @param code the format name code, e.g. COMMANDER, STANDARD_BRAWL
     * @return all the information for requested game format
     */
    public Mono<GameFormatDto> fetchFormatByCode(String code) {
        return guarded(mtgLibraryWebClient.get()
                .uri(PATH_FORMATS + "/{code}", code)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, this::handleLibraryClientError)
                .onStatus(HttpStatusCode::is5xxServerError, this::handleLibraryServerError)
                .bodyToMono(new ParameterizedTypeReference<DmdResponse<GameFormatDto>>() { })
                .map(DmdResponse::getPayload));
    }

    private Mono<? extends Throwable> handleLibraryClientError(ClientResponse response) {
        return response.bodyToMono(new ParameterizedTypeReference<DmdResponse<Object>>() {})
                .defaultIfEmpty(new DmdResponse<>())
                .map(dmd -> {
                    String upstreamMessage = dmd.getMessage() != null
                            ? dmd.getMessage()
                            : "Library call failed";
                    List<ApiError> upstreamErrors = dmd.getErrors() != null
                            ? dmd.getErrors()
                            : List.of();

                    if (response.statusCode() == HttpStatus.NOT_FOUND) {
                        return libraryNotFound(upstreamMessage, upstreamErrors);
                    }
                    return libraryClientError(upstreamMessage, upstreamErrors);
                });
    }

    private Mono<? extends Throwable> handleLibraryServerError(ClientResponse response) {
        return response.releaseBody()
                .then(Mono.just(libraryUnavailable()));
    }
}
