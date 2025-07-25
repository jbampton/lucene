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
package org.apache.lucene.codecs.lucene90;

import java.io.IOException;
import java.util.Random;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.RandomAccessInput;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.BitSetIterator;
import org.apache.lucene.util.FixedBitSet;
import org.apache.lucene.util.SparseFixedBitSet;

// Copied from the lucene70 package for separation of codec-code
public class TestIndexedDISI extends LuceneTestCase {

  public void testEmpty() throws IOException {
    int maxDoc = TestUtil.nextInt(random(), 1, 100000);
    BitSet set = new SparseFixedBitSet(maxDoc);
    try (Directory dir = newDirectory()) {
      doTest(set, dir);
    }
  }

  // EMPTY blocks are special with regard to jumps as they have size 0
  @Nightly
  public void testEmptyBlocks() throws IOException {
    final int B = 65536;
    int maxDoc = B * 11;
    BitSet set = new SparseFixedBitSet(maxDoc);
    // block 0: EMPTY
    set.set(B + 5); // block 1: SPARSE
    // block 2: EMPTY
    // block 3: EMPTY
    set.set(B * 4 + 5); // block 4: SPARSE

    for (int i = 0; i < B; i++) {
      set.set(B * 6 + i); // block 6: ALL
    }
    for (int i = 0; i < B; i += 3) {
      set.set(B * 7 + i); // block 7: DENSE
    }
    for (int i = 0; i < B; i++) {
      if (i != 32768) {
        set.set(B * 8 + i); // block 8: DENSE (all-1)
      }
    }
    // block 9-11: EMPTY

    try (Directory dir = newDirectory()) {
      doTestAllSingleJump(set, dir);
    }

    // Change the first block to DENSE to see if jump-tables sets to position 0
    set.set(0);
    try (Directory dir = newDirectory()) {
      doTestAllSingleJump(set, dir);
    }
  }

  // EMPTY blocks are special with regard to jumps as they have size 0
  public void testLastEmptyBlocks() throws IOException {
    final int B = 65536;
    int maxDoc = B * 3;
    BitSet set = new SparseFixedBitSet(maxDoc);
    for (int docID = 0; docID < B * 2; docID++) { // first 2 blocks are ALL
      set.set(docID);
    }
    // Last block is EMPTY

    try (Directory dir = newDirectory()) {
      doTestAllSingleJump(set, dir);
      assertAdvanceBeyondEnd(set, dir);
    }
  }

  // Checks that advance after the end of the blocks has been reached has the correct behaviour
  private void assertAdvanceBeyondEnd(BitSet set, Directory dir) throws IOException {
    final int cardinality = set.cardinality();
    final byte denseRankPower = 9; // Not tested here so fixed to isolate factors
    int jumpTableentryCount;
    try (IndexOutput out = dir.createOutput("bar", IOContext.DEFAULT)) {
      jumpTableentryCount =
          IndexedDISI.writeBitSet(new BitSetIterator(set, cardinality), out, denseRankPower);
    }

    try (IndexInput in = dir.openInput("bar", IOContext.DEFAULT)) {
      BitSetIterator disi2 = new BitSetIterator(set, cardinality);
      int doc = disi2.docID();
      int index = 0;
      while (doc < cardinality) {
        doc = disi2.nextDoc();
        index++;
      }

      IndexedDISI disi =
          new IndexedDISI(in, 0L, in.length(), jumpTableentryCount, denseRankPower, cardinality);
      // Advance 1 docID beyond end
      assertFalse(
          "There should be no set bit beyond the valid docID range",
          disi.advanceExact(set.length()));
      disi.advance(
          doc); // Should be the special docID signifyin NO_MORE_DOCS from the BitSetIterator
      assertEquals(
          "The index when advancing beyond the last defined docID should be correct",
          index,
          disi.index() + 1); // disi.index()+1 as the while-loop also counts the NO_MORE_DOCS
    }
  }

  // TODO: can this be toned down?
  @Nightly
  public void testRandomBlocks() throws IOException {
    final int BLOCKS = 5;
    BitSet set = createSetWithRandomBlocks(BLOCKS);
    try (Directory dir = newDirectory()) {
      doTestAllSingleJump(set, dir);
    }
  }

