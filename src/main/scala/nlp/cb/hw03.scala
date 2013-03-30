// Copyright 2013 Christopher Brown, MIT Licensed
package nlp.cb
import org.rogach.scallop._
import scala.util.Random
import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer

import java.io.{PrintWriter, OutputStream}
import edu.stanford.nlp.parser.lexparser.{LexicalizedParser, TrainOptions, TestOptions, Options, EnglishTreebankParserParams, TreebankLangParserParams}
import edu.stanford.nlp.io.{NumberRangeFileFilter, RegExFileFilter}
import edu.stanford.nlp.trees.{Tree, Treebank, DiskTreebank, MemoryTreebank}
import edu.stanford.nlp.ling.{HasTag, HasWord, TaggedWord}

class NullOutputStream extends OutputStream {
  override def write(b: Int) { }
  override def write(b: Array[Byte]) { }
  override def write(b: Array[Byte], off: Int, len: Int) { }
  override def close() { }
  override def flush() { }
}

class QuietEnglishTreebankParserParams() extends EnglishTreebankParserParams {
  override def pw(): PrintWriter = {
    return new PrintWriter(new NullOutputStream())
  }

  override def pw(o: OutputStream): PrintWriter = {
    return new PrintWriter(new NullOutputStream())
  }

  override def display() { }
}

class QuietTestOptions extends TestOptions {
  override def display() { }
}
class QuietTrainOptions extends TrainOptions {
  override def display() { }
}
class QuietOptions(par: TreebankLangParserParams) extends Options(par) {
  trainOptions = new QuietTrainOptions()
  testOptions = new QuietTestOptions()
  override def display() { }
}

object ActiveLearner {
  // run-main nlp.cb.ActiveLearner -i 1 -p 1000
  // --train-dir $PENN/parsed/mrg/wsj/ --test-dir $PENN/parsed/mrg/wsj/
  class TreebankWrapper(trees: Seq[Tree]) {
    def toTreebank = {
      val treebank = new MemoryTreebank()
      treebank.addAll(0, trees)
      treebank
    }
  }
  implicit def wrapTreebank(trees: Seq[Tree]) = new TreebankWrapper(trees)

  val LOGn2 = math.log(2.0)
  def log2(x: Double) = math.log(x) / LOGn2

