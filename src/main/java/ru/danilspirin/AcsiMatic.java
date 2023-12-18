package ru.danilspirin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.error.Mark;
import ru.danilspirin.dictionary.SynonymDictionary;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class AcsiMatic implements AbstractingMethod {

    private final Logger log = LoggerFactory.getLogger(AcsiMatic.class);

    private static final int DEFAULT_ABSTRACT_SIZE_IN_PERCENT = 10;
    private static final int MAX_SENTENCE_OVERSUPPLY_IN_PERCENT = 25;
    private static final int RESERVE_BORDER_OFFSET_IN_PERCENT = 40;
    private final SynonymDictionary synonymDictionary;
    private final WordsPool wordsPool = new WordsPool();
    private final List<Sentence> originalSentencesOrderList = new ArrayList<>();

    // Максимальный размер реферата от исходного текста
    private int maxAbstractSizeInPercent = DEFAULT_ABSTRACT_SIZE_IN_PERCENT;
    private int maxAbstractSentenceCount;

    // Нижняя и верхняя граница рейтинга для резервных предложений
    private double reserveSentenceRatingUpperBound, reserveSentenceRatingLowerBound;

    public AcsiMatic(SynonymDictionary synonymDictionary) {
        this.synonymDictionary = synonymDictionary;
    }

    public AbstractingMethod useAbstractLimit(int abstractSizeInPercent) {
        if (0 < abstractSizeInPercent && abstractSizeInPercent < 100) {
            this.maxAbstractSizeInPercent = abstractSizeInPercent;
            return this;
        } else {
            throw new IllegalArgumentException("abstractSizeInPercent должен находиться в интервале (0,100)");
        }
    }

    @Override
    public void process(InputStream input, OutputStream output) {
        log.info("Начало реферирования...");
        readText(input);

        maxAbstractSentenceCount = (int) (originalSentencesOrderList.size() * ((double) maxAbstractSizeInPercent / 100));
        log.info("Ожидаемое количество предложений выходного реферата ({}% от исходного текста): {}",
                maxAbstractSizeInPercent, maxAbstractSentenceCount);

        // Обновляем рейтинг предложений с учетом сформированного пула слов с рейтингом
        // И вычисляем границы рейтинга для резервных предложений
        // ("резервные предложения" - со средним значением рейтинга)
        double avgRating = 0;
        double maxRating = 0;
        for (Sentence sentence : originalSentencesOrderList) {
            double sentenceRating = sentence.updateRating(wordsPool);
            avgRating+=sentenceRating;
            if (maxRating < sentenceRating) {
                maxRating = sentenceRating;
            }
        }

        avgRating = avgRating / originalSentencesOrderList.size();
        // delta для avg
        double reserveBorderOffset = (maxRating - avgRating) * ((double) (RESERVE_BORDER_OFFSET_IN_PERCENT / 2) / 100);
        reserveSentenceRatingUpperBound = avgRating + reserveBorderOffset;
        reserveSentenceRatingLowerBound = avgRating - reserveBorderOffset;
        // Если все предложения равны между собой смещаем границы вниз
        if (avgRating == maxRating){
            log.info("Все предложения равны по рейтингу - средняя граница была смещена вниз!");
            avgRating = maxRating/2;
            reserveBorderOffset = (maxRating - avgRating) * ((double) (RESERVE_BORDER_OFFSET_IN_PERCENT / 2) / 100);;
            reserveSentenceRatingUpperBound = avgRating + reserveBorderOffset;
            reserveSentenceRatingLowerBound = avgRating - reserveBorderOffset;
        }

        log.info("\tДиапазон главных предложений: ({}, {}]", reserveSentenceRatingUpperBound, maxRating);
        log.info("\tСреднее значение рейтинга предложений: {}", avgRating);
        log.info("\tДиапазон \"резервных\" предложений: [{}, {}]", reserveSentenceRatingLowerBound, reserveSentenceRatingUpperBound);


        // Формируем пул главных предложений (важен порядок)
        // Также все главные предложения будут маркироваться - избыточные или нет
        Set<Sentence> generalSentences = new HashSet<>();
        // Пул резервных предложений (все те предложения которые попали в серединный рейтинг)
        Set<Sentence> reserveSentences = new HashSet<>();

        // Классифицируем предложения на главные и резервные
        classifySentences(generalSentences, reserveSentences);

        Set<Sentence> resultAbstractSentences = generalSentences;
        // Если количество основных предложений больше максимального количества предложений реферата
        if (resultAbstractSentences.size() > maxAbstractSentenceCount) {
//             Устраняем избыточность
            resultAbstractSentences = removeOversupplySentences(
                    markOversupplySentences(generalSentences),
                    reserveSentences
            );

            // Если по прежнему больше обрезаем количество предложений до максимального
            if (resultAbstractSentences.size() > maxAbstractSentenceCount) {
                resultAbstractSentences = resultAbstractSentences.stream()
                        .limit(maxAbstractSentenceCount)
                        .collect(Collectors.toSet());
            }
        }else if (resultAbstractSentences.size() < maxAbstractSentenceCount) {
            // Если предложений меньше, чем максимально разрешенное количество предложений добираем из резерва
            resultAbstractSentences = mergeGeneralAndReserveForMaxSize(generalSentences, reserveSentences);
        } else {
            // иначе ничего не делаем, наш реферат состоит из основных предложений
            log.info("Реферат полностью состоит из главных предложений!");
        }

        // Выделяем предложения из оригинала для
        List<Sentence> resultAbstract = getHighlightedSentencesFromOriginal(resultAbstractSentences);
        writeAbstract(output, resultAbstract);
        log.info("Текст был успешно отреферирован.");
    }

    private void classifySentences(Set<Sentence> general, Set<Sentence> reserve) {
        log.info("Классификация предложений на основные и резервные...");
        originalSentencesOrderList.forEach(s -> {
            if (s.rating > reserveSentenceRatingUpperBound) {
                // Если текущее предложение выше верхней средней границы, определяем предложение как основное
                general.add(s);
            } else if (s.rating >= reserveSentenceRatingLowerBound) {
                // Если текущее предложение ниже верхней средней границы, но выше нижней средней границы
                // определяем как резервное
                reserve.add(s);
            }
            // иначе предложение исключается
        });
        log.info("\t Количество основных предложений: {}", general.size());
        log.info("\t Количество резервных предложений: {}", reserve.size());
    }


    // Определяет избыточные главные предложения и заменяет их на резервные
    // Если резервные предложения закончились, просто удаляет избыточность
    private Set<Sentence> removeOversupplySentences(Set<MarkedSentence> checkedOversupplySentences, Set<Sentence> reserve) {
        log.info("Удаление избыточных предложений...");
        Iterator<Sentence> reserveIter = reserve.iterator();

        int countReplaced = 0;
        int countRemoved = 0;
        Set<Sentence> withoutOversupply = new HashSet<>();
        for (MarkedSentence s : checkedOversupplySentences) {
            // Если избыточное предложение
            if (s.isOversupply) {
                // И есть резервное для замены
                if (reserveIter.hasNext()) {
                    countReplaced++;
                    // заменяем избыточное на резервное
                    withoutOversupply.add(reserveIter.next());
                } else {
                    // Если резервных нет значит удаляем избыточное
                    countRemoved++;
                }
            } else {
                // Если не избыточное, то пропускаем для реферата
                withoutOversupply.add(s.sentence);
            }
        }
        log.info("\tКоличество предложений после удаления избыточности: {}", withoutOversupply.size());
        log.info("\tЗамен избыточных на резервные: {}", countReplaced);
        log.info("\tУдалений избыточных предложений: {}", countRemoved);

        return withoutOversupply;
    }

    // Ищет и маркирует избыточные предложения
    private Set<MarkedSentence> markOversupplySentences(Set<Sentence> sentences){
        log.info("Поиск избыточных главных предложений...");
        log.info("\tМаксимальный процент избыточности: {}% синонимов", MAX_SENTENCE_OVERSUPPLY_IN_PERCENT);
        log.info("\tСловарь синонимов: {}", synonymDictionary);
        Set<MarkedSentence> markedOversupplySentences = new HashSet<>();
        AtomicInteger oversupplyCount = new AtomicInteger();
        sentences.forEach(s -> {
            var ms = new MarkedSentence(s);

            // Каждое предложение проверяем на избыточность со всеми уже добавленными не избыточными предложениям
            Iterator<MarkedSentence> generalIter = markedOversupplySentences.iterator();
            while (!ms.isOversupply && generalIter.hasNext()) {
                MarkedSentence g = generalIter.next();
                if (!g.isOversupply) {
                    // Проверяем на избыточность, если хотя бы одно из предложений
                    // избыточно ему устанавливается соответсвующий маркер
                    checkAndSetOversupplyFlags(ms, g);
                    if (ms.isOversupply) {
                        oversupplyCount.getAndIncrement();
                    }
                    if (g.isOversupply){
                        oversupplyCount.getAndIncrement();
                    }
                }
            }
            markedOversupplySentences.add(ms);
        });
        log.info("\tБыло найдено избыточных предложений: {}", oversupplyCount.get());

        return markedOversupplySentences;
    }

    // Проверяет избыточность двух предложений и если
    // хотя бы одно из предложений избыточно ему устанавливается флаг
    private void checkAndSetOversupplyFlags(MarkedSentence first, MarkedSentence second) {
        // Если одно и то же предложение - ничего не делаем
        if (first == second) {
            return;
        }

        // Количество синонимов для слов из первого предложения, которые были найдены во втором
        int countSynonymsInSentence = 0;
        // Перебираем все слова из первого предложения
        // И делаем сопоставление со словами из второго предложения
        // Если слово из первого является синонимом слова во втором предложении -
        // увеличиваем счетчик
        for (String sourceWord : first.sentence.words) {
            for (String destinationWord : second.sentence.words) {
                if (synonymDictionary.areSynonyms(sourceWord, destinationWord)) {
                    countSynonymsInSentence++;
                }
            }
        }

        // Определяем процент синонимов в первом и втором предложениях,
        // маркируем избыточным если превысило допустимый процент
        int firstSynonymsPercent = (int) ((double) countSynonymsInSentence / first.sentence.words.size() * 100);
        first.isOversupply = firstSynonymsPercent > MAX_SENTENCE_OVERSUPPLY_IN_PERCENT;
        int secondSynonymsPercent = (int) ((double) countSynonymsInSentence / second.sentence.words.size() * 100);
        second.isOversupply = secondSynonymsPercent > MAX_SENTENCE_OVERSUPPLY_IN_PERCENT;
    }

    // Добирает недостающие предложения для основных предложений из резерва с учетом максимального количества
    // предложений в реферате
    private Set<Sentence> mergeGeneralAndReserveForMaxSize(Set<Sentence> general, Set<Sentence> reserve){
        log.info("Добор предложений из резерва...");
        Set<Sentence> result = new HashSet<>(general);
        Iterator<Sentence> reserveIter = reserve.iterator();
        int reserveCount = 0;
        while (result.size() < maxAbstractSentenceCount && reserveIter.hasNext()){
            reserveCount++;
            result.add(reserveIter.next());
        }
        log.info("\tКоличество добранных предложений из резервных {}", reserveCount);
        log.info("\tКоличество предложений после добора: {}", result.size());
        return result;
    }

    private List<Sentence> getHighlightedSentencesFromOriginal(Set<Sentence> highlightedSentences) {
        return originalSentencesOrderList.stream()
                .filter(highlightedSentences::contains)
                .toList();
    }


    private void readText(InputStream input) {
        // Считываем предложения из текста и формируем пул всех слов и список предложений в оригинальном порядке
        Scanner sc = new Scanner(input).useDelimiter("\\s*[.!?]");
        while (sc.hasNext()) {
            String sentenceOriginal = sc.next();
            // Пропускаем если предложение пустое
            if (sentenceOriginal.trim().equals(""))
                continue;

            String endSymbol = sc.findInLine("[.!?]");

            // Создаем и добавляем предложение в пул всех предложений
            Sentence sentence = new Sentence(sentenceOriginal + (endSymbol != null ? endSymbol : ""));
            originalSentencesOrderList.add(sentence);

            // Добавляем каждое слово из предложения в пул, или увеличиваем счетчик вхождения слова
            for (String word : sentence.words) {
                wordsPool.addWord(word);
            }
        }
        log.info("Количество предложений входного текста: {}", originalSentencesOrderList.size());
    }

    private void writeAbstract(OutputStream output, List<Sentence> resultAbstract) {
        log.debug("Предложения получившегося реферата: ");
        for (Sentence sentence : resultAbstract) {
            log.debug("{}", sentence);
        }

        try (OutputStreamWriter nFile = new OutputStreamWriter(output);) {
            for (Sentence sentence : resultAbstract) {
                nFile.write(sentence.original);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static class WordsPool {
        private int globalWordsCount;
        private final Map<String, Double> wordPointMap = new HashMap<>();

        private void addWord(String word) {
            if (wordPointMap.containsKey(word)) {
                wordPointMap.put(word, wordPointMap.get(word) + 1);
            } else {
                wordPointMap.put(word, 1.0);
            }
            globalWordsCount++;
        }

        @Override
        public String toString() {
            return "WordsPool{" +
                    "globalWordsCount=" + globalWordsCount +
                    ", wordPointMap=" + wordPointMap +
                    '}';
        }
    }

    private static class MarkedSentence {
        private boolean isOversupply = false;
        private final Sentence sentence;

        MarkedSentence(Sentence sentence) {
            this.sentence = sentence;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MarkedSentence that = (MarkedSentence) o;
            return Objects.equals(sentence, that.sentence);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sentence);
        }

        @Override
        public String toString() {
            return "MarkedSentence{" +
                    "isOversupply=" + isOversupply +
                    ", sentence=" + sentence +
                    '}';
        }
    }

    private static class Sentence implements Comparable<Sentence> {
        final String original;
        final Set<String> words;
        double rating = 0.0;

        int originalWordsCount;

        Sentence(String sentence) {
            this.original = sentence;
            List<String> allWords = Arrays.stream(sentence.split("[^А-Яа-я]"))
                    .filter(w -> !w.equals(""))
                    .map(String::toLowerCase).toList();
            originalWordsCount = allWords.size();
            words = new HashSet<>(allWords);
        }

        private double updateRating(WordsPool wordsPool) {
            for (String word : words) {
                rating += wordsPool.wordPointMap.get(word);
            }
            rating = rating / wordsPool.globalWordsCount;
            return rating;
        }

        @Override
        public String toString() {
            return "Sentence{" +
                    "original='" + original.trim() + '\'' +
                    ", rating=" + rating +
                    '}';
        }

        @Override
        public int compareTo(Sentence o) {
            return Double.compare(rating, o.rating);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Sentence sentence = (Sentence) o;
            return Objects.equals(original, sentence.original);
        }

        @Override
        public int hashCode() {
            return Objects.hash(original);
        }
    }
}
