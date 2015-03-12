package edu.stanford.nlp.naturalli;

import edu.stanford.nlp.classify.*;
import edu.stanford.nlp.ie.machinereading.structure.Span;
import edu.stanford.nlp.ie.util.RelationTriple;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.io.RuntimeIOException;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.ling.RVFDatum;
import edu.stanford.nlp.math.SloppyMath;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.trees.GrammaticalRelation;
import edu.stanford.nlp.util.*;
import edu.stanford.nlp.util.PriorityQueue;

import java.io.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPOutputStream;

import static edu.stanford.nlp.util.logging.Redwood.Util.*;

/**
 * <p>
 *   A search problem for finding clauses in a sentence.
 * </p>
 *
 * <p>
 *   For usage at test time, load a model from
 *   {@link ClauseSearcher#factory(File)}, and then take the top clauses of a given tree
 *   with {@link ClauseSearcher#topClauses(double)}, yielding a list of
 *   {@link edu.stanford.nlp.naturalli.SentenceFragment}s.
 * </p>
 * <pre>
 *   {@code
 *     ClauseSearcher searcher = ClauseSearcher.factory("/model/path/");
 *     List<SentenceFragment> sentences = searcher.topClauses(threshold);
 *   }
 * </pre>
 *
 * <p>
 *   For training, see {@link ClauseSearcher#trainFactory(Stream, File, File)}.
 * </p>
 *
 * @author Gabor Angeli
 */
public class ClauseSearcher {

  /**
   * The tree to search over.
   */
  public final SemanticGraph tree;
  /**
   * The length of the sentence, as determined from the tree.
   */
  public final int sentenceLength;
  /**
   * A mapping from a word to the extra edges that come out of it.
   */
  private final Map<IndexedWord, Collection<SemanticGraphEdge>> extraEdgesByGovernor = new HashMap<>();
  /**
   * The classifier for whether a particular dependency edge defines a clause boundary.
   */
  private final Optional<Classifier<Boolean, String>> isClauseClassifier;
  /**
   * An optional featurizer to use with the clause classifier ({@link ClauseSearcher#isClauseClassifier}).
   * If that classifier is defined, this should be as well.
   */
  private final Optional<Function<Triple<ClauseSearcher.State, ClauseSearcher.Action, ClauseSearcher.State>, Counter<String>>> featurizer;

  /**
   * A mapping from edges in the tree, to an index.
   */
  @SuppressWarnings("Convert2Diamond")  // It's lying -- type inference times out with a diamond
  private final Index<SemanticGraphEdge> edgeToIndex = new HashIndex<SemanticGraphEdge>(ArrayList::new, IdentityHashMap::new);

  /**
   * A search state.
   */
  public class State {
    public final SemanticGraphEdge edge;
    public final int edgeIndex;
    public final SemanticGraphEdge subjectOrNull;
    public final int distanceFromSubj;
    public final SemanticGraphEdge ppOrNull;
    public final Consumer<SemanticGraph> thunk;
    public final boolean isDone;

    public State(SemanticGraphEdge edge, SemanticGraphEdge subjectOrNull, int distanceFromSubj, SemanticGraphEdge ppOrNull,
                 Consumer<SemanticGraph> thunk, boolean isDone) {
      this.edge = edge;
      this.edgeIndex = edgeToIndex.indexOf(edge);
      this.subjectOrNull = subjectOrNull;
      this.distanceFromSubj = distanceFromSubj;
      this.ppOrNull = ppOrNull;
      this.thunk = thunk;
      this.isDone = isDone;
    }

    public State(State source, boolean isDone) {
      this.edge = source.edge;
      this.edgeIndex = edgeToIndex.indexOf(edge);
      this.subjectOrNull = source.subjectOrNull;
      this.distanceFromSubj = source.distanceFromSubj;
      this.ppOrNull = source.ppOrNull;
      this.thunk = source.thunk;
      this.isDone = isDone;
    }

    public SemanticGraph originalTree() {
      return ClauseSearcher.this.tree;
    }
  }

  /**
   * An action being taken; that is, the type of clause splitting going on.
   */
  public static interface Action {
    /**
     * The name of this action.
     */
    public String signature();

    /**
     * A check to make sure this is actually a valid action to take, in the context of the given tree.
     * @param originalTree The _original_ tree we are searching over. This is before any clauses are split off.
     * @param edge The edge that we are traversing with this clause.
     * @return True if this is a valid action.
     */
    @SuppressWarnings("UnusedParameters")
    public default boolean prerequisitesMet(SemanticGraph originalTree, SemanticGraphEdge edge) {
      return true;
    }

    /**
     * Apply this action to the given state.
     * @param tree The original tree we are applying the action to.
     * @param source The source state we are mutating from.
     * @param outgoingEdge The edge we are splitting off as a clause.
     * @param subjectOrNull The subject of the parent tree, if there is one.
     * @param ppOrNull The preposition attachment of the parent tree, if there is one.
     * @return A new state, or {@link Optional#empty()} if this action was not successful.
     */
    public Optional<State> applyTo(SemanticGraph tree, State source,
                                   SemanticGraphEdge outgoingEdge,
                                   SemanticGraphEdge subjectOrNull,
                                   SemanticGraphEdge ppOrNull);
  }

