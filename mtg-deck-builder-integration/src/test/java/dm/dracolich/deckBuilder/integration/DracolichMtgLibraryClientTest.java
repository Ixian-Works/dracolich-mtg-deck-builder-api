package dm.dracolich.deckBuilder.integration;

import dm.dracolich.deckBuilder.integration.config.MtgLibraryConfig;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.records.CardCollectionRecord;
import dm.dracolich.mtgLibrary.dto.records.CardIdentifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DracolichMtgLibraryClientTest {

    private static CardDto card(String id) {
        CardDto dto = new CardDto();
        dto.setId(id);
        return dto;
    }

    @Test
    void theAnswerIsPairedBackWithTheRequest() {
        List<CardIdentifier> request = List.of(CardIdentifier.byName("Bolt"), CardIdentifier.byName("Nope"),
                CardIdentifier.byId("x"), CardIdentifier.byId("x"), CardIdentifier.byName("Also nope"));
        CardCollectionRecord answer = new CardCollectionRecord(
                List.of(card("bolt"), card("x"), card("x")),
                List.of(CardIdentifier.byName("Nope"), CardIdentifier.byName("Also nope")));

        List<CardDto> aligned = DracolichMtgLibraryClient.align(request, answer);

        assertThat(aligned).extracting(c -> c == null ? null : c.getId())
                .containsExactly("bolt", null, "x", "x", null);
    }

    @Test
    void aHundredAndFiftyIdentifiersAreTwoCalls() {
        AtomicInteger calls = new AtomicInteger();
        WebClient stub = WebClient.builder()
                .exchangeFunction(request -> {
                    calls.incrementAndGet();
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body("{\"success\":true,\"payload\":{\"cards\":[],\"not_found\":[]}}")
                            .build());
                })
                .build();
        List<CardIdentifier> identifiers = new ArrayList<>();
        for (int i = 0; i < 150; i++) identifiers.add(CardIdentifier.byId("c" + i));

        StepVerifier.create(new DracolichMtgLibraryClient(stub).resolveCards(identifiers))
                .assertNext(cards -> assertThat(cards).hasSize(150))
                .verifyComplete();

        assertThat(calls).hasValue(2);
    }

    @Test
    void noIdentifiersMakeNoCall() {
        WebClient failing = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new AssertionError("no call expected")))
                .build();

        StepVerifier.create(new DracolichMtgLibraryClient(failing).resolveCards(List.of()))
                .assertNext(cards -> assertThat(cards).isEmpty())
                .verifyComplete();
    }

    @Test
    void aLibraryThatNeverAnswersFailsWithinTheTimeoutAsUnavailable() {
        // A real local server that accepts the request and never replies.
        DisposableServer silent = HttpServer.create().port(0)
                .handle((request, response) -> Mono.never())
                .bindNow();
        try {
            WebClient client = WebClient.builder()
                    .baseUrl("http://localhost:" + silent.port())
                    .clientConnector(new ReactorClientHttpConnector(
                            MtgLibraryConfig.httpClient(Duration.ofSeconds(1), Duration.ofMillis(300))))
                    .build();

            StepVerifier.create(new DracolichMtgLibraryClient(client).fetchCardById("bolt"))
                    .expectErrorSatisfies(e -> {
                        assertThat(e).isInstanceOf(ResponseException.class);
                        ResponseException response = (ResponseException) e;
                        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                        assertThat(response.getErrors()).extracting("error").containsExactly("DMD032");
                    })
                    .verify(Duration.ofSeconds(5));
        } finally {
            silent.disposeNow();
        }
    }
}
