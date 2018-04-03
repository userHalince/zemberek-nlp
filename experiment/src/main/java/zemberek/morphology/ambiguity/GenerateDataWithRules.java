package zemberek.morphology.ambiguity;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import zemberek.core.logging.Log;
import zemberek.langid.LanguageIdentifier;
import zemberek.morphology._analyzer._TurkishMorphologicalAnalyzer;
import zemberek.morphology._analyzer._WordAnalysis;
import zemberek.morphology.ambiguity.RuleBasedDisambiguator.AmbiguityAnalysis;
import zemberek.morphology.ambiguity.RuleBasedDisambiguator.AnalysisDecision;
import zemberek.morphology.ambiguity.RuleBasedDisambiguator.Decision;
import zemberek.morphology.ambiguity.RuleBasedDisambiguator.ResultSentence;
import zemberek.morphology.lexicon.RootLexicon;
import zemberek.morphology.lexicon.tr.TurkishDictionaryLoader;
import zemberek.tokenization.TurkishSentenceExtractor;

public class GenerateDataWithRules {

  private static LanguageIdentifier identifier;
  private static RuleBasedDisambiguator ruleBasedDisambiguator;

  private GenerateDataWithRules() throws IOException {
    identifier = LanguageIdentifier.fromInternalModelGroup("tr_group");
    RootLexicon lexicon = TurkishDictionaryLoader.loadDefaultDictionaries();
    _TurkishMorphologicalAnalyzer analyzer = new _TurkishMorphologicalAnalyzer(lexicon);
    ruleBasedDisambiguator = new RuleBasedDisambiguator(analyzer);
  }

  private static Collection<Predicate<_WordAnalysis>> acceptWordPredicates = new ArrayList<>();
  private static Collection<Predicate<String>> ignoreSentencePredicates = new ArrayList<>();

  public static void main(String[] args) throws IOException {
    //Path p = Paths.get("/home/ahmetaa/data/zemberek/data/corpora/www.aljazeera.com.tr");
    //Path p = Paths.get("/home/ahmetaa/data/zemberek/data/corpora/open-subtitles");
    Path p = Paths.get("/media/aaa/Data/corpora/final/open-subtitles");
    Path outRoot = Paths.get("data/ambiguity");
    Files.createDirectories(outRoot);

    acceptWordPredicates.add(maxAnalysisCount(4));
    acceptWordPredicates.add(hasAnalysis());
    ignoreSentencePredicates.add(contains("\""));
    ignoreSentencePredicates.add(contains("…"));
    ignoreSentencePredicates.add(probablyNotTurkish());
    ignoreSentencePredicates.add(longSentence(15));

    new GenerateDataWithRules()
        .extractData(p, outRoot, 2000);
  }

  private static Predicate<_WordAnalysis> hasAnalysis() {
    return _WordAnalysis::isCorrect;
  }

  private static Predicate<_WordAnalysis> maxAnalysisCount(int i) {
    return p -> p.analysisCount() <= i;
  }

  private static Predicate<String> contains(String s) {
    return p -> p.contains(s);
  }

  private static Predicate<String> probablyNotTurkish() {
    return p -> !identifier.identify(p).equals("tr");
  }

  private static Predicate<String> longSentence(int tokenCount) {
    return p -> p.split("[ ]+").length > tokenCount;
  }

  private void extractData(Path p, Path outRoot, int resultLimit)
      throws IOException {
    List<Path> files = Files.walk(p, 1).filter(s -> s.toFile().isFile()).collect(Collectors.toList());

    BatchResult result = new BatchResult();

    int i = 0;

    for (Path file : files) {
      collect(result, file, resultLimit);
      i++;
      Log.info("%d of %d", i, files.size());
      if (resultLimit > 0 && result.results.size() > resultLimit) {
        break;
      }
    }

    String s = p.toFile().getName();

    Log.info("Saving.");
    Path out = outRoot.resolve(s + "-rule-result.txt");
    Path amb = outRoot.resolve(s + "-rule-result-amb.txt");

    try (
        PrintWriter pwu = new PrintWriter(out.toFile(), "utf-8");
        PrintWriter pwa= new PrintWriter(amb.toFile(), "utf-8")
    ) {
      for (ResultSentence sentence : result.results) {
        pwu.println(sentence.sentence);
        pwa.println(sentence.sentence);
        for (AmbiguityAnalysis analysis : sentence.results) {
          pwu.println(analysis.input);
          pwa.println(analysis.input);
          for (AnalysisDecision r : analysis.choices) {
            pwu.println(r.analysis.format() + (r.decision == Decision.IGNORE ? "-" : ""));
            pwa.println(r.analysis.format());
          }
        }
        pwu.println();
        pwa.println();
      }
    }
  }

  private void collect(BatchResult batchResult, Path p, int resultLimit) throws IOException {

    LinkedHashSet<String> sentences = getSentences(p);

    List<String> normalized = new ArrayList<>();
    for (String sentence : sentences) {
      sentence = sentence.replaceAll("\\s+|\\u00a0", " ");
      sentence = sentence.replaceAll("[\\u00ad]", "");
      sentence = sentence.replaceAll("[…]", "...");
      normalized.add(sentence);
    }

    LinkedHashSet<String> toProcess = new LinkedHashSet<>();
    for (String s : normalized) {
      boolean ok = true;
      for (Predicate<String> ignorePredicate : ignoreSentencePredicates) {
        if (ignorePredicate.test(s)) {
          ok = false;
          break;
        }
      }
      if (!ok) {
        batchResult.ignoredSentences.add(s);
      } else {
        toProcess.add(s);
      }
    }

    for (String sentence : toProcess) {

      ResultSentence r = ruleBasedDisambiguator.disambiguate(sentence);

      boolean sentenceOk = true;

      for (_WordAnalysis an : r.sentenceAnalysis) {
        boolean ok = true;
        for (Predicate<_WordAnalysis> predicate : acceptWordPredicates) {
          if (!predicate.test(an)) {
            ok = false;
            break;
          }
        }
        if (!ok) {
          batchResult.ignoredSentences.add(sentence);
          sentenceOk = false;
          break;
        }
      }

      if (sentenceOk) {
        batchResult.acceptedSentences.add(sentence);
        batchResult.results.add(r);
        if (resultLimit > 0 && batchResult.results.size() > resultLimit) {
          return;
        }
      }
    }
  }

  static class BatchResult {

    LinkedHashSet<String> ignoredSentences = new LinkedHashSet<>();
    LinkedHashSet<String> acceptedSentences = new LinkedHashSet<>();
    List<ResultSentence> results = new ArrayList<>();
  }

  private LinkedHashSet<String> getSentences(Path p) throws IOException {
    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8).stream()
        .filter(s -> !s.startsWith("<")).collect(Collectors.toList());
    return new LinkedHashSet<>(TurkishSentenceExtractor.DEFAULT.fromParagraphs(lines));
  }


}