  // When doing merges in Lucene80NormsProducer, IndexedDISI are created from slices where the
  // offset is not 0
  public void testPositionNotZero() throws IOException {
    final int BLOCKS = 10;
    final byte denseRankPower =
        rarely() ? -1 : (byte) (random().nextInt(7) + 7); // sane + chance of disable

    BitSet set = createSetWithRandomBlocks(BLOCKS);
    try (Directory dir = newDirectory()) {
      final int cardinality = set.cardinality();
      int jumpTableEntryCount;
      try (IndexOutput out = dir.createOutput("foo", IOContext.DEFAULT)) {
        jumpTableEntryCount =
            IndexedDISI.writeBitSet(new BitSetIterator(set, cardinality), out, denseRankPower);
      }
      try (IndexInput fullInput = dir.openInput("foo", IOContext.DEFAULT)) {
        IndexInput blockData =
            IndexedDISI.createBlockSlice(
                fullInput, "blocks", 0, fullInput.length(), jumpTableEntryCount);
        blockData.seek(random().nextInt((int) blockData.length()));

        RandomAccessInput jumpTable =
            IndexedDISI.createJumpTable(fullInput, 0, fullInput.length(), jumpTableEntryCount);
        IndexedDISI disi =
            new IndexedDISI(blockData, jumpTable, jumpTableEntryCount, denseRankPower, cardinality);
        // This failed at some point during LUCENE-8585 development as it did not reset the slice
        // position
        disi.advanceExact(BLOCKS * 65536 - 1);
      }
    }
  }

  private BitSet createSetWithRandomBlocks(int blockCount) {
    final int B = 65536;
    BitSet set = new SparseFixedBitSet(blockCount * B);
    for (int block = 0; block < blockCount; block++) {
      switch (random().nextInt(4)) {
        case 0:
          { // EMPTY
            break;
          }
        case 1:
          { // ALL
            for (int docID = block * B; docID < (block + 1) * B; docID++) {
              set.set(docID);
            }
            break;
          }
        case 2:
          { // SPARSE ( < 4096 )
            for (int docID = block * B; docID < (block + 1) * B; docID += 101) {
              set.set(docID);
            }
            break;
          }
        case 3:
          { // DENSE ( >= 4096 )
            for (int docID = block * B; docID < (block + 1) * B; docID += 3) {
              set.set(docID);
            }
            break;
          }
        default:
          throw new IllegalStateException(
              "Modulo logic error: there should only be 4 possibilities");
      }
    }
    return set;
  }

  private void doTestAllSingleJump(BitSet set, Directory dir) throws IOException {
    final int cardinality = set.cardinality();
    final byte denseRankPower =
        rarely() ? -1 : (byte) (random().nextInt(7) + 7); // sane + chance of disable
    long length;
    int jumpTableentryCount;
    try (IndexOutput out = dir.createOutput("foo", IOContext.DEFAULT)) {
      jumpTableentryCount =
          IndexedDISI.writeBitSet(new BitSetIterator(set, cardinality), out, denseRankPower);
      length = out.getFilePointer();
    }

    try (IndexInput in = dir.openInput("foo", IOContext.DEFAULT)) {
      for (int i = 0; i < set.length(); i++) {
        IndexedDISI disi =
            new IndexedDISI(in, 0L, length, jumpTableentryCount, denseRankPower, cardinality);
        assertEquals(
            "The bit at " + i + " should be correct with advanceExact",
            set.get(i),
            disi.advanceExact(i));

        IndexedDISI disi2 =
            new IndexedDISI(in, 0L, length, jumpTableentryCount, denseRankPower, cardinality);
        disi2.advance(i);
        // Proper sanity check with jump tables as an error could make them seek backwards
        assertTrue(
            "The docID should at least be "
                + i
                + " after advance("
                + i
                + ") but was "
                + disi2.docID(),
            i <= disi2.docID());
        if (set.get(i)) {
          assertEquals("The docID should be present with advance", i, disi2.docID());
        } else {
          assertNotSame("The docID should not be present with advance", i, disi2.docID());
        }
      }
    }
  }

  public void testOneDoc() throws IOException {
    int maxDoc = TestUtil.nextInt(random(), 1, 100000);
    BitSet set = new SparseFixedBitSet(maxDoc);
    set.set(random().nextInt(maxDoc));
    try (Directory dir = newDirectory()) {
      doTest(set, dir);
    }
  }

  public void testTwoDocs() throws IOException {
    int maxDoc = TestUtil.nextInt(random(), 1, 100000);
    BitSet set = new SparseFixedBitSet(maxDoc);
    set.set(random().nextInt(maxDoc));
    set.set(random().nextInt(maxDoc));
    try (Directory dir = newDirectory()) {
      doTest(set, dir);
    }
  }

