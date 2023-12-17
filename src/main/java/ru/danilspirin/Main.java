package ru.danilspirin;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import ru.danilspirin.cache.RedisCache;
import ru.danilspirin.dictionary.SynonymDictionary;
import ru.danilspirin.dictionary.YandexSynonymDictionary;

import java.io.*;
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

        InputStream input = new FileInputStream("input-file.txt");
        OutputStream output = new FileOutputStream("abstract-file.txt");

        new AcsiMatic(wordSynonymAnalyzer)
                .useMaxAbstractSize(50)
                .process(input, output);

        redis.shutdown();
    }
}