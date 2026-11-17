package dm.dracolich.deckBuilder.web.config;

import dm.dracolich.deckBuilder.core.service.DeckService;
import dm.dracolich.deckBuilder.dto.DeckDto;
import dm.dracolich.deckBuilder.web.controller.DeckController;
import dm.dracolich.forge.controller.ControllerAdvice;
import dm.dracolich.forge.security.AnonCookieFilter;
import dm.dracolich.forge.security.AnonCookieSigner;
import dm.dracolich.forge.security.JwtTokenValidator;
import dm.dracolich.forge.security.ReactiveSecurityContextUtil;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two identity filters in the order this service registers them, JWT then anonymous cookie, in
 * front of the real deck controller. Since forge 8.1.3 a bad token is answered 401 and is never handed
 * an anonymous identity; no token still gets one.
 */
class BadTokenTest {

    private static final String SECRET = "a-test-cookie-secret-that-is-long-enough";

    private final DeckService service = mock(DeckService.class);
    private WebTestClient client;

    @BeforeEach
    void setUp() {
        SecurityConfig config = new SecurityConfig();
        // Every token fails, as an expired one does.
        JwtTokenValidator expired = token -> Mono.error(new ExpiredJwtException(null, null, "JWT expired"));
        AnonCookieSigner signer = config.anonCookieSigner(SECRET);
        AnonCookieFilter anonFilter = config.anonCookieFilter(signer, Duration.ofHours(24), false);

        // The service reports who the filters said the caller is, so the test can see it.
        when(service.fetchDeckById(anyString())).thenAnswer(i -> ReactiveSecurityContextUtil.getPrincipal()
                .map(principal -> {
                    DeckDto dto = new DeckDto();
                    dto.setName(principal.type() + ":" + principal.id());
                    return dto;
                }));

        client = WebTestClient.bindToController(new DeckController(service, signer))
                .webFilter(config.jwtFilter(expired), anonFilter)
                .controllerAdvice(new ControllerAdvice())
                .build();
    }

    @Test
    void aBadTokenIs401AndGetsNoAnonymousIdentity() {
        client.get().uri("/decks/deck-1")
                .header(HttpHeaders.AUTHORIZATION, "Bearer an.expired.token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"invalid_token\"")
                .expectHeader().doesNotExist(HttpHeaders.SET_COOKIE);

        verify(service, never()).fetchDeckById(anyString());
    }

    @Test
    void noTokenStillGetsAnAnonymousIdentityAndItsCookie() {
        client.get().uri("/decks/deck-1")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().value(HttpHeaders.SET_COOKIE, cookie -> org.assertj.core.api.Assertions
                        .assertThat(cookie).startsWith(AnonCookieFilter.COOKIE_NAME + "="))
                .expectBody().jsonPath("$.name").value(name -> org.assertj.core.api.Assertions
                        .assertThat((String) name).startsWith("ANON:"));
    }

    @Test
    void aMalformedBodyIs400WithDmd043() {
        client.post().uri("/decks/")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ this is not json")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errors[0].error").isEqualTo("DMD043");
    }
}