  public void testAllDocs() throws IOException {
    int maxDoc = TestUtil.nextInt(random(), 1, 100000);
    FixedBitSet set = new FixedBitSet(maxDoc);
    set.set(1, maxDoc);
    try (Directory dir = newDirectory()) {
      doTest(set, dir);
    }
  }

  public void testHalfFull() throws IOException {
    int maxDoc = TestUtil.nextInt(random(), 1, 100000);
    BitSet set = new SparseFixedBitSet(maxDoc);
    for (int i = random().nextInt(2); i < maxDoc; i += TestUtil.nextInt(random(), 1, 3)) {
      set.set(i);
    }
    try (Directory dir = newDirectory()) {
      doTest(set, dir);
    }
  }

  public void testDocRange() throws IOException {
    try (Directory dir = newDirectory()) {
      for (int iter = 0; iter < 10; ++iter) {
        int maxDoc = TestUtil.nextInt(random(), 1, 1000000);
        FixedBitSet set = new FixedBitSet(maxDoc);
        final int start = random().nextInt(maxDoc);
        final int end = TestUtil.nextInt(random(), start + 1, maxDoc);
        set.set(start, end);
        doTest(set, dir);
      }
    }
  }

  public void testSparseDenseBoundary() throws IOException {
    try (Directory dir = newDirectory()) {
      FixedBitSet set = new FixedBitSet(200000);
      int start = 65536 + random().nextInt(100);
      final byte denseRankPower =
          rarely() ? -1 : (byte) (random().nextInt(7) + 7); // sane + chance of disable

      // we set MAX_ARRAY_LENGTH bits so the encoding will be sparse
      set.set(start, start + IndexedDISI.MAX_ARRAY_LENGTH);
      long length;
      int jumpTableEntryCount;
      try (IndexOutput out = dir.createOutput("sparse", IOContext.DEFAULT)) {
        jumpTableEntryCount =
            IndexedDISI.writeBitSet(
                new BitSetIterator(set, IndexedDISI.MAX_ARRAY_LENGTH), out, denseRankPower);
        length = out.getFilePointer();
      }
      try (IndexInput in = dir.openInput("sparse", IOContext.DEFAULT)) {
        IndexedDISI disi =
            new IndexedDISI(
                in, 0L, length, jumpTableEntryCount, denseRankPower, IndexedDISI.MAX_ARRAY_LENGTH);
        assertEquals(start, disi.nextDoc());
        assertEquals(IndexedDISI.Method.SPARSE, disi.method);
      }
      doTest(set, dir);

      // now we set one more bit so the encoding will be dense
      set.set(start + IndexedDISI.MAX_ARRAY_LENGTH + random().nextInt(100));
      try (IndexOutput out = dir.createOutput("bar", IOContext.DEFAULT)) {
        IndexedDISI.writeBitSet(
            new BitSetIterator(set, IndexedDISI.MAX_ARRAY_LENGTH + 1), out, denseRankPower);
        length = out.getFilePointer();
      }
      try (IndexInput in = dir.openInput("bar", IOContext.DEFAULT)) {
        IndexedDISI disi =
            new IndexedDISI(
                in,
                0L,
                length,
                jumpTableEntryCount,
                denseRankPower,
                IndexedDISI.MAX_ARRAY_LENGTH + 1);
        assertEquals(start, disi.nextDoc());
        assertEquals(IndexedDISI.Method.DENSE, disi.method);
      }
      doTest(set, dir);
    }
  }

  public void testOneDocMissing() throws IOException {
    int maxDoc = TestUtil.nextInt(random(), 1, 1000000);
    FixedBitSet set = new FixedBitSet(maxDoc);
    set.set(0, maxDoc);
    set.clear(random().nextInt(maxDoc));
    try (Directory dir = newDirectory()) {
      doTest(set, dir);
    }
  }

  public void testFewMissingDocs() throws IOException {
    try (Directory dir = newDirectory()) {
      int numIters = atLeast(10);
      for (int iter = 0; iter < numIters; ++iter) {
        int maxDoc = TestUtil.nextInt(random(), 1, 100000);
        FixedBitSet set = new FixedBitSet(maxDoc);
        set.set(0, maxDoc);
        final int numMissingDocs = TestUtil.nextInt(random(), 2, 1000);
        for (int i = 0; i < numMissingDocs; ++i) {
          set.clear(random().nextInt(maxDoc));
        }
        doTest(set, dir);
      }
    }
  }

