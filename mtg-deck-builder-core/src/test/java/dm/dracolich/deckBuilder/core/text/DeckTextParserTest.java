package dm.dracolich.deckBuilder.core.text;

import dm.dracolich.deckBuilder.core.text.ParsedLine.Kind;
import dm.dracolich.deckBuilder.core.text.ParsedLine.Section;
import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.deckBuilder.dto.enums.ImportLineReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeckTextParserTest {

    private static List<String> cards(String text) {
        return DeckTextParser.parse(text).stream()
                .filter(ParsedLine::isValid)
                .map(l -> l.count() + " " + l.cardName() + " @" + l.section())
                .toList();
    }

    private static ParsedLine only(String line) {
        List<ParsedLine> parsed = DeckTextParser.parse(line);
        assertThat(parsed).hasSize(1);
        return parsed.getFirst();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4 Lightning Bolt", "4x Lightning Bolt", "4X Lightning Bolt", "4 x Lightning Bolt",
            "  4   Lightning Bolt  ", "4 Lightning Bolt (M21) 162", "4 Lightning Bolt *F*", "4x Lightning Bolt [M21]"})
    void countedCardForms(String line) {
        ParsedLine parsed = only(line);
        assertThat(parsed.kind()).isEqualTo(Kind.CARD);
        assertThat(parsed.count()).isEqualTo(4);
        assertThat(parsed.cardName()).isEqualTo("Lightning Bolt");
        assertThat(parsed.section()).isEqualTo(Section.MAINBOARD);
    }

    @Test
    void aNameAloneIsOneCopy() {
        assertThat(cards("Sol Ring")).containsExactly("1 Sol Ring @MAINBOARD");
    }

    @Test
    void anXThatStartsANameStaysInTheName() {
        assertThat(cards("1 Xenagos, God of Revels")).containsExactly("1 Xenagos, God of Revels @MAINBOARD");
    }

    @Test
    void aSplitCardWithTwoSlashesIsACard() {
        assertThat(cards("1 Fire // Ice\nFire // Ice")).containsExactly("1 Fire // Ice @MAINBOARD", "1 Fire // Ice @MAINBOARD");
    }

    @Test
    void sbPrefixIsASideboardCardAndLeavesTheHeadingAlone() {
        assertThat(cards("Deck\n4 Counterspell\nSB: 2 Negate\nSB:1 Duress\n4 Island"))
                .containsExactly("4 Counterspell @MAINBOARD", "2 Negate @SIDEBOARD", "1 Duress @SIDEBOARD", "4 Island @MAINBOARD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sideboard", "SIDEBOARD", "sideboard:", "Sideboard (15)", "Sideboard [15]", "// Sideboard",
            "//Sideboard", "// Sideboard:", "Side", "// Outside the Game"})
    void sideboardHeadings(String heading) {
        List<ParsedLine> parsed = DeckTextParser.parse(heading + "\n2 Negate");
        assertThat(parsed.getFirst().kind()).isEqualTo(Kind.HEADING);
        assertThat(cards(heading + "\n2 Negate")).containsExactly("2 Negate @SIDEBOARD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Deck", "deck", "Main", "Mainboard", "Main deck", "Deck (60)", "Deck:", "// Deck", "// Mainboard", "// Main"})
    void headingsThatReturnToTheMainDeck(String heading) {
        assertThat(cards("Sideboard\n2 Negate\n" + heading + "\n4 Island"))
                .containsExactly("2 Negate @SIDEBOARD", "4 Island @MAINBOARD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Commander", "Commanders", "commander:", "// Commander", "Commander (1)"})
    void commanderHeadings(String heading) {
        assertThat(cards(heading + "\n1 Krenko, Mob Boss")).containsExactly("1 Krenko, Mob Boss @COMMANDER");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Maybeboard", "Maybe", "Maybe board", "// Maybeboard", "// Maybe"})
    void maybeboardHeadings(String heading) {
        assertThat(cards(heading + "\n1 Shock")).containsExactly("1 Shock @MAYBEBOARD");
    }

    @Test
    void theCommanderFirstThenDeckKeepsTheMainDeckOutOfTheCommandZone() {
        // The design's export order, and the order at least one other product writes. Before this
        // issue every card after "// Deck" stayed a commander.
        assertThat(cards("// Commander\n1 Krenko, Mob Boss\n\n// Deck\n1 Goblin Lackey\n30 Mountain"))
                .containsExactly("1 Krenko, Mob Boss @COMMANDER", "1 Goblin Lackey @MAINBOARD", "30 Mountain @MAINBOARD");
        assertThat(cards("Commander\n1 Krenko, Mob Boss\n\nDeck\n1 Goblin Lackey"))
                .containsExactly("1 Krenko, Mob Boss @COMMANDER", "1 Goblin Lackey @MAINBOARD");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Companion", "About", "Name My deck", "Lands (36)", "Creatures [20]", "// Lands", "Tokens", "Notes:"})
    void aHeadingThatNamesNoBoardIsReportedAndChangesNothing(String heading) {
        List<ParsedLine> parsed = DeckTextParser.parse("Sideboard\n" + heading + "\n2 Negate");
        assertThat(parsed.get(1).kind()).isEqualTo(Kind.UNREAD);
        assertThat(parsed.get(1).reason()).isEqualTo(ImportLineReason.UNKNOWN_HEADING);
        assertThat(parsed.get(1).refusesStrictImport()).isFalse();
        assertThat(cards("Sideboard\n" + heading + "\n2 Negate")).containsExactly("2 Negate @SIDEBOARD");
    }

    @Test
    void aCountedCardNamedLikeAHeadingIsACard() {
        assertThat(cards("1 Commander\n1 About Face")).containsExactly("1 Commander @MAINBOARD", "1 About Face @MAINBOARD");
    }

    @Test
    void aCountThatIsNotValidHasItsReason() {
        assertThat(only("0 Lightning Bolt").reason()).isEqualTo(ImportLineReason.COUNT_NOT_VALID);
        assertThat(only("99999999999 Lightning Bolt").reason()).isEqualTo(ImportLineReason.COUNT_NOT_VALID);
        assertThat(only("0 Lightning Bolt").refusesStrictImport()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "SB:", "SB: 4", "***", "4 ***"})
    void linesThatAreNotCardLines(String line) {
        ParsedLine parsed = only(line);
        assertThat(parsed.kind()).isEqualTo(Kind.UNREAD);
        assertThat(parsed.reason()).isEqualTo(ImportLineReason.NOT_A_CARD_LINE);
        assertThat(parsed.refusesStrictImport()).isTrue();
    }

    @Test
    void blankLinesAreSkippedAndLineNumbersCountThem() {
        List<ParsedLine> parsed = DeckTextParser.parse("\n4 Island\n\n\r\n1 Sol Ring\n");
        assertThat(parsed).extracting(ParsedLine::lineNumber).containsExactly(2, 5);
    }

    @Test
    void whatTheExporterWritesReadsBackAsTheSameList() {
        DeckEntity deck = DeckEntity.builder()
                .commander(new ArrayList<>(List.of(card("Krenko, Mob Boss", 1, null))))
                .cards(new ArrayList<>(List.of(
                        card("Mountain", 30, CardCategory.MAINBOARD),
                        card("Fire // Ice", 1, CardCategory.MAINBOARD),
                        card("Pyroblast", 2, CardCategory.SIDEBOARD),
                        card("Shock", 1, CardCategory.MAYBE_BOARD))))
                .build();

        assertThat(cards(DeckTextExporter.export(deck))).containsExactlyInAnyOrder(
                "1 Krenko, Mob Boss @COMMANDER",
                "30 Mountain @MAINBOARD",
                "1 Fire // Ice @MAINBOARD",
                "2 Pyroblast @SIDEBOARD",
                "1 Shock @MAYBEBOARD");
    }

    private static DeckCardEntity card(String name, int count, CardCategory category) {
        return DeckCardEntity.builder().cardId(name).name(name).count(count).cardCategory(category).build();
    }
}
