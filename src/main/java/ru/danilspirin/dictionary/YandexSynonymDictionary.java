package ru.danilspirin.dictionary;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ru.danilspirin.cache.Cache;
import ru.danilspirin.cache.MapCache;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;

/**
 * Реализация словаря синонимов слова, основанная на сервисе Яндекс.Словарь.
 * <p>
 * Работает только с простыми словами на русском языке.
 * Под простыми словами понимаются слова, в которых нет никаких символов.
 * Если попытаться передать слова на английском или других языках - для каждого слова
 * будет возвращать пустой список синонимов.
 * Поддерживает кеширование.
 * Достает рекурсивно все синонимы для слова из ответа и сохраняет их в кеш,
 * после чего повторные запросы с данным словом достаются из кеша.
 * <p>
 * Реализовано с помощью сервиса <a href="https://tech.yandex.ru/dictionary">«Яндекс.Словарь»</a>
 */
public class YandexSynonymDictionary implements SynonymDictionary {

    private static final String LOOKUP_REQUEST_TEMPLATE = "https://dictionary.yandex.net/api/v1/dicservice.json/" +
            "lookup?" +
            "key=%s" + "&" +
            "text=%s" + "&" +
            "lang=ru-ru";
    private static final String INVALID_SYMBOLS_PATTERN = "[А-Яа-я]+";
    private final HttpClient client;
    private final String apiKey;

    private final Cache<String, Set<String>> cache;


    public YandexSynonymDictionary(HttpClient client, String apiKey) {
        this.client = client;
        this.apiKey = apiKey;
        cache = new MapCache<>();
    }

    public YandexSynonymDictionary(HttpClient client, String apiKey, Cache<String, Set<String>> cache){
        this.client = client;
        this.apiKey = apiKey;
        this.cache = cache;
    }

    @Override
    public boolean areSynonyms(String source, String destination) {
        checkCorrectWord(source);
        checkCorrectWord(destination);

        // если слова полностью одинаковые
        if (source.equals(destination)) {
            return true;
        }

        // Получаем синонимы для первого и второго слов
        Set<String> sourceSynonyms = getSynonyms(source);
        Set<String> destinationSynonyms = getSynonyms(destination);

        // если второе слово есть в синонимах первого
        for (String synonym : sourceSynonyms) {
            if (synonym.equals(destination)) {
                return true;
            }
        }

        // если в синонимах второго слова есть первое
        for (String synonym: destinationSynonyms){
            if (synonym.equals(source)){
                return true;
            }
        }

        return false;
    }

    @Override
    public Set<String> getSynonyms(String word) {
        checkCorrectWord(word);
        // Пытаемся достать из кеша
        Set<String> synonyms = cache.get(word);

        // Если в кеше нет то делаем запрос в сервис
        if (synonyms == null) {
            synonyms = getSynonymsFromYandexAndCached(word);
        }


        return synonyms;
    }

    private void checkCorrectWord(String word) {


        if (word == null || word.isEmpty()) {
            throw new IllegalArgumentException("Ожидалось непустое слово.");
        }

        if (!word.matches(INVALID_SYMBOLS_PATTERN)) {
            throw new IllegalArgumentException("Слово не должно содержать никаких символов, кроме букв кириллицы.");
        }
    }


    // Делает запрос в сервис Яндекс.Словарь и кеширует полученные синонимы
    private Set<String> getSynonymsFromYandexAndCached(String word) {
        // Отправляем запрос на Яндекс.Словарь и парсим json в множество синонимов
        Set<String> synonyms = parseResponseToSynonymsSet(
                getResponseFromYandex(word)
        );

        // Сохраняем в кеш результат синонимов для слова
        cache.put(word, synonyms);

        return synonyms;
    }


    private HttpResponse<String> getResponseFromYandex(String word) {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(String.format(
                        LOOKUP_REQUEST_TEMPLATE,
                        apiKey,
                        word
                )))
                .build();
        try {
            return client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Ошибка соединения");
        }
    }

    private Set<String> parseResponseToSynonymsSet(HttpResponse<String> response) {

        Set<String> synonyms = new HashSet<>();

        JsonArray defArrayJson = new Gson().fromJson(response.body(), JsonObject.class)
                .getAsJsonArray("def");
        if (defArrayJson.isEmpty()) {
            return synonyms;
        }

        JsonArray synonymsJson = defArrayJson.get(0).getAsJsonObject()
                .getAsJsonArray("tr");

        for (int i = 0; i < synonymsJson.size(); i++) {
            JsonObject synonymJsonObject = synonymsJson.get(i).getAsJsonObject();
            String synonym = synonymJsonObject.get("text").getAsString();

            // если синоним не составной
            if (!synonym.contains(" ")) {
                synonyms.add(synonymJsonObject.get("text").getAsString());
            }

            // Рекурсивно добавляем все внутренние синонимы
            JsonArray innerSynonymsJsonArray = synonymJsonObject.getAsJsonArray("syn");
            if (innerSynonymsJsonArray != null) {
                for (int j = 0; j < innerSynonymsJsonArray.size(); j++) {
                    String innerSynonym = innerSynonymsJsonArray.get(j).getAsJsonObject().get("text").getAsString();

                    // Если внутренний синоним не составной
                    if (!synonym.contains(" ")) {
                        synonyms.add(innerSynonym);
                    }
                }
            }
        }
        return synonyms;
    }

    @Override
    public String toString() {
        return "Яндекс.Словарь";
    }
}
