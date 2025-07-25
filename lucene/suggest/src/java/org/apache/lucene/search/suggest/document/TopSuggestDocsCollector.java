/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.search.suggest.document;

import static org.apache.lucene.search.suggest.document.TopSuggestDocs.SUGGEST_SCORE_DOC_COMPARATOR;
import static org.apache.lucene.search.suggest.document.TopSuggestDocs.SuggestScoreDoc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.lucene.analysis.CharArraySet;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.CollectionTerminatedException;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;
import org.apache.lucene.search.TotalHits;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.util.PriorityQueue;

/**
 * {@link org.apache.lucene.search.Collector} that collects completion and score, along with
 * document id
 *
 * <p>Non scoring collector that collect completions in order of their pre-computed scores.
 *
 * <p>NOTE: One document can be collected multiple times if a document is matched for multiple
 * unique completions for a given query
 *
 * <p>Subclasses should only override {@link TopSuggestDocsCollector#collect(int, CharSequence,
 * CharSequence, float)}.
 *
 * <p>NOTE: {@link #setScorer(org.apache.lucene.search.Scorable)} and {@link #collect(int)} is not
 * used
 *
 * @lucene.experimental
 */
public class TopSuggestDocsCollector extends SimpleCollector {

  private final PriorityQueue<SuggestScoreDoc> priorityQueue;
  private final int num;

  /**
   * Only set if we are deduplicating hits: holds all per-segment hits until the end, when we dedup
   * them
   */
  private final List<SuggestScoreDoc> pendingResults;

  /**
   * Only set if we are deduplicating hits: holds all surface forms seen so far in the current
   * segment
   */
  final CharArraySet seenSurfaceForms;

  /** Document base offset for the current Leaf */
  protected int docBase;

  /**
   * Sole constructor
   *
   * <p>Collects at most <code>num</code> completions with corresponding document and weight
   */
  public TopSuggestDocsCollector(int num, boolean skipDuplicates) {
    if (num <= 0) {
      throw new IllegalArgumentException("'num' must be > 0");
    }
    this.num = num;
    this.priorityQueue = PriorityQueue.usingComparator(num, SUGGEST_SCORE_DOC_COMPARATOR);
    if (skipDuplicates) {
      seenSurfaceForms = new CharArraySet(num, false);
      pendingResults = new ArrayList<>(num);
    } else {
      seenSurfaceForms = null;
      pendingResults = null;
    }
  }

  /** Returns true if duplicates are filtered out */
  protected boolean doSkipDuplicates() {
    return seenSurfaceForms != null;
  }

  /** Returns the number of results to be collected */
  public int getCountToCollect() {
    return num;
  }

  @Override
  protected void doSetNextReader(LeafReaderContext context) throws IOException {
    docBase = context.docBase;
  }

  @Override
  public void finish() throws IOException {
    if (seenSurfaceForms != null) {
      // doesn't need to be sorted now, it is sorted in the get() method
      priorityQueue.iterator().forEachRemaining(pendingResults::add);
      priorityQueue.clear();

      // Deduplicate all hits: we already dedup'd efficiently within each segment by
      // truncating the FST top paths search, but across segments there may still be dups:
      seenSurfaceForms.clear();
    }
  }

  /**
   * Called for every matched completion, similar to {@link
   * org.apache.lucene.search.LeafCollector#collect(int)} but for completions.
   *
   * <p>NOTE: collection at the leaf level is guaranteed to be in descending order of score
   */
  public void collect(int docID, CharSequence key, CharSequence context, float score)
      throws IOException {
    SuggestScoreDoc current = new SuggestScoreDoc(docBase + docID, key, context, score);
    if (current == priorityQueue.insertWithOverflow(current)) {
      // if the current SuggestScoreDoc has overflown from pq,
      // we can assume all of the successive collections from
      // this leaf will be overflown as well
      // TODO: reuse the overflow instance?
      throw new CollectionTerminatedException();
    }
  }

  /**
   * Returns at most <code>num</code> Top scoring {@link
   * org.apache.lucene.search.suggest.document.TopSuggestDocs}s
   */
  public TopSuggestDocs get() throws IOException {

    SuggestScoreDoc[] suggestScoreDocs;

    if (seenSurfaceForms != null) {
      assert seenSurfaceForms.isEmpty();
      // TODO: we could use a priority queue here to make cost O(N * log(num)) instead of O(N *
      // log(N)), where N = O(num *
      // numSegments), but typically numSegments is smallish and num is smallish so this won't
      // matter much in practice:

      pendingResults.sort(
          (a, b) -> {
            // sort by higher score
            int cmp = Float.compare(b.score, a.score);
            if (cmp == 0) {
              // tie-break by completion key
              cmp = Lookup.CHARSEQUENCE_COMPARATOR.compare(a.key, b.key);
              if (cmp == 0) {
                // prefer smaller doc id, in case of a tie
                cmp = Integer.compare(a.doc, b.doc);
              }
            }
            return cmp;
          });

      List<SuggestScoreDoc> hits = new ArrayList<>();

      for (SuggestScoreDoc hit : pendingResults) {
        if (seenSurfaceForms.add(hit.key)) {
          hits.add(hit);
          if (hits.size() == num) {
            break;
          }
        }
      }
      suggestScoreDocs = hits.toArray(SuggestScoreDoc[]::new);
    } else {
      suggestScoreDocs = priorityQueue.drainToArrayHighestFirst(SuggestScoreDoc[]::new);
    }

    if (suggestScoreDocs.length > 0) {
      return new TopSuggestDocs(
          new TotalHits(suggestScoreDocs.length, TotalHits.Relation.EQUAL_TO), suggestScoreDocs);
    } else {
      return TopSuggestDocs.EMPTY;
    }
  }

  /** Ignored */
  @Override
  public void collect(int doc) throws IOException {
    // {@link #collect(int, CharSequence, CharSequence, long)} is used
    // instead
  }

  /** Ignored */
  @Override
  public ScoreMode scoreMode() {
    return ScoreMode.COMPLETE;
  }
}