  public void testDenseMultiBlock() throws IOException {
    try (Directory dir = newDirectory()) {
      int maxDoc = 10 * 65536; // 10 blocks
      FixedBitSet set = new FixedBitSet(maxDoc);
      for (int i = 0; i < maxDoc; i += 2) { // Set every other to ensure dense
        set.set(i);
      }
      doTest(set, dir);
    }
  }

  public void testDenseBitSizeLessThanBlockSize() throws IOException {
    final byte denseRankPower = (byte) (random().nextInt(7) + 7);
    try (Directory dir = newDirectory()) {
      // initialize a maxDoc that is less than IndexedDISI.BLOCK_SIZE
      int maxDoc = random().nextInt(4096 * 2, 65536);
      FixedBitSet set = new FixedBitSet(maxDoc);
      for (int i = 0; i < maxDoc; i += 2) { // Set every other to ensure dense
        set.set(i);
      }
      int jumpTableEntryCount; // this should always be 0 given that maxDoc < BLOCK_SIZE
      long length;
      try (IndexOutput out = dir.createOutput("foo", IOContext.DEFAULT)) {
        jumpTableEntryCount =
            IndexedDISI.writeBitSet(
                new BitSetIterator(set, set.cardinality()), out, denseRankPower);
        length = out.getFilePointer();
        assertTrue(
            "jumpTableEntryCount should be 0 for dense bitsets with size < BLOCK_SIZE",
            0 == jumpTableEntryCount);
      }
      try (IndexInput in = dir.openInput("foo", IOContext.DEFAULT)) {
        IndexedDISI disi =
            new IndexedDISI(in, 0L, length, jumpTableEntryCount, denseRankPower, set.cardinality());
        FixedBitSet disiSet = new FixedBitSet(maxDoc);
        // This would throw IOOB if bitset size is not handled correctly as per #14882
        disiSet.or(disi);
      }
    }
  }

  public void testIllegalDenseRankPower() throws IOException {

    // Legal values
    for (byte denseRankPower : new byte[] {-1, 7, 8, 9, 10, 11, 12, 13, 14, 15}) {
      createAndOpenDISI(denseRankPower, denseRankPower);
    }

    // Illegal values
    for (byte denseRankPower : new byte[] {-2, 0, 1, 6, 16}) {
      expectThrows(
          IllegalArgumentException.class,
          () -> {
            createAndOpenDISI(
                denseRankPower, (byte) 8); // Illegal write, legal read (should not reach read)
          });

      expectThrows(
          IllegalArgumentException.class,
          () -> {
            createAndOpenDISI(
                (byte) 8, denseRankPower); // Legal write, illegal read (should reach read)
          });
    }
  }

  private void createAndOpenDISI(byte denseRankPowerWrite, byte denseRankPowerRead)
      throws IOException {
    BitSet set = new FixedBitSet(10);
    set.set(set.length() - 1);
    try (Directory dir = newDirectory()) {
      long length;
      int jumpTableEntryCount = -1;
      try (IndexOutput out = dir.createOutput("foo", IOContext.DEFAULT)) {
        jumpTableEntryCount =
            IndexedDISI.writeBitSet(
                new BitSetIterator(set, set.cardinality()), out, denseRankPowerWrite);
        length = out.getFilePointer();
      }
      try (IndexInput in = dir.openInput("foo", IOContext.DEFAULT)) {
        new IndexedDISI(in, 0L, length, jumpTableEntryCount, denseRankPowerRead, set.cardinality());
      }
      // This tests the legality of the denseRankPower only, so we don't do anything with the disi
    }
  }

  public void testOneDocMissingFixed() throws IOException {
    int maxDoc = 9699;
    final byte denseRankPower =
        rarely() ? -1 : (byte) (random().nextInt(7) + 7); // sane + chance of disable
    FixedBitSet set = new FixedBitSet(maxDoc);
    set.set(0, maxDoc);
    set.clear(1345);
    try (Directory dir = newDirectory()) {

      final int cardinality = set.cardinality();
      long length;
      int jumpTableentryCount;
      try (IndexOutput out = dir.createOutput("foo", IOContext.DEFAULT)) {
        jumpTableentryCount =
            IndexedDISI.writeBitSet(new BitSetIterator(set, cardinality), out, denseRankPower);
        length = out.getFilePointer();
      }

      int step = 16000;
      try (IndexInput in = dir.openInput("foo", IOContext.DEFAULT)) {
        IndexedDISI disi =
            new IndexedDISI(in, 0L, length, jumpTableentryCount, denseRankPower, cardinality);
        BitSetIterator disi2 = new BitSetIterator(set, cardinality);
        assertAdvanceEquality(disi, disi2, step);
      }
    }
  }

