package dm.dracolich.deckBuilder.web.controller;

import dm.dracolich.deckBuilder.core.service.FavoriteService;
import dm.dracolich.deckBuilder.dto.DeckDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("favorites")
@Tag(name = "Favorites")
@RequiredArgsConstructor
public class FavoriteController {
    private final FavoriteService service;

    @Operation(summary = "Favorite deck", description = "Favorite someone else's public deck. Favoriting one's own deck is refused.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Deck added successfully to favorites list"),
            @ApiResponse(responseCode = "400", description = "The deck is the caller's own (DMD049)"),
            @ApiResponse(responseCode = "401", description = "Not signed in, or a deck that isn't public"),
            @ApiResponse(responseCode = "404", description = "Deck not found"),
            @ApiResponse(responseCode = "503", description = "Internal server error")
    })
    @PostMapping(path = {""}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<?> favoriteDeck(@RequestParam(name = "deck_id") String deckId) {
        return service.favoriteDeck(deckId);
    }

    @Operation(summary = "Unfavorite deck", description = "Remove a deck from the caller's favorites, even one deleted or no longer public")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Deck removed from the favorites list"),
            @ApiResponse(responseCode = "401", description = "Not signed in"),
            @ApiResponse(responseCode = "503", description = "Internal server error")
    })
    @DeleteMapping(path = {""}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<?> unfavoriteDeck(@RequestParam(name = "deck_id") String deckId) {
        return service.unfavoriteDeck(deckId);
    }

    @Operation(summary = "My favorite ids", description = "The signed-in player's favorite deck ids, newest favorite first, all in one answer (at most 5,000). For drawing every star without paging through decks.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The ids"),
            @ApiResponse(responseCode = "401", description = "Not signed in")
    })
    @GetMapping(path = {"/ids"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<String>> myFavoriteIds() {
        return service.myFavoriteIds();
    }
}
