package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.dto.DeckAiResultDto;
import reactor.core.publisher.Mono;

public interface DeckAiService {
    Mono<DeckAiResultDto> suggestForDeck(String deckId);
    Mono<DeckAiResultDto> analyzeDeck(String deckId);
}
