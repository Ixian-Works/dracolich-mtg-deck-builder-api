package dm.dracolich.deckBuilder.data.repository;

import dm.dracolich.deckBuilder.data.entity.FavoriteEntity;
import dm.dracolich.deckBuilder.data.repository.custom.FavoriteCustomRepository;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

public interface FavoriteRepository extends ReactiveMongoRepository<FavoriteEntity, String>,
        FavoriteCustomRepository {
}
