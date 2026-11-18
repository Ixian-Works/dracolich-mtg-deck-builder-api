package dm.dracolich.deckBuilder.data.entity;

import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.mtgLibrary.dto.enums.Color;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.Map;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class DeckCardEntity {
    @Field("card_id")
    private String cardId; //FK -> dracolich-mtg-library-api
    private String name;
    @Field("mana_cost")
    private String manaCost;
    private Double cmc; // mana curve
    private Set<Color> colors;
    @Field("color_identity")
    private Set<Color> colorIdentity; // null on entries saved before it was snapshotted
    @Field("type_line")
    private String typeLine;
    @Field("image_uri")
    private Map<String, String> imageUri; // user can choose which version to use, otherwise it's the default one
    // Who painted the snapshot's art, for crediting it wherever the art is shown. Null on cards
    // saved before it was snapshotted; adding the card to that board again fills it in.
    private String artist;
    private Integer count; // quantity on deck - must be 1 for commander decks
    @Field("card_category")
    private CardCategory cardCategory; // null if commander

}
