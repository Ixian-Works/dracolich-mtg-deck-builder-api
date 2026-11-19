package dm.dracolich.deckBuilder.core.stats;

import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.dto.DeckStatsDto;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.mtgLibrary.dto.enums.Color;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Statistics count the main deck and the commanders; the sideboard apart; the maybe board never. */
class DeckStatsCalculatorTest {

    private static DeckCardEntity card(String name, String type, double cmc, int count, CardCategory board, Color... colors) {
        return DeckCardEntity.builder()
                .cardId(name).name(name).typeLine(type).cmc(cmc).count(count)
                .colors(Set.of(colors)).cardCategory(board)
                .build();
    }

    private static DeckEntity deck(DeckCardEntity... cards) {
        return DeckEntity.builder()
                .id("deck-1")
                .commander(new ArrayList<>(List.of(card("Krenko, Mob Boss", "Legendary Creature — Goblin", 4.0, 1, null, Color.R))))
                .cards(new ArrayList<>(List.of(cards)))
                .build();
    }

    private static final DeckCardEntity BOLT = card("Lightning Bolt", "Instant", 1.0, 4, CardCategory.MAINBOARD, Color.R);
    private static final DeckCardEntity MOUNTAIN = card("Mountain", "Basic Land — Mountain", 0.0, 20, CardCategory.MAINBOARD);

    @Test
    void aCardInTheMaybeBoardChangesNothing() {
        DeckStatsDto without = DeckStatsCalculator.compute(deck(BOLT, MOUNTAIN));
        DeckStatsDto with = DeckStatsCalculator.compute(deck(BOLT, MOUNTAIN,
                card("Counterspell", "Instant", 2.0, 4, CardCategory.MAYBE_BOARD, Color.U),
                card("Island", "Basic Land — Island", 0.0, 10, CardCategory.MAYBE_BOARD)));

        assertThat(with).isEqualTo(without);
    }

    @Test
    void aSideboardCardIsNotInTheMainDecksCurveOrTotalsAndIsReportedApart() {
        DeckStatsDto stats = DeckStatsCalculator.compute(deck(BOLT, MOUNTAIN,
                card("Pyroblast", "Instant", 1.0, 2, CardCategory.SIDEBOARD, Color.R),
                card("Ancient Grudge", "Instant", 2.0, 3, CardCategory.SIDEBOARD, Color.R)));

        assertThat(stats.cardCount()).isEqualTo(25);           // Krenko, 4 Bolts, 20 Mountains
        assertThat(stats.landCount()).isEqualTo(20);
        assertThat(stats.manaCurve()).isEqualTo(Map.of(1, 4, 4, 1));
        assertThat(stats.typeBreakdown()).doesNotContainKey("Sorcery").containsEntry("Instant", 4);
        assertThat(stats.averageCmc()).isEqualTo(1.6);         // (4 + 4*1) / 5

        assertThat(stats.sideboard()).isNotNull();
        assertThat(stats.sideboard().cardCount()).isEqualTo(5);
        assertThat(stats.sideboard().landCount()).isZero();
        assertThat(stats.sideboard().manaCurve()).isEqualTo(Map.of(1, 2, 2, 3));
        assertThat(stats.sideboard().typeBreakdown()).isEqualTo(Map.of("Instant", 5));
    }

    @Test
    void aDeckWithNoSideboardHasNoSideboardStatistics() {
        assertThat(DeckStatsCalculator.compute(deck(BOLT, MOUNTAIN)).sideboard()).isNull();
    }

    @Test
    void anEntrySavedWithoutABoardCountsAsMainDeck() {
        DeckStatsDto stats = DeckStatsCalculator.compute(deck(
                card("Lightning Bolt", "Instant", 1.0, 4, null, Color.R), MOUNTAIN));

        assertThat(stats.cardCount()).isEqualTo(25);
        assertThat(stats.manaCurve()).containsEntry(1, 4);
    }

    @Test
    void theWarningsAreAboutTheMainDeckOnly() {
        // 20 lands of 25 is a high ratio; a sideboard full of spells mustn't hide it.
        DeckStatsDto stats = DeckStatsCalculator.compute(deck(BOLT, MOUNTAIN,
                card("Duress", "Sorcery", 1.0, 15, CardCategory.SIDEBOARD)));

        assertThat(stats.warnings()).anyMatch(w -> w.startsWith("High land count (20/25)"));
    }
}
