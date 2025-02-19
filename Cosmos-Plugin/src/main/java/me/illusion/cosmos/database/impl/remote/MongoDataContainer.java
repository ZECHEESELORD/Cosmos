package me.illusion.cosmos.database.impl.remote;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import me.illusion.cosmos.CosmosPlugin;
import me.illusion.cosmos.database.CosmosDataContainer;
import me.illusion.cosmos.serialization.CosmosSerializer;
import me.illusion.cosmos.template.TemplatedArea;
import org.bson.Document;
import org.bson.types.Binary;
import org.bukkit.configuration.ConfigurationSection;

public class MongoDataContainer implements CosmosDataContainer {

    private final List<CompletableFuture<?>> futures = new ArrayList<>();
    private MongoClient mongoClient;
    private MongoCollection<Document> templatesCollection;

    private final CosmosPlugin plugin;

    public MongoDataContainer(CosmosPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public CompletableFuture<Boolean> enable(ConfigurationSection section) {
        return CompletableFuture.supplyAsync(() -> {
            String connectionString = section.getString("connection-string");

            if (connectionString == null) {
                String ip = section.getString("ip");
                int port = section.getInt("port");
                String authsource = section.getString("auth-source");
                String username = section.getString("username");
                String password = section.getString("password");
                boolean ssl = section.getBoolean("ssl", false);

                connectionString = createConnectionString(ip, port, authsource, username, password, ssl);
            }

            String database = section.getString("database", "cosmos");
            String collectionName = section.getString("collection", "cosmos_templates");


            try {
                mongoClient = MongoClients.create(new ConnectionString(connectionString));
                templatesCollection = mongoClient.getDatabase(database).getCollection(collectionName); // if the collection doesn't exist, it will be created
                return true;
            } catch (Exception expected) { // catching MongoException doesn't work for some reason
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<TemplatedArea> fetchTemplate(String name) {
        CompletableFuture<TemplatedArea> future = new CompletableFuture<>();
        CompletableFuture<Void> fetch = CompletableFuture.runAsync(() -> {
            Document document = templatesCollection.find(new Document("name", name)).first();
            if (document == null) {
                future.complete(null);
                return;
            }

            byte[] data = document.get("data", Binary.class).getData();
            String serializer = document.getString("serializer");

            CosmosSerializer cosmosSerializer = plugin.getSerializerRegistry().get(serializer);

            if (cosmosSerializer == null) {
                plugin.getLogger().warning("Could not find serializer " + serializer + " for template " + name);
                future.complete(null);
                return;
            }

            cosmosSerializer.deserialize(data).thenAccept(future::complete);
        });

        registerFuture(fetch);
        return registerFuture(future);
    }

    @Override
    public CompletableFuture<Void> saveTemplate(String name, TemplatedArea area) {
        CompletableFuture<Void> future = area.getSerializer().serialize(area).thenAccept(binary -> {
            Document document = new Document("name", name)
                .append("data", binary)
                .append("serializer", area.getSerializer().getName());

            templatesCollection.insertOne(document);
        });

        return registerFuture(future);
    }

    @Override
    public CompletableFuture<Void> deleteTemplate(String name) {
        return registerFuture(CompletableFuture.runAsync(() -> templatesCollection.deleteOne(new Document("name", name))));
    }

    @Override
    public CompletableFuture<Void> flush() {
        if (futures.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }

        System.out.println("Flushing " + futures.size() + " futures");
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    @Override
    public String getName() {
        return "mongodb";
    }

    @Override
    public CompletableFuture<Collection<String>> fetchAllTemplates() {
        CompletableFuture<Collection<String>> future = CompletableFuture.supplyAsync(() -> {
            List<String> templates = new ArrayList<>();

            templatesCollection.find().forEach(document -> templates.add(document.getString("name")));

            return templates;
        });

        return registerFuture(future);
    }

    private <T> CompletableFuture<T> registerFuture(CompletableFuture<T> future) {
        future.thenRun(() -> futures.remove(future));
        future.exceptionally(throwable -> {
            futures.remove(future);
            throwable.printStackTrace();
            return null;
        });

        futures.add(future);

        return future;
    }


    @Override
    public boolean requiresCredentials() {
        return true;
    }

    private String createConnectionString(String ip, int port, String authsource, String username, String password, boolean ssl) {
        StringBuilder builder = new StringBuilder();
        builder.append("mongodb://");
        if (username != null && !username.isEmpty()) {
            builder.append(username);
            if (password != null && !password.isEmpty()) {
                builder.append(":").append(password);
            }
            builder.append("@");
        }

        builder.append(ip).append(":").append(port);

        if (authsource != null && !authsource.isEmpty()) {
            builder.append("/?authSource=").append(authsource);
        }

        if (ssl) {
            builder.append("&ssl=true");
        }

        return builder.toString();
    }
}