  /**
   * The options used for training the clause searcher.
   */
  public static class TrainingOptions {
    @Execution.Option(name = "negativeSubsampleRatio", gloss = "The percent of negative datums to take")
    public double negativeSubsampleRatio = 0.10;
    @Execution.Option(name = "positiveDatumWeight", gloss = "The weight to assign every positive datum.")
    public float positiveDatumWeight = 50.0f;
    @Execution.Option(name = "seed", gloss = "The random seed to use")
    public int seed = 42;
    @SuppressWarnings("unchecked")
    @Execution.Option(name = "classifierFactory", gloss = "The class of the classifier factory to use for training the various classifiers")
    public Class<? extends ClassifierFactory<Boolean, String, Classifier<Boolean, String>>> classifierFactory = (Class<? extends ClassifierFactory<Boolean, String, Classifier<Boolean, String>>>) ((Object) LinearClassifierFactory.class);
  }

  /**
   * Mostly just an alias, but make sure our featurizer is serializable!
   */
  public static interface Featurizer extends Function<Triple<ClauseSearcher.State, ClauseSearcher.Action, ClauseSearcher.State>, Counter<String>>, Serializable { }

  /**
   * Create a searcher manually, suppling a dependency tree, an optional classifier for when to split clauses,
   * and a featurizer for that classifier.
   * You almost certainly want to use {@link edu.stanford.nlp.naturalli.ClauseSearcher#factory(java.io.File)} instead of this
   * constructor.
   *
   * @param tree               The dependency tree to search over.
   * @param isClauseClassifier The classifier for whether a given dependency arc should be a new clause. If this is not given, all arcs are treated as clause separators.
   * @param featurizer         The featurizer for the classifier. If no featurizer is given, one should be given in {@link ClauseSearcher#search(java.util.function.Predicate, edu.stanford.nlp.stats.Counter, java.util.function.Function, int)}, or else the classifier will be useless.
   * @see edu.stanford.nlp.naturalli.ClauseSearcher#factory(java.io.File)
   */
  public ClauseSearcher(SemanticGraph tree,
                        Optional<Classifier<Boolean, String>> isClauseClassifier,
                        Optional<Function<Triple<ClauseSearcher.State, ClauseSearcher.Action, ClauseSearcher.State>, Counter<String>>> featurizer
  ) {
    this.tree = new SemanticGraph(tree);
    this.isClauseClassifier = isClauseClassifier;
    this.featurizer = featurizer;
    // Index edges
    this.tree.edgeIterable().forEach(edgeToIndex::addToIndex);
    // Get length
    List<IndexedWord> sortedVertices = tree.vertexListSorted();
    sentenceLength = sortedVertices.get(sortedVertices.size() - 1).index();
    // Register extra edges
    for (IndexedWord vertex : sortedVertices) {
      extraEdgesByGovernor.put(vertex, new ArrayList<>());
    }
    List<SemanticGraphEdge> extraEdges = cleanTree(this.tree);
    assert isTree(this.tree);
    for (SemanticGraphEdge edge : extraEdges) {
      extraEdgesByGovernor.get(edge.getGovernor()).add(edge);
    }
  }

  /**
   * Create a clause searcher which searches naively through every possible subtree as a clause.
   * For an end-user, this is almost certainly not what you want.
   * However, it is very useful for training time.
   *
   * @param tree The dependency tree to search over.
   */
  protected ClauseSearcher(SemanticGraph tree) {
    this(tree, Optional.empty(), Optional.empty());
  }


