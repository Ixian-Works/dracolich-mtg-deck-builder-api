package dm.dracolich.deckBuilder.web.controller;

import dm.dracolich.deckBuilder.core.service.DeckAiService;
import dm.dracolich.deckBuilder.dto.DeckAiResultDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("ai")
@Tag(name = "AI")
@RequiredArgsConstructor
public class AiController {

    private final DeckAiService service;

    @Operation(summary = "Request AI card suggestions for a deck",
            description = "Returns the AI's text summary plus structured card suggestions extracted via the suggestCards tool. " +
                    "Creates or reuses an ai-api session per deck. Cached: subsequent calls without deck modifications return the previous response.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "AI result returned successfully",
                    content = @Content(schema = @Schema(implementation = DeckAiResultDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "404", description = "Deck not found"),
            @ApiResponse(responseCode = "422", description = "The deck has no cards (DMD042), or the model declined the request (DMD040 with DMD037)"),
            @ApiResponse(responseCode = "429", description = "The AI is rate limited (DMD040 with DMD038)"),
            @ApiResponse(responseCode = "502", description = "ai-api would not take the request deck-builder sent (DMD040)"),
            @ApiResponse(responseCode = "503", description = "AI API unavailable (DMD041)")
    })
    @PostMapping(path = "/decks/{id}/suggest", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<DeckAiResultDto> suggestForDeck(@PathVariable String id) {
        return service.suggestForDeck(id);
    }

    @Operation(summary = "Request AI deck analysis",
            description = "Returns the AI's text summary plus structured card suggestions extracted via the suggestCards tool. " +
                    "Creates or reuses an ai-api session per deck. Cached: subsequent calls without deck modifications return the previous response.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "AI result returned successfully",
                    content = @Content(schema = @Schema(implementation = DeckAiResultDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized"),
            @ApiResponse(responseCode = "404", description = "Deck not found"),
            @ApiResponse(responseCode = "422", description = "The deck has no cards (DMD042), or the model declined the request (DMD040 with DMD037)"),
            @ApiResponse(responseCode = "429", description = "The AI is rate limited (DMD040 with DMD038)"),
            @ApiResponse(responseCode = "502", description = "ai-api would not take the request deck-builder sent (DMD040)"),
            @ApiResponse(responseCode = "503", description = "AI API unavailable (DMD041)")
    })
    @PostMapping(path = "/decks/{id}/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<DeckAiResultDto> analyzeDeck(@PathVariable String id) {
        return service.analyzeDeck(id);
    }
}
