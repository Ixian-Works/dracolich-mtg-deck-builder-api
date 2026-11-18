package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.core.mapper.DeckMapper;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSetRegistry;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.ImportValidateRequest;
import dm.dracolich.deckBuilder.integration.DracolichMtgLibraryClient;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.CardFaceDto;
import dm.dracolich.mtgLibrary.dto.GameplayPropertyDto;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import dm.dracolich.mtgLibrary.dto.records.CardIdentifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Import resolves every line in one library call, and still names each line it couldn't resolve. */
class DeckServiceImplImportTest {

    private final DracolichMtgLibraryClient library = mock(DracolichMtgLibraryClient.class);
    private final DeckServiceImpl service = new DeckServiceImpl(mock(DeckRepository.class), mock(FavoriteRepository.class),
            mock(DeckMapper.class), library, new FormatRuleSetRegistry());

    private static CardDto card(String name) {
        GameplayPropertyDto gameplay = new GameplayPropertyDto();
        gameplay.setManaValue(1.0);
        CardFaceDto face = new CardFaceDto();
        face.setGameplayProperty(gameplay);
        face.setFullType("Instant");
        CardDto dto = new CardDto();
        dto.setId(name.toLowerCase());
        dto.setName(name);
        dto.setDefaultFace(face);
        return dto;
    }

    /** The library knows every name except those in {@code unknown}. */
    private void libraryKnowsAllBut(Set<String> unknown) {
        when(library.resolveCards(anyList())).thenAnswer(i -> {
            List<CardDto> answer = new ArrayList<>();
            for (CardIdentifier identifier : i.<List<CardIdentifier>>getArgument(0)) {
                answer.add(unknown.contains(identifier.name()) ? null : card(identifier.name()));
            }
            return Mono.just(answer);
        });
    }

    @Test
    void aHundredLineListIsOneLibraryCall() {
        libraryKnowsAllBut(Set.of());
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 100; i++) text.append("1 Card ").append(i).append('\n');

        StepVerifier.create(service.validateImportedDeck(new ImportValidateRequest(Format.COMMANDER, text.toString())))
                .assertNext(analysis -> assertThat(analysis).isNotNull())
                .verifyComplete();

        verify(library, times(1)).resolveCards(anyList());
        verify(library, never()).fetchCardByName(anyString());
    }

    @Test
    void threeUnknownNamesAreReportedOnTheirOwnLines() {
        libraryKnowsAllBut(Set.of("Not A Card", "Also Fake", "Third Fake"));
        String text = """
                1 Lightning Bolt
                1 Not A Card
                1 Counterspell
                2 Also Fake
                1 Third Fake
                """;

        StepVerifier.create(service.validateImportedDeck(new ImportValidateRequest(Format.MODERN, text)))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ResponseException.class);
                    ResponseException response = (ResponseException) e;
                    assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(response.getErrors()).extracting("field").containsExactly(
                            "line 2: '1 Not A Card' — Card 'Not A Card' not found in library",
                            "line 4: '2 Also Fake' — Card 'Also Fake' not found in library",
                            "line 5: '1 Third Fake' — Card 'Third Fake' not found in library");
                })
                .verify();
    }
}
