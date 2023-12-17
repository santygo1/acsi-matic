package ru.danilspirin;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.danilspirin.dictionary.YandexSynonymDictionary;

import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.*;

class YandexSynonymDictionaryTest {

    private static YandexSynonymDictionary synonymDictionary;

    @BeforeAll
    public static void initAnalyzer() {
        HttpClient client = HttpClient.newHttpClient();
        synonymDictionary = new YandexSynonymDictionary(
                client,
                System.getenv("YANDEX_TOKEN")
        );
    }

    @Test
    public void givenSimpleWordWithSynonyms_whenGetSynonyms_thenSynonymsIsNotEmpty() {
        String word = "Школа";
        assertFalse(synonymDictionary.getSynonyms(word).isEmpty());
    }

    @Test
    public void givenHardWordWithoutSynonyms_whenGetSynonyms_thenSynonymsIsEmpty() {
        String word = "квазирефирирование";
        assertTrue(synonymDictionary.getSynonyms(word).isEmpty());
    }

    @Test
    public void givenTwoSynonyms_whenAreSynonyms_thenTrue() {
        String source = "психология";
        String destination = "этнопсихология";
        assertTrue(synonymDictionary.areSynonyms(source, destination));
    }

    @Test
    public void givenTwoNoSynonyms_whenAreSynonyms_thenFalse() {
        String source = "слово";
        String destination = "стол";

        assertFalse(synonymDictionary.areSynonyms(source, destination));
    }

    @Test
    public void givenWordWithHyphen_whenAreSynonymsOrGetSynonyms_thenIllegalArgumentException() {
        String wordWithHyphen = "стоп-слово";
        String normalWord = "слово";

        assertThrows(IllegalArgumentException.class,
                () -> synonymDictionary.areSynonyms(wordWithHyphen, normalWord)
        );

        assertThrows(IllegalArgumentException.class,
                () -> synonymDictionary.getSynonyms(wordWithHyphen)
        );
    }

    @Test
    public void givenNullOrEmptyWord_whenGetSynonyms_thenIllegalArgumentException() {
        String nullWord = null;
        assertThrows(IllegalArgumentException.class,
                () -> synonymDictionary.getSynonyms(nullWord)
        );

        String firstEmptyWord = "";
        String secondEmptyWord = "";
        assertThrows(IllegalArgumentException.class,
                () -> synonymDictionary.areSynonyms(firstEmptyWord, secondEmptyWord)
        );
    }

    @Test
    public void givenTwoEqualsWords_whenAreSynonyms_thenTrue(){
        String source = "слово";
        String destination = "слово";

        assertTrue(synonymDictionary.areSynonyms(source, destination));
    }


}