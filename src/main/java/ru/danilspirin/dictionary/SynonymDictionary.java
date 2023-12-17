package ru.danilspirin.dictionary;

import java.util.Set;

public interface SynonymDictionary {
    boolean areSynonyms(String first, String second);
    Set<String> getSynonyms(String word);
}