  /**
   * Fix some bizarre peculiarities with certain trees.
   * So far, these include:
   * <ul>
   * <li>Sometimes there's a node from a word to itself. This seems wrong.</li>
   * </ul>
   *
   * @param tree The tree to clean (in place!).
   * @return A list of extra edges, which are valid but were removed.
   */
  private static List<SemanticGraphEdge> cleanTree(SemanticGraph tree) {
    // Clean nodes
    List<IndexedWord> toDelete = new ArrayList<>();
    for (IndexedWord vertex : tree.vertexSet()) {
      // Clean punctuation
      char tag = vertex.backingLabel().tag().charAt(0);
      if (tag == '.' || tag == ',' || tag == '(' || tag == ')' || tag == ':') {
        if (!tree.outgoingEdgeIterator(vertex).hasNext()) {  // This should really never happen, but it does.
          toDelete.add(vertex);
        }
      }
    }
    toDelete.forEach(tree::removeVertex);

    // Clean edges
    Iterator<SemanticGraphEdge> iter = tree.edgeIterable().iterator();
    while (iter.hasNext()) {
      SemanticGraphEdge edge = iter.next();
      if (edge.getDependent().index() == edge.getGovernor().index()) {
        // Clean self-edges
        iter.remove();
      } else if (edge.getRelation().toString().equals("punct")) {
        // Clean punctuation (again)
        if (!tree.outgoingEdgeIterator(edge.getDependent()).hasNext()) {  // This should really never happen, but it does.
          iter.remove();
        }
      }
    }

    // Remove extra edges
    List<SemanticGraphEdge> extraEdges = new ArrayList<>();
    for (SemanticGraphEdge edge : tree.edgeIterable()) {
      if (edge.isExtra()) {
        if (tree.incomingEdgeList(edge.getDependent()).size() > 1) {
          extraEdges.add(edge);
        }
      }
    }
    extraEdges.forEach(tree::removeEdge);
    // Add apposition edges (simple coref)
    for (SemanticGraphEdge extraEdge : new ArrayList<>(extraEdges)) {  // note[gabor] prevent concurrent modification exception
      for (SemanticGraphEdge candidateAppos : tree.incomingEdgeIterable(extraEdge.getDependent())) {
        if (candidateAppos.getRelation().toString().equals("appos")) {
          extraEdges.add(new SemanticGraphEdge(extraEdge.getGovernor(), candidateAppos.getGovernor(), extraEdge.getRelation(), extraEdge.getWeight(), extraEdge.isExtra()));
        }
      }
      for (SemanticGraphEdge candidateAppos : tree.outgoingEdgeIterable(extraEdge.getDependent())) {
        if (candidateAppos.getRelation().toString().equals("appos")) {
          extraEdges.add(new SemanticGraphEdge(extraEdge.getGovernor(), candidateAppos.getDependent(), extraEdge.getRelation(), extraEdge.getWeight(), extraEdge.isExtra()));
        }
      }
    }

    // Brute force ensure tree
    // Remove incoming edges from roots
    List<SemanticGraphEdge> rootIncomingEdges = new ArrayList<>();
    for (IndexedWord root : tree.getRoots()) {
      for (SemanticGraphEdge incomingEdge : tree.incomingEdgeIterable(root)) {
        rootIncomingEdges.add(incomingEdge);
      }
    }
    rootIncomingEdges.forEach(tree::removeEdge);
    // Loop until it becomes a tree.
    boolean changed = true;
    while (changed) {  // I just want trees to be trees; is that so much to ask!?
      changed = false;
      List<IndexedWord> danglingNodes = new ArrayList<>();
      List<SemanticGraphEdge> invalidEdges = new ArrayList<>();

      for (IndexedWord vertex : tree.vertexSet()) {
        // Collect statistics
        Iterator<SemanticGraphEdge> incomingIter = tree.incomingEdgeIterator(vertex);
        boolean hasIncoming = incomingIter.hasNext();
        boolean hasMultipleIncoming = false;
        if (hasIncoming) {
          incomingIter.next();
          hasMultipleIncoming = incomingIter.hasNext();
        }

        // Register actions
        if (!hasIncoming && !tree.getRoots().contains(vertex)) {
          danglingNodes.add(vertex);
        } else {
          if (hasMultipleIncoming) {
            for (SemanticGraphEdge edge : new IterableIterator<>(incomingIter)) {
              invalidEdges.add(edge);
            }
          }
        }
      }

      // Perform actions
      for (IndexedWord vertex : danglingNodes) {
        tree.removeVertex(vertex);
        changed = true;
      }
      for (SemanticGraphEdge edge : invalidEdges) {
        tree.removeEdge(edge);
        changed = true;
      }
    }

    // Return
    assert isTree(tree);
    return extraEdges;
  }

  /**
   * The basic method for splitting off a clause of a tree.
   * This modifies the tree in place.
   *
   * @param tree The tree to split a clause from.
   * @param toKeep The edge representing the clause to keep.
   */
  private static void simpleClause(SemanticGraph tree, SemanticGraphEdge toKeep) {
    Queue<IndexedWord> fringe = new LinkedList<>();
    List<IndexedWord> nodesToRemove = new ArrayList<>();
    // Find nodes to remove
    // (from the root)
    for (IndexedWord root : tree.getRoots()) {
      nodesToRemove.add(root);
      for (SemanticGraphEdge out : tree.outgoingEdgeIterable(root)) {
        if (!out.equals(toKeep)) {
          fringe.add(out.getDependent());
        }
      }
    }
    // (recursively)
    while (!fringe.isEmpty()) {
      IndexedWord node = fringe.poll();
      nodesToRemove.add(node);
      for (SemanticGraphEdge out : tree.outgoingEdgeIterable(node)) {
        if (!out.equals(toKeep)) {
          fringe.add(out.getDependent());
        }
      }
    }
    // Remove nodes
    nodesToRemove.forEach(tree::removeVertex);
    // Set new root
    tree.setRoot(toKeep.getDependent());
  }

  /**
   * A helper to add a single word to a given dependency tree
   * @param toModify The tree to add the word to.
   * @param root The root of the tree where we should be adding the word.
   * @param rel The relation to add the word with.
   * @param coreLabel The word to add.
   */
  @SuppressWarnings("UnusedDeclaration")
  private static void addWord(SemanticGraph toModify, IndexedWord root, String rel, CoreLabel coreLabel) {
    IndexedWord dependent = new IndexedWord(coreLabel);
    toModify.addVertex(dependent);
    toModify.addEdge(root, dependent, GrammaticalRelation.valueOf(GrammaticalRelation.Language.English, rel), Double.NEGATIVE_INFINITY, false);
  }

