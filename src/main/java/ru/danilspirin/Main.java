package ru.danilspirin;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import ru.danilspirin.cache.RedisCache;
import ru.danilspirin.dictionary.SynonymDictionary;
import ru.danilspirin.dictionary.YandexSynonymDictionary;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.http.HttpClient;

public class Main {
    public static void main(String[] args) throws FileNotFoundException {
        HttpClient client = HttpClient.newHttpClient();
        RedissonClient redis = Redisson.create();
        String yandexAccessToken = System.getenv("YANDEX_TOKEN");
        SynonymDictionary wordSynonymAnalyzer = new YandexSynonymDictionary(
                client,
                yandexAccessToken,
                new RedisCache<>(redis, "synonyms")
        );


        InputStream input = Main.class.getResourceAsStream("/example.txt");
        OutputStream output = System.out;

        new AcsiMatic(wordSynonymAnalyzer)
                .useAbstractLimit(20)
                .process(input, output);

        redis.shutdown();
    }
}