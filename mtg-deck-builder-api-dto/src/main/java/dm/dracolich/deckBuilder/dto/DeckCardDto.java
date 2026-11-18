package dm.dracolich.deckBuilder.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.mtgLibrary.dto.enums.Color;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeckCardDto {
    @JsonProperty("card_id")
    private String cardId;
    private String name;
    @JsonProperty("mana_cost")
    private String manaCost;
    private Double cmc;
    private Set<Color> colors;
    @JsonProperty("color_identity")
    private Set<Color> colorIdentity;
    @JsonProperty("type_line")
    private String typeLine;
    @JsonProperty("image_uri")
    private Map<String, String> imageUri;
    /** Who painted the card's art, for crediting it wherever the art is shown. */
    private String artist;
    private Integer count;
    @JsonProperty("card_category")
    private CardCategory cardCategory;
}