  /**
   * A helper to add an entire subtree to a given dependency tree.
   *
   * @param toModify The tree to add the subtree to.
   * @param root The root of the tree where we should be adding the subtree.
   * @param rel The relation to add the subtree with.
   * @param originalTree The orignal tree (i.e., {@link edu.stanford.nlp.naturalli.ClauseSearcher#tree}).
   * @param subject The root of the clause to add.
   * @param ignoredEdges The edges to ignore adding when adding this subtree.
   */
  private static void addSubtree(SemanticGraph toModify, IndexedWord root, String rel, SemanticGraph originalTree, IndexedWord subject, Collection<SemanticGraphEdge> ignoredEdges) {
    if (toModify.containsVertex(subject)) {
      return;  // This subtree already exists.
    }
    Queue<IndexedWord> fringe = new LinkedList<>();
    Collection<IndexedWord> wordsToAdd = new ArrayList<>();
    Collection<SemanticGraphEdge> edgesToAdd = new ArrayList<>();
    // Search for subtree to add
    for (SemanticGraphEdge edge : originalTree.outgoingEdgeIterable(subject)) {
      if (!ignoredEdges.contains(edge)) {
        if (toModify.containsVertex(edge.getDependent())) {
          // Case: we're adding a subtree that's not disjoint from toModify. This is bad news.
          return;
        }
        edgesToAdd.add(edge);
        fringe.add(edge.getDependent());
      }
    }
    while (!fringe.isEmpty()) {
      IndexedWord node = fringe.poll();
      wordsToAdd.add(node);
      for (SemanticGraphEdge edge : originalTree.outgoingEdgeIterable(node)) {
        if (!ignoredEdges.contains(edge)) {
          if (toModify.containsVertex(edge.getDependent())) {
            // Case: we're adding a subtree that's not disjoint from toModify. This is bad news.
            return;
          }
          edgesToAdd.add(edge);
          fringe.add(edge.getDependent());
        }
      }
    }
    // Add subtree
    // (add subject)
    toModify.addVertex(subject);
    toModify.addEdge(root, subject, GrammaticalRelation.valueOf(GrammaticalRelation.Language.English, rel), Double.NEGATIVE_INFINITY, false);

    // (add nodes)
    wordsToAdd.forEach(toModify::addVertex);
    // (add edges)
    for (SemanticGraphEdge edge : edgesToAdd) {
      assert !toModify.incomingEdgeIterator(edge.getDependent()).hasNext();
      toModify.addEdge(edge.getGovernor(), edge.getDependent(), edge.getRelation(), edge.getWeight(), edge.isExtra());
    }
  }

