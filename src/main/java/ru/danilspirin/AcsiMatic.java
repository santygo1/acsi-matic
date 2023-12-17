package ru.danilspirin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.danilspirin.dictionary.SynonymDictionary;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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

    // Нижняя и верхняя граница рейтинга для резервных предложений
    private double reserveSentenceRatingUpperBound, reserveSentenceRatingLowerBound;

    public AcsiMatic(SynonymDictionary synonymDictionary) {
        this.synonymDictionary = synonymDictionary;
    }

    public AbstractingMethod useMaxAbstractSize(int abstractSizeInPercent) {
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
        // Вычисляем допустимую длину исходного реферата и сокращаем длину всех отобранных предложений
        System.out.println(originalSentencesOrderList.size() + " " + maxAbstractSizeInPercent / 100);
        int maxAbstractSentenceCount = (int) (originalSentencesOrderList.size() * ((double) maxAbstractSizeInPercent / 100));
        log.info("Количество предложений выходного реферата ({}% от исходного текста): {}",
                maxAbstractSizeInPercent, maxAbstractSentenceCount);

        // Обновляем рейтинг предложений с учетом сформированного пула слов с рейтингом
        // И вычисляем границы рейтинга для резервных предложений
        // ("резервные предложения" - со средним значением рейтинга)
        double avgRating = 0;
        double maxRating = 0;
        for (Sentence sentence : originalSentencesOrderList) {
            double sentenceRating = sentence.updateRating(wordsPool);

            if (maxRating < sentenceRating) {
                maxRating = sentenceRating;
            }
        }
        avgRating = avgRating / originalSentencesOrderList.size();

        // delta для avg
        double reserveBorderOffset = (maxRating - avgRating) * ((double) (RESERVE_BORDER_OFFSET_IN_PERCENT / 2) / 100);
        reserveSentenceRatingUpperBound = avgRating + reserveBorderOffset;
        reserveSentenceRatingLowerBound = avgRating - reserveBorderOffset;
        log.info("Диапазон главных предложений: ({}, {}]", reserveSentenceRatingUpperBound, maxRating);
        log.info("Диапазон \"резервных\" предложений: [{}, {}]", reserveSentenceRatingLowerBound, reserveSentenceRatingUpperBound);


        // Формируем пул главных предложений (важен порядок)
        // Также все главные предложения будут маркироваться - избыточные или нет
        Set<MarkedSentence> generalSentences = new LinkedHashSet<>();
        // Пул резервных предложений (все те предложения которые попали в серединный рейтинг)
        Set<Sentence> reserveSentences = new LinkedHashSet<>();

        // Классифицируем предложения на главные и резервные
        // Все главные предложения маркируются - избыточные, не избыточные
        classifyAndMarkSentences(generalSentences, reserveSentences);

        // Удаляем избыточность
        Set<Sentence> resultAbstractSentences = removeOversupply(generalSentences, reserveSentences);

        // Выделяем все отобранные слова из оригинального текста с соблюдением последовательности
        List<Sentence> resultAbstract = getHighlightedSentencesFromText(originalSentencesOrderList, resultAbstractSentences);

        // Если получаем реферат больше чем максимально разрешенное количество, то сокращаем результат
        if (resultAbstract.size() < maxAbstractSentenceCount) {
            // Вычисляем с какой периодичностью мы будем брать предложения
            int nth = 100 / maxAbstractSizeInPercent;
            log.info(
                    "Количество отобранных предложений превышает {}% исходного текста.",
                    maxAbstractSizeInPercent
            );
            log.info("Выбираем каждое {} из отобранных", nth);

            resultAbstract = IntStream.range(0, resultAbstract.size())
                    .filter(n -> n % nth == 0)
                    .mapToObj(resultAbstract::get)
                    .toList();
        }

        writeAbstract(output, resultAbstract);
        log.info("Текст был успешно отреферирован.");
    }

    // Классифицируем предложения на главные и резервные
    // и маркирует избыточные предложения которые попадают в класс главных
    private void classifyAndMarkSentences(Set<MarkedSentence> generalSentences, Set<Sentence> reserveSentences) {
        log.info("Классификация предложений - основные и резервные");
        log.info("Определение избыточности основных предложений с помощью словаря синонимов {}", synonymDictionary);
        originalSentencesOrderList.forEach(s -> {
            if (s.rating > reserveSentenceRatingUpperBound) {
                // Если предложение главное
                // На каждое предложение вешаем маркер - избыточное или нет
                var ms = new MarkedSentence(s);

                // Каждое предложение проверяем на избыточность со всеми уже добавленными не избыточными предложениям
                Iterator<MarkedSentence> generalIter = generalSentences.iterator();
                while (!ms.isOversupply && generalIter.hasNext()) {
                    MarkedSentence g = generalIter.next();
                    if (!g.isOversupply) {
                        // Проверяем на избыточность, если хотя бы одно из предложений
                        // избыточно ему устанавливается соответсвующий маркер
                        checkAndSetOversupplyFlags(ms, g);
                    }
                }
                // Добавляем в список основных предложений
                generalSentences.add(ms);
            } else if (s.rating >= reserveSentenceRatingLowerBound) {
                // Если предложение резервное -> добавляем в резерв
                reserveSentences.add(s);
            }
        });
    }

    // Заменяет избыточные предложения предложениями из резерва пока они не закончатся
    private Set<Sentence> removeOversupply(Set<MarkedSentence> general, Set<Sentence> reserve) {
        log.info("Количество главных предложений: {}", general.size());
        log.info("Количество \"резервных\" предложений: {}", reserve.size());

        log.info("Удаление избыточных предложений");
        Iterator<Sentence> reserveIter = reserve.iterator();
        AtomicInteger countReplaced = new AtomicInteger();
        Set<Sentence> withoutOversupply = general.stream()
                .map(s -> {
                    // Если избыточность, то смотрим есть ли резервные
                    // есть -> значит возвращаем
                    // если нет -> возвращаем предложение, несмотря на его избыточность
                    if (s.isOversupply && reserveIter.hasNext()) {
                        countReplaced.getAndIncrement();
                        return reserveIter.next();
                    } else {
                        return s.sentence;
                    }
                })
                .collect(Collectors.toSet());

        log.info("Количество замен избыточных главных предложений на резервные: {}", countReplaced);

        return withoutOversupply;
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

    private List<Sentence> getHighlightedSentencesFromText(List<Sentence> textSentences,
                                                           Set<Sentence> highlightedSentences) {
        return textSentences.stream()
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