  def main(args: Array[String]) {
    val opts = Scallop(args.toList)
      // .opt[String]("train-dir")
      // .opt[String]("test-dir")
      .opt[Int]("iterations", required=true, short='i')
      .opt[Int]("sentences-per-iteration", required=true, short='p')
      .opt[String]("selection-method", required=true, short='s')
      .verify

    val penn = sys.env("PENN")

    val tlp = new QuietEnglishTreebankParserParams()
    val options = new QuietOptions(tlp)
    options.doDep = false
    options.doPCFG = true
    options.setOptions("-goodPCFG", "-evals", "tsv")
    options.testOptions.verbose = false

    // def step2 = {
    //   val trainTreebank = options.tlpParams.diskTreebank()
    //   // var trainTreebank = new DiskTreebank()
    //   trainTreebank.loadPath(opts[String]("train-dir"), new NumberRangeFileFilter(200, 270, true))

    //   val testTreebank = new MemoryTreebank()
    //   testTreebank.loadPath(opts[String]("test-dir"), new NumberRangeFileFilter(2000, 2100, true))

    //   val parser = LexicalizedParser.trainFromTreebank(trainTreebank, options)
    //   parser.parserQuery().testOnTreebank(testTreebank)
    // }

    // step3
    // Create an initial training set, an "unlabeled" training pool for active learning, and a test set. To create the initial training set, extract the first 50 sentences from section 00. For the unlabeled training set, concatenate sections 01-03 of WSJ. This will give you roughly 4500 additional potential training sentences (approximately 100,000 words). For testing, use WSJ section 20.

    val wsj_00 = new MemoryTreebank()
    wsj_00.loadPath(penn+"/parsed/mrg/wsj/00")
    wsj_00.textualSummary
    // the Stanford NLP is pretty awesome, because if I don't run the textualSummary, training the parser will break later
    val initial = wsj_00.toSeq.take(50)

    val unlabeled = new MemoryTreebank()
    unlabeled.loadPath(penn+"/parsed/mrg/wsj/01")
    unlabeled.loadPath(penn+"/parsed/mrg/wsj/02")
    unlabeled.loadPath(penn+"/parsed/mrg/wsj/03")
    unlabeled.textualSummary

    val test = new MemoryTreebank()
    test.loadPath(penn+"/parsed/mrg/wsj/20")
    test.textualSummary

    // step4
    val iterations = opts[Int]("iterations")
    val sentences_per_iteration = opts[Int]("sentences-per-iteration")
    // def step4(iterations: Int, : Int) {
    // Using the ParserDemo.java class as a example, develop a simple command line interface to the LexicalizedParser that includes support for active learning. Your package should train a parser on a given training set and evaluate it on a given test set, as with the bundled LexicalizedParser. Additionally, choose a random set of sentences from the "unlabeled" training pool whose word count totals approximately 1500 (this represents approximately 60 additional sentences of average length). Output the original training set plus the annotated versions of the randomly selected sentences as your next training set. Output the remaining "unlabeled" training instances as your next "unlabeled" training pool. Lastly, collect your results for this iteration, including at a minimum the following:

    val results = ListBuffer[Map[String, Any]]()

    var next_initial = initial
    var next_unlabeled = unlabeled.toSeq

    val selection_method = opts[String]("selection-method") // "random" "length" "top" "entropy"

    // Step 5: Execute 10-20 iterations of your parser for the random selection function, selecting approx 1500 words of additional training data each iteration. You may wish to write a simple test harness script that automates this for you. The random selection function represents a baseline that your more sophisticated sample selection functions should outperform.
    for (iteration <- 1 to iterations) {
      val parser = LexicalizedParser.trainFromTreebank(next_initial.toTreebank, options)

      // we select the next sentences to train on from the beginning of the unlabeled_sorted list
      val unlabeled_sorted = selection_method match {
        case "random" =>
          Random.shuffle(next_unlabeled)
        case "length" =>
          // sortBy+reverse to make the longest first
          next_unlabeled.sortBy(_.yieldHasWord().size).reverse
        case "top" =>
          // sortBy+reverse to put the highest scores first
          next_unlabeled.sortBy { unlabeled_tree =>
            val parserQuery = parser.parserQuery()
            val sentence = unlabeled_tree.yieldHasWord()
            val best_score = if (parserQuery.parse(sentence)) {
              // parserQuery.getPCFGScore() -> lower = less likely!
              parserQuery.getPCFGScore()
            }
            else {
              0.0
            }
            // take the n - 1'th root as a way of normalizing
            math.pow(best_score, 1.0 / (sentence.size - 1))
          }.reverse
        case "entropy" =>
          // we are seeking low entropy, so don't resort
          next_unlabeled.sortBy { unlabeled_tree =>
            val k = 20
            val parserQuery = parser.parserQuery()
            val sentence = unlabeled_tree.yieldHasWord()
            val tree_entropy = if (parserQuery.parse(sentence)) {
              val top_k_parses = parserQuery.getKBestPCFGParses(k)
              // top_parses(0).score is a log prob, so we exponentiate
              val top_k_probabilities = top_k_parses.map(_.score).map(math.exp)
              // val top_k_log_probs = top_parses.map(_.score)
              val p_sentence = top_k_probabilities.sum
              val top_k_normalized = top_k_probabilities.map(_/p_sentence)

              -top_k_normalized.map { p => p * log2(p) }.sum
            }
            else {
              Double.PositiveInfinity
            }
            tree_entropy / sentence.size
          }
      }

      val (unlabeled_selection, unlabeled_remainder) = unlabeled_sorted.splitAt(sentences_per_iteration)
      val unlabeled_selection_reparsed = unlabeled_selection.map { unlabeled_tree =>
        parser.apply(unlabeled_tree.yieldHasWord())
      }

      // update
      next_initial = next_initial ++ unlabeled_selection_reparsed
      next_unlabeled = unlabeled_remainder

      // other than going through again and counting the unlabeled_section words,
      // we don't need it anymore. we only keep the reparses.
      var active_training_words = unlabeled_selection.map(_.yieldHasWord().size).sum
      val total_training_words = next_initial.map(_.yieldHasWord().size).sum

      val retrained_parser = LexicalizedParser.trainFromTreebank(next_initial.toTreebank, options)
      results += Map(
        "Iteration" -> iteration,
        "Added training words" -> active_training_words,
        "Total training words" -> total_training_words,
        "Sample selection method" -> selection_method,
        "PCFG F1 score" -> retrained_parser.parserQuery().testOnTreebank(test)
      )
    }

    println(List.fill(80)("=").mkString)
    val columns = List(
      ("Iteration", "%d"),
      ("Added training words", "%d"),
      ("Total training words", "%d"),
      ("Sample selection method", "%s"),
      ("PCFG F1 score", "%.5f")
    )
    val table = Table(columns, ", ")
    table.printHeader()
    for (result <- results)
      table.printLine(result)

    // For reference, the TA's code took the following arguments: --trainBank <file>, --candidateBank <file>, --testBank <file>, --nextTrainBank <file>, --nextCandidatePool <file>, --selectionFunction <random|treeEntropy|...>. For each iteration, the first three arguments were files that were read, and the next two were filenames that were written to.

  }
}