  public void testRandom() throws IOException {
    try (Directory dir = newDirectory()) {
      int numIters = atLeast(3);
      for (int i = 0; i < numIters; ++i) {
        doTestRandom(dir);
      }
    }
  }

  private void doTestRandom(Directory dir) throws IOException {
    Random random = random();
    final int maxStep = TestUtil.nextInt(random, 1, 1 << TestUtil.nextInt(random, 2, 20));
    final int numDocs =
        TestUtil.nextInt(random, 1, Math.min(100000, (Integer.MAX_VALUE - 1) / maxStep));
    BitSet docs = new SparseFixedBitSet(numDocs * maxStep + 1);
    int lastDoc = -1;
    for (int doc = -1, i = 0; i < numDocs; ++i) {
      doc += TestUtil.nextInt(random, 1, maxStep);
      docs.set(doc);
      lastDoc = doc;
    }
    final int maxDoc = lastDoc + TestUtil.nextInt(random, 1, 100);

    BitSet set = BitSet.of(new BitSetIterator(docs, docs.approximateCardinality()), maxDoc);
    doTest(set, dir);
  }

  private void doTest(BitSet set, Directory dir) throws IOException {
    final int cardinality = set.cardinality();
    final byte denseRankPower =
        rarely() ? -1 : (byte) (random().nextInt(7) + 7); // sane + chance of disable
    long length;
    int jumpTableentryCount;
    try (IndexOutput out = dir.createOutput("foo", IOContext.DEFAULT)) {
      jumpTableentryCount =
          IndexedDISI.writeBitSet(new BitSetIterator(set, cardinality), out, denseRankPower);
      length = out.getFilePointer();
    }

    try (IndexInput in = dir.openInput("foo", IOContext.DEFAULT)) {
      IndexedDISI disi =
          new IndexedDISI(in, 0L, length, jumpTableentryCount, denseRankPower, cardinality);
      BitSetIterator disi2 = new BitSetIterator(set, cardinality);
      assertSingleStepEquality(disi, disi2);
    }

    for (int step : new int[] {1, 10, 100, 1000, 10000, 100000}) {
      try (IndexInput in = dir.openInput("foo", IOContext.DEFAULT)) {
        IndexedDISI disi =
            new IndexedDISI(in, 0L, length, jumpTableentryCount, denseRankPower, cardinality);
        BitSetIterator disi2 = new BitSetIterator(set, cardinality);
        assertAdvanceEquality(disi, disi2, step);
      }
    }

    for (int step : new int[] {10, 100, 1000, 10000, 100000}) {
      try (IndexInput in = dir.openInput("foo", IOContext.DEFAULT)) {
        IndexedDISI disi =
            new IndexedDISI(in, 0L, length, jumpTableentryCount, denseRankPower, cardinality);
        BitSetIterator disi2 = new BitSetIterator(set, cardinality);
        int disi2length = set.length();
        assertAdvanceExactRandomized(disi, disi2, disi2length, step);
      }
    }

    for (int step : new int[] {100, 1000, 10000, 100000}) {
      try (IndexInput in = dir.openInput("foo", IOContext.DEFAULT)) {
        IndexedDISI disi =
            new IndexedDISI(in, 0L, length, jumpTableentryCount, denseRankPower, cardinality);
        BitSetIterator disi2 = new BitSetIterator(set, cardinality);
        int disi2length = set.length();
        assertIntoBitsetRandomized(disi, disi2, disi2length, step);
      }
    }

    for (int step : new int[] {100, 1000, 10000, 100000}) {
      try (IndexInput in = dir.openInput("foo", IOContext.DEFAULT)) {
        IndexedDISI disi =
            new IndexedDISI(in, 0L, length, jumpTableentryCount, denseRankPower, cardinality);
        BitSetIterator disi2 = new BitSetIterator(set, cardinality);
        int disi2length = set.length();
        assertDocIDRunEndRandomized(disi, disi2, disi2length, step);
      }
    }

    dir.deleteFile("foo");
  }

  private void assertAdvanceExactRandomized(
      IndexedDISI disi, BitSetIterator disi2, int disi2length, int step) throws IOException {
    int index = -1;
    Random random = random();
    for (int target = 0; target < disi2length; ) {
      target += TestUtil.nextInt(random, 0, step);
      int doc = disi2.docID();
      while (doc < target) {
        doc = disi2.nextDoc();
        index++;
      }

      boolean exists = disi.advanceExact(target);
      assertEquals(doc == target, exists);
      if (exists) {
        assertEquals(index, disi.index());
      } else if (random.nextBoolean()) {
        assertEquals(doc, disi.nextDoc());
        // This is a bit strange when doc == NO_MORE_DOCS as the index overcounts in the disi2
        // while-loop
        assertEquals(index, disi.index());
        target = doc;
      }
    }
  }

  private void assertIntoBitsetRandomized(
      IndexedDISI disi, BitSetIterator disi2, int disi2length, int step) throws IOException {
    int index = -1;
    FixedBitSet set1 = new FixedBitSet(step);
    FixedBitSet set2 = new FixedBitSet(step);

    for (int upTo = 0; upTo < disi2length; ) {
      int lastUpTo = upTo;
      upTo += TestUtil.nextInt(random(), 0, step);
      int offset = TestUtil.nextInt(random(), lastUpTo, upTo);

      if (disi.docID() < offset) {
        disi.advance(offset);
      }
      int doc = disi2.docID();
      while (doc < offset) {
        index++;
        doc = disi2.nextDoc();
      }
      while (doc < upTo) {
        set2.set(doc - offset);
        index++;
        doc = disi2.nextDoc();
      }

      disi.intoBitSet(upTo, set1, offset);
      assertEquals(index, disi.index());
      assertEquals(disi2.docID(), disi.docID());

      BitSetIterator expected = new BitSetIterator(set2, set2.cardinality());
      BitSetIterator actual = new BitSetIterator(set1, set1.cardinality());
      for (int expectedDoc = expected.nextDoc();
          expectedDoc != DocIdSetIterator.NO_MORE_DOCS;
          expectedDoc = expected.nextDoc()) {
        int actualDoc = actual.nextDoc();
        assertEquals(expectedDoc + offset, actualDoc + offset); // plus offset for better message.
      }
      assertEquals(DocIdSetIterator.NO_MORE_DOCS, actual.nextDoc());

      if (disi2.docID() != DocIdSetIterator.NO_MORE_DOCS) {
        assertEquals(disi2.nextDoc(), disi.nextDoc());
        assertEquals(++index, disi.index());
      }

      set1.clear();
      set2.clear();
    }
  }

  private void assertDocIDRunEndRandomized(
      IndexedDISI disi, BitSetIterator disi2, int disi2length, int step) throws IOException {
    for (int target = 0; target < disi2length; ) {
      target += TestUtil.nextInt(random(), 0, step);
      if (disi.docID() < target) {
        disi.advance(target);
        disi2.advance(target);
        assertEquals(disi2.docID(), disi.docID());
        int end = disi.docIDRunEnd();
        assertNotEquals(0, end);
        for (int it = disi.docID(); it != DocIdSetIterator.NO_MORE_DOCS && it + 1 < end; it++) {
          assertEquals(it + 1, disi.nextDoc());
          assertEquals(it + 1, disi2.nextDoc());
        }
      }
    }
  }

  private void assertSingleStepEquality(IndexedDISI disi, BitSetIterator disi2) throws IOException {
    int i = 0;
    for (int doc = disi2.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = disi2.nextDoc()) {
      assertEquals(doc, disi.nextDoc());
      assertEquals(i++, disi.index());
    }
    assertEquals(DocIdSetIterator.NO_MORE_DOCS, disi.nextDoc());
  }

  private void assertAdvanceEquality(IndexedDISI disi, BitSetIterator disi2, int step)
      throws IOException {
    int index = -1;
    while (true) {
      int target = disi2.docID() + step;
      int doc;
      do {
        doc = disi2.nextDoc();
        index++;
      } while (doc < target);
      assertEquals(doc, disi.advance(target));
      if (doc == DocIdSetIterator.NO_MORE_DOCS) {
        break;
      }
      assertEquals(
          "Expected equality using step " + step + " at docID " + doc, index, disi.index());
    }
  }
}
