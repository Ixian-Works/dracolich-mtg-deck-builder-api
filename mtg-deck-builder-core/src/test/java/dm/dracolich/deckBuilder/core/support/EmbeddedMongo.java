package dm.dracolich.deckBuilder.core.support;

import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoClients;
import de.flapdoodle.embed.mongo.distribution.Version;
import de.flapdoodle.embed.mongo.transitions.Mongod;
import de.flapdoodle.embed.mongo.transitions.RunningMongodProcess;
import de.flapdoodle.commons.reverse.TransitionWalker;
import de.flapdoodle.commons.reverse.transitions.Start;
import de.flapdoodle.embed.process.io.ProcessOutput;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;

/**
 * One real mongod for the whole test run, started without Docker. The first run downloads the
 * MongoDB binary into ~/.embedmongo; later runs reuse it. Each call gets a fresh database.
 */
public final class EmbeddedMongo {

    private static TransitionWalker.ReachedState<RunningMongodProcess> process;
    private static int databaseCounter;

    private EmbeddedMongo() {
    }

    /** A Spring context with the deck repositories, on a fresh database. Close it after the test. */
    public static synchronized AnnotationConfigApplicationContext freshContext() {
        if (process == null) {
            process = Mongod.instance()
                    .withProcessOutput(Start.to(ProcessOutput.class).initializedWith(ProcessOutput.silent()))
                    .start(Version.Main.V7_0);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> process.close()));
        }
        var address = process.current().getServerAddress();
        Config.uri = "mongodb://" + address.getHost() + ":" + address.getPort();
        Config.database = "deck_builder_test_" + (++databaseCounter);
        return new AnnotationConfigApplicationContext(Config.class);
    }

    @Configuration
    @EnableReactiveMongoRepositories(basePackageClasses = DeckRepository.class)
    static class Config {
        static String uri;
        static String database;

        @Bean(destroyMethod = "close")
        MongoClient mongoClient() {
            return MongoClients.create(uri);
        }

        @Bean
        ReactiveMongoTemplate reactiveMongoTemplate(MongoClient client) {
            return new ReactiveMongoTemplate(client, database);
        }
    }
}