  /**
   * A little utility function to make sure a SemanticGraph is a tree.
   * @param tree The tree to check.
   * @return True if this {@link edu.stanford.nlp.semgraph.SemanticGraph} is a tree (versus a DAG, or Graph).
   */
  private static boolean isTree(SemanticGraph tree) {
    for (IndexedWord vertex : tree.vertexSet()) {
      if (tree.getRoots().contains(vertex)) {
        if (tree.incomingEdgeIterator(vertex).hasNext()) {
          return false;
        }
      } else {
        Iterator<SemanticGraphEdge> iter = tree.incomingEdgeIterator(vertex);
        if (!iter.hasNext()) {
          return false;
        }
        iter.next();
        if (iter.hasNext()) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Create a mock node, to be added to the dependency tree but which is not part of the original sentence.
   *
   * @param toCopy The CoreLabel to copy from initially.
   * @param word   The new word to add.
   * @param POS    The new part of speech to add.
   *
   * @return A CoreLabel copying most fields from toCopy, but with a new word and POS tag (as well as a new index).
   */
  @SuppressWarnings("UnusedDeclaration")
  private CoreLabel mockNode(CoreLabel toCopy, String word, String POS) {
    CoreLabel mock = new CoreLabel(toCopy);
    mock.setWord(word);
    mock.setLemma(word);
    mock.setValue(word);
    mock.setNER("O");
    mock.setTag(POS);
    mock.setIndex(sentenceLength + 5);
    return mock;
  }

  /**
   * Get the top few clauses from this searcher, cutting off at the given minimum
   * probability.
   * @param thresholdProbability The threshold under which to stop returning clauses. This should be between 0 and 1.
   * @return The resulting {@link edu.stanford.nlp.naturalli.SentenceFragment} objects, representing the top clauses of the sentence.
   */
  public List<SentenceFragment> topClauses(double thresholdProbability) {
    List<SentenceFragment> results = new ArrayList<>();
    search(triple -> {
      double prob = Math.exp(triple.first);
      if (prob >= thresholdProbability) {
        SentenceFragment fragment = triple.third.get();
        fragment.score = prob;
        results.add(fragment);
        return true;
      } else {
        return false;
      }
    });
    return results;
  }

  /**
   * Search, using the default weights / featurizer. This is the most common entry method for the raw search,
   * though {@link edu.stanford.nlp.naturalli.ClauseSearcher#topClauses(double)} may be a more convenient method for
   * an end user.
   *
   * @param candidateFragments The callback function for results. The return value defines whether to continue searching.
   */
  public void search(final Predicate<Triple<Double, List<Counter<String>>, Supplier<SentenceFragment>>> candidateFragments) {
    if (!isClauseClassifier.isPresent() ||
        !(isClauseClassifier.get() instanceof LinearClassifier)) {
      throw new IllegalArgumentException("For now, only linear classifiers are supported");
    }
    search(candidateFragments,
        ((LinearClassifier<Boolean,String>) isClauseClassifier.get()).weightsAsMapOfCounters().get(true),
        this.featurizer.get(),
        10000);
  }

  /**
   * Search from the root of the tree.
   * This function also defines the default action space to use during search.
   *
   * @param candidateFragments The callback function.
   * @param weights The weights to use during search.
   * @param featurizer The featurizer to use during search, to be dot producted with the weights.
   */
  protected void search(
      // The output specs
      final Predicate<Triple<Double, List<Counter<String>>, Supplier<SentenceFragment>>> candidateFragments,
      // The learning specs
      final Counter<String> weights,
      final Function<Triple<State, Action, State>, Counter<String>> featurizer,
      final int maxTicks
  ) {
    Collection<Action> actionSpace = new ArrayList<>();

    // SIMPLE SPLIT
    actionSpace.add(new Action() {
      @Override
      public String signature() {
        return "simple";
      }

      @Override
      public Optional<State> applyTo(SemanticGraph tree, State source, SemanticGraphEdge outgoingEdge, SemanticGraphEdge subjectOrNull, SemanticGraphEdge ppOrNull) {
        return Optional.of(new State(
            outgoingEdge,
            subjectOrNull == null ? source.subjectOrNull : subjectOrNull,
            subjectOrNull == null ? (source.distanceFromSubj + 1) : 0,
            ppOrNull,
            source.thunk.andThen(toModify -> {
              assert isTree(toModify);
              simpleClause(toModify, outgoingEdge);
              assert isTree(toModify);
            }), false
        ));
      }
    });

    /*
    // CLONE ROOT
    actionSpace.add(new Action() {
      @Override
      public String signature() {
        return "clone_root_as_nsubjpass";
      }

      @Override
      public boolean prerequisitesMet(SemanticGraph originalTree, SemanticGraphEdge edge) {
        // Only valid if there's a single outgoing edge from a node. Otherwise it's a whole can of worms.
        Iterator<SemanticGraphEdge> iter =  originalTree.outgoingEdgeIterable(edge.getGovernor()).iterator();
        if (!iter.hasNext()) {
          return false; // what?
        }
        iter.next();
        return !iter.hasNext();
      }

      @Override
      public Optional<State> applyTo(SemanticGraph tree, State source, SemanticGraphEdge outgoingEdge, SemanticGraphEdge subjectOrNull, SemanticGraphEdge ppOrNull) {
        return Optional.of(new State(
            outgoingEdge,
            subjectOrNull == null ? source.subjectOrNull : subjectOrNull,
            subjectOrNull == null ? (source.distanceFromSubj + 1) : 0,
            ppOrNull,
            source.thunk.andThen(toModify -> {
              assert isTree(toModify);
              simpleClause(toModify, outgoingEdge);
              addSubtree(toModify, outgoingEdge.getDependent(), "nsubjpass", tree, outgoingEdge.getGovernor(), Collections.singleton(outgoingEdge));
//              addWord(toModify, outgoingEdge.getDependent(), "auxpass", mockNode(outgoingEdge.getDependent().backingLabel(), "is", "VBZ"));
              assert isTree(toModify);
            }), true
        ));
      }
    });
    */

    // COPY SUBJECT
    actionSpace.add(new Action() {
      @Override
      public String signature() {
        return "clone_nsubj";
      }

      @Override
      public Optional<State> applyTo(SemanticGraph tree, State source, SemanticGraphEdge outgoingEdge, SemanticGraphEdge subjectOrNull, SemanticGraphEdge ppOrNull) {
        if (subjectOrNull != null && !outgoingEdge.equals(subjectOrNull)) {
          return Optional.of(new State(
              outgoingEdge,
              subjectOrNull,
              0,
              ppOrNull,
              source.thunk.andThen(toModify -> {
                assert isTree(toModify);
                simpleClause(toModify, outgoingEdge);
                addSubtree(toModify, outgoingEdge.getDependent(), "nsubj", tree,
                    subjectOrNull.getDependent(), Collections.singleton(outgoingEdge));
                assert isTree(toModify);
              }), true
          ));
        } else {
          return Optional.empty();
        }
      }
    });

    for (IndexedWord root : tree.getRoots()) {
      search(root, candidateFragments, weights, featurizer, actionSpace, maxTicks);
    }
  }

  /**
   * The core implementation of the search.
   *
   * @param root The root word to search from. Traditionally, this is the root of the sentence.
   * @param candidateFragments The callback for the resulting sentence fragments.
   *                           This is a predicate of a triple of values.
   *                           The return value of the predicate determines whether we should continue searching.
   *                           The triple is a triple of
   *                           <ol>
   *                             <li>The log probability of the sentence fragment, according to the featurizer and the weights</li>
   *                             <li>The features along the path to this fragment. The last element of this is the features from the most recent step.</li>
   *                             <li>The sentence fragment. Because it is relatively expensive to compute the resulting tree, this is returned as a lazy {@link Supplier}.</li>
   *                           </ol>
   * @param weights The weights to use during searching. This is traditionally from a trained classifier (e.g., with {@link ClauseSearcher#factory(File)}).
   * @param featurizer The featurizer to use. Make sure this matches the weights!
   * @param actionSpace The action space we are allowed to take. Each action defines a means of splitting a clause on a dependency boundary.
   */
  protected void search(
      // The root to search from
      IndexedWord root,
      // The output specs
      final Predicate<Triple<Double, List<Counter<String>>, Supplier<SentenceFragment>>> candidateFragments,
      // The learning specs
      final Counter<String> weights,
      final Function<Triple<State, Action, State>, Counter<String>> featurizer,
      final Collection<Action> actionSpace,
      final int maxTicks
  ) {
    // (the fringe)
    PriorityQueue<Pair<State, List<Counter<String>>>> fringe = new FixedPrioritiesPriorityQueue<>();
    // (a helper list)
    List<SemanticGraphEdge> ppEdges = new ArrayList<>();
    // (avoid duplicate work)
    Set<IndexedWord> seenWords = new HashSet<>();

    State firstState = new State(null, null, -9000, null, x -> {
    }, false);
    fringe.add(Pair.makePair(firstState, new ArrayList<>(0)), -0.0);
    int ticks = 0;

    while (!fringe.isEmpty()) {
      if (++ticks > maxTicks) {
        System.err.println("WARNING! Timed out on search with " + ticks + " ticks");
        return;
      }
      // Useful variables
      double logProbSoFar = fringe.getPriority();
      Pair<State, List<Counter<String>>> lastStatePair = fringe.removeFirst();
      State lastState = lastStatePair.first;
      List<Counter<String>> featuresSoFar = lastStatePair.second;
      IndexedWord rootWord = lastState.edge == null ? root : lastState.edge.getDependent();
//      System.err.println("Looking at " + rootWord);

      // Register thunk
      if (!candidateFragments.test(Triple.makeTriple(logProbSoFar, featuresSoFar, () -> {
        SemanticGraph copy = new SemanticGraph(tree);
        lastState.thunk.andThen(x -> {
          // Add the extra edges back in, if they don't break the tree-ness of the extraction
          for (IndexedWord newTreeRoot : x.getRoots()) {
            for (SemanticGraphEdge extraEdge : extraEdgesByGovernor.get(newTreeRoot)) {
              assert isTree(x);
              //noinspection unchecked
              addSubtree(x, newTreeRoot, extraEdge.getRelation().toString(), tree, extraEdge.getDependent(), tree.getIncomingEdgesSorted(newTreeRoot));
              assert isTree(x);
            }
          }
        }).accept(copy);
        return new SentenceFragment(copy, false);
      }))) {
        break;
      }

      // Find relevant auxilliary terms
      ppEdges.clear();
      ppEdges.add(null);
      SemanticGraphEdge subjOrNull = null;
      for (SemanticGraphEdge auxEdge : tree.outgoingEdgeIterable(rootWord)) {
        String relString = auxEdge.getRelation().toString();
        if (relString.startsWith("prep")) {
          ppEdges.add(auxEdge);
        } else if (relString.contains("subj")) {
          subjOrNull = auxEdge;
        }
      }

      // Iterate over children
      for (Action action : actionSpace) {
        // For each action...
        for (SemanticGraphEdge outgoingEdge : tree.outgoingEdgeIterable(rootWord)) {
          // Check the prerequisite
          if (!action.prerequisitesMet(tree, outgoingEdge)) {
            continue;
          }
          // For each outgoing edge...
          // 1. Find the best aux information to carry along
          double max = Double.NEGATIVE_INFINITY;
          Pair<State, List<Counter<String>>> argmax = null;
          for (SemanticGraphEdge ppEdgeOrNull : ppEdges) {
            Optional<State> candidate = action.applyTo(tree, lastState,
                outgoingEdge, subjOrNull,
                ppEdgeOrNull);
            if (candidate.isPresent()) {
              Counter<String> features = featurizer.apply(Triple.makeTriple(lastState, action, candidate.get()));
              double probability = SloppyMath.sigmoid(Counters.dotProduct(features, weights));
              if (probability > max) {
                max = probability;
                argmax = Pair.makePair(candidate.get(), new ArrayList<Counter<String>>(featuresSoFar) {{
                  add(features);
                }});
              }
            }
          }
          // 2. Register the child state
          if (argmax != null && !seenWords.contains(argmax.first.edge.getDependent())) {
//            System.err.println("  pushing " + action.signature() + " with " + argmax.first.edge);
            fringe.add(argmax, Math.log(max));
          }
        }
      }

      seenWords.add(rootWord);
    }
  }



  /**
   * The default featurizer to use during training.
   */
  public static final Featurizer DEFAULT_FEATURIZER = triple -> {
    // Variables
    State from = triple.first;
    Action action = triple.second;
    State to = triple.third;
    String signature = action.signature();
    String edgeRelTaken = to.edge == null ? "root" : to.edge.getRelation().toString();
    String edgeRelShort = to.edge == null ?  "root"  : to.edge.getRelation().getShortName();
    if (edgeRelShort.contains("_")) {
      edgeRelShort = edgeRelShort.substring(0, edgeRelShort.indexOf("_"));
    }

    // -- Featurize --
    // Variables to aggregate
    boolean parentHasSubj = false;
    boolean parentHasObj = false;
    boolean childHasSubj = false;
    boolean childHasObj = false;
    Counter<String> feats = new ClassicCounter<>();

    // 1. edge taken
    feats.incrementCount(signature + "&edge:" + edgeRelTaken);
    feats.incrementCount(signature + "&edge_type:" + edgeRelShort);

    // 2. last edge taken
    if (from.edge == null) {
      assert to.edge == null || to.originalTree().getRoots().contains(to.edge.getGovernor());
      feats.incrementCount(signature + "&at_root");
      feats.incrementCount(signature + "&at_root&root_pos:" + to.originalTree().getFirstRoot().tag());
    } else {
      feats.incrementCount(signature + "&not_root");
      String lastRelShort = from.edge.getRelation().getShortName();
      if (lastRelShort.contains("_")) {
        lastRelShort = lastRelShort.substring(0, lastRelShort.indexOf("_"));
      }
      feats.incrementCount(signature + "&last_edge:" + lastRelShort);
    }

    if (to.edge != null) {
      // 3. other edges at parent
      for (SemanticGraphEdge parentNeighbor : from.originalTree().outgoingEdgeIterable(to.edge.getGovernor())) {
        if (parentNeighbor != to.edge) {
          String parentNeighborRel = parentNeighbor.getRelation().toString();
          if (parentNeighborRel.contains("subj")) {
            parentHasSubj = true;
          }
          if (parentNeighborRel.contains("obj")) {
            parentHasObj = true;
          }
          // (add feature)
          feats.incrementCount(signature + "&parent_neighbor:" + parentNeighborRel);
          feats.incrementCount(signature + "&edge_type:" + edgeRelShort + "&parent_neighbor:" + parentNeighborRel);
        }
      }

      // 4. Other edges at child
      for (SemanticGraphEdge childNeighbor : from.originalTree().outgoingEdgeIterable(to.edge.getDependent())) {
        String childNeighborRel = childNeighbor.getRelation().toString();
        if (childNeighborRel.contains("subj")) {
          childHasSubj = true;
        }
        if (childNeighborRel.contains("obj")) {
          childHasObj = true;
        }
        // (add feature)
        feats.incrementCount(signature + "&child_neighbor:" + childNeighborRel);
        feats.incrementCount(signature + "&edge_type:" + edgeRelShort + "&child_neighbor:" + childNeighborRel);
      }

      // 5. Subject/Object stats
      feats.incrementCount(signature + "&parent_neighbor_subj:" + parentHasSubj);
      feats.incrementCount(signature + "&parent_neighbor_obj:" + parentHasObj);
      feats.incrementCount(signature + "&child_neighbor_subj:" + childHasSubj);
      feats.incrementCount(signature + "&child_neighbor_obj:" + childHasObj);

      // 6. POS tag info
      feats.incrementCount(signature + "&parent_pos:" + to.edge.getGovernor().tag());
      feats.incrementCount(signature + "&child_pos:" + to.edge.getDependent().tag());
      feats.incrementCount(signature + "&pos_signature:" + to.edge.getGovernor().tag() + "->" + to.edge.getDependent().tag());
      feats.incrementCount(signature + "&edge_type:" + edgeRelShort + "&pos_signature:" + to.edge.getGovernor().tag() + "->" + to.edge.getDependent().tag());
    }
    return feats;
  };

  /**
   * A helper function for dumping the accuracy of the trained classifier.
   *
   * @param classifier The classifier to evaluate.
   * @param dataset The dataset to evaluate the classifier on.
   */
  private static void dumpAccuracy(Classifier<Boolean, String> classifier, GeneralDataset<Boolean, String> dataset) {
    DecimalFormat df = new DecimalFormat("0.000");
    log("size:       " + dataset.size());
    log("true count: " + StreamSupport.stream(dataset.spliterator(), false).filter(RVFDatum::label).collect(Collectors.toList()).size());
    Pair<Double, Double> pr = classifier.evaluatePrecisionAndRecall(dataset, true);
    log("precision:  " + df.format(pr.first));
    log("recall:     " + df.format(pr.second));
    log("f1:         " + df.format(2 * pr.first * pr.second / (pr.first + pr.second)));
  }

  /**
   * Train a clause searcher factory. That is, train a classifier for which arcs should be
   * new clauses.
   *
   * @param trainingData The training data. This is a stream of triples of:
   *                     <ol>
   *                       <li>The sentence containing a known extraction.</li>
   *                       <li>The span of the subject in the sentence, as a token span.</li>
   *                       <li>The span of the object in the sentence, as a token span.</li>
   *                     </ol>
   * @param featurizer The featurizer to use for this classifier.
   * @param options The training options.
   * @param modelPath The path to save the model to. This is useful for {@link ClauseSearcher#factory(File)}.
   * @param trainingDataDump The path to save the training data, as a set of labeled featurized datums.
   *
   * @return A factory for creating searchers from a given dependency tree.
   */
  public static Function<SemanticGraph, ClauseSearcher> trainFactory(
      Stream<Triple<CoreMap, Span, Span>> trainingData,
      Featurizer featurizer,
      TrainingOptions options,
      Optional<File> modelPath,
      Optional<File> trainingDataDump) {
    // Parse options
    ClassifierFactory<Boolean, String, Classifier<Boolean,String>> classifierFactory = MetaClass.create(options.classifierFactory).createInstance();
    // Generally useful objects
    OpenIE openie = new OpenIE();
    Random rand = new Random(options.seed);
    WeightedDataset<Boolean, String> dataset = new WeightedDataset<>();
    AtomicInteger numExamplesProcessed = new AtomicInteger(0);
    final Optional<PrintWriter> datasetDumpWriter = trainingDataDump.map(file -> {
      try {
        return new PrintWriter(new OutputStreamWriter(new GZIPOutputStream(new FileOutputStream(trainingDataDump.get()))));
      } catch (IOException e) {
        throw new RuntimeIOException(e);
      }
    });

    // Step 1: Inference over training sentences
    forceTrack("Training inference");
    trainingData.forEach(triple -> {
      // Parse training datum
      CoreMap sentence = triple.first;
      List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
      Span subjectSpan = Util.extractNER(tokens, triple.second);
      Span objectSpan = Util.extractNER(tokens, triple.third);
      // Create raw clause searcher (no classifier)
      ClauseSearcher problem = new ClauseSearcher(sentence.get(SemanticGraphCoreAnnotations.CollapsedDependenciesAnnotation.class));
      Pointer<Boolean> anyCorrect = new Pointer<>(false);

      // Run search
      problem.search(fragmentAndScore -> {
        // Parse the search output
        List<Counter<String>> features = fragmentAndScore.second;
        Supplier<SentenceFragment> fragmentSupplier = fragmentAndScore.third;
        SentenceFragment fragment = fragmentSupplier.get();
        // Search for extractions
        List<RelationTriple> extractions = openie.relationInClause(fragment.parseTree);
        boolean correct = false;
        RelationTriple bestExtraction = null;
        for (RelationTriple extraction : extractions) {
          // Clean up the guesses
          Span subjectGuess = Util.extractNER(tokens, Span.fromValues(extraction.subject.get(0).index() - 1, extraction.subject.get(extraction.subject.size() - 1).index()));
          Span objectGuess = Util.extractNER(tokens, Span.fromValues(extraction.object.get(0).index() - 1, extraction.object.get(extraction.object.size() - 1).index()));
          // Check if it matches
          if ((subjectGuess.equals(subjectSpan) && objectGuess.equals(objectSpan)) ||
              (subjectGuess.equals(objectSpan) && objectGuess.equals(subjectSpan))
              ) {
            correct = true;
            anyCorrect.set(true);
            bestExtraction = extraction;
          } else if ((subjectGuess.contains(subjectSpan) && objectGuess.contains(objectSpan)) ||
              (subjectGuess.contains(objectSpan) && objectGuess.contains(subjectSpan))
              ) {
            correct = true;
            anyCorrect.set(true);
            if (bestExtraction == null) {
              bestExtraction = extraction;
            }
          } else {
            if (bestExtraction == null) {
              bestExtraction = extraction;
            }
            correct = false;
          }
        }
        // Process the datum
        if ((bestExtraction != null || fragment.length() == 1) && !features.isEmpty()) {
          for (Counter<String> decision : features) {
            // Compute datum
            RVFDatum<Boolean, String> datum = new RVFDatum<>(decision);
            datum.setLabel(correct);
            // Dump datum to debug log
            if (datasetDumpWriter.isPresent()) {
              datasetDumpWriter.get().println("" + correct + "\t" +
                  (decision == features.get(features.size() - 1)) + "\t" +
                  StringUtils.join(decision.entrySet().stream().map(entry -> "" + entry.getKey() + "->" + entry.getValue()), ";"));
            }
            // Add datum to dataset
            if (correct || rand.nextDouble() > (1.0 - options.negativeSubsampleRatio)) {  // Subsample
              dataset.add(datum, correct ? options.positiveDatumWeight : 1.0f);
            }
          }
        }
        return true;
      }, new ClassicCounter<>(), featurizer, 10000);
      // Debug info
      if (numExamplesProcessed.incrementAndGet() % 100 == 0) {
        log("processed " + numExamplesProcessed + " training sentences: " + dataset.size() + " datums");
      }
    });
    // Close dataset dump
    datasetDumpWriter.ifPresent(PrintWriter::close);
    endTrack("Training inference");

    // Step 2: Train classifier
    forceTrack("Training");
    Classifier<Boolean,String> fullClassifier = classifierFactory.trainClassifier(dataset);
    endTrack("Training");
    if (modelPath.isPresent()) {
      Pair<Classifier<Boolean,String>, Featurizer> toSave = Pair.makePair(fullClassifier, featurizer);
      try {
        IOUtils.writeObjectToFile(toSave, modelPath.get());
        log("SUCCESS: wrote model to " + modelPath.get().getPath());
      } catch (IOException e) {
        log("ERROR: failed to save model to path: " + modelPath.get().getPath());
        err(e);
      }
    }

    // Step 3: Check accuracy of classifier
    forceTrack("Training accuracy");
    dataset.randomize(options.seed);
    dumpAccuracy(fullClassifier, dataset);
    endTrack("Training accuracy");

    int numFolds = 5;
    forceTrack("" + numFolds + " fold cross-validation");
    for (int fold = 0; fold < numFolds; ++fold) {
      forceTrack("Fold " + (fold + 1));
      forceTrack("Training");
      Pair<GeneralDataset<Boolean, String>, GeneralDataset<Boolean, String>> foldData = dataset.splitOutFold(fold, numFolds);
      Classifier<Boolean, String> classifier = classifierFactory.trainClassifier(foldData.first);
      endTrack("Training");
      forceTrack("Test");
      dumpAccuracy(classifier, foldData.second);
      endTrack("Test");
      endTrack("Fold " + (fold + 1));
    }
    endTrack("" + numFolds + " fold cross-validation");


    // Step 5: return factory
    return tree -> new ClauseSearcher(tree, Optional.of(fullClassifier), Optional.of(featurizer));
  }



  /**
   * A helper function for training with the default featurizer and training options.
   *
   * @see edu.stanford.nlp.naturalli.ClauseSearcher#trainFactory(Stream, Featurizer, TrainingOptions, Optional, Optional)
   */
  public static Function<SemanticGraph, ClauseSearcher> trainFactory(
      Stream<Triple<CoreMap, Span, Span>> trainingData,
      File modelPath,
      File trainingDataDump) {
    // Train
    return trainFactory(trainingData, DEFAULT_FEATURIZER, new TrainingOptions(), Optional.of(modelPath), Optional.of(trainingDataDump));
  }


  /**
   * Load a factory model from a given path. This can be trained with
   * {@link edu.stanford.nlp.naturalli.ClauseSearcher#trainFactory(Stream, Featurizer, TrainingOptions, Optional, Optional)}.
   *
   * @return A function taking a dependency tree, and returning a clause searcher.
   */
  public static Function<SemanticGraph, ClauseSearcher> factory(File serializedModel) throws IOException {
    try {
      System.err.println("Loading clause searcher from " + serializedModel.getPath() + " ...");
      Pair<Classifier<Boolean,String>, Featurizer> data = IOUtils.readObjectFromFile(serializedModel);
      return tree -> new ClauseSearcher(tree, Optional.of(data.first), Optional.of(data.second));
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("Invalid model at path: " + serializedModel.getPath(), e);
    }
  }

}
