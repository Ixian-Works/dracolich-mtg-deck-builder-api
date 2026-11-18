package dm.dracolich.deckBuilder.core.mapper;

import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import static org.assertj.core.api.Assertions.assertThat;

class DeckCardMapperTest {

    @Test
    void theArtistReachesTheResponse() {
        DeckCardMapper mapper = Mappers.getMapper(DeckCardMapper.class);

        var dto = mapper.toDto(DeckCardEntity.builder().cardId("bolt").artist("Christopher Moeller").build());

        assertThat(dto.getArtist()).isEqualTo("Christopher Moeller");
    }
}
