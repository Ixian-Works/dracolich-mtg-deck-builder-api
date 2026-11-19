package dm.dracolich.deckBuilder.data.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * One player's favorite of one deck, with when it was made. A document per favorite, not a list on
 * the player: a unique index on the pair makes favoriting twice impossible, the player's favorites
 * sort by time from an index, and favorites can be counted by period for popular-this-week
 * (Ixian-Works/dracolich-mtg-deck-builder-api#31) by a plain aggregation over {@code favorited_at}.
 * Favorites made before this collection existed carry the migration's time.
 */
@Document(collection = FavoriteEntity.COLLECTION)
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FavoriteEntity {
    public static final String COLLECTION = "deck_favorites";
    public static final String USER_ID = "user_id";
    public static final String DECK_ID = "deck_id";
    public static final String FAVORITED_AT = "favorited_at";

    @Id
    private String id;
    @Field(USER_ID)
    private String userId;
    @Field(DECK_ID)
    private String deckId;
    @Field(FAVORITED_AT)
    private Instant favoritedAt;
}
