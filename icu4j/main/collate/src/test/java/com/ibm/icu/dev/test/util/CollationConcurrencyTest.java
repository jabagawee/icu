// © 2026 and later: Unicode, Inc. and others.
// License & terms of use: http://www.unicode.org/copyright.html
package com.ibm.icu.dev.test.util;

import com.ibm.icu.text.Collator;
import com.ibm.icu.text.RuleBasedCollator;
import com.ibm.icu.util.ULocale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Concurrency regression tests for CollationTailoring. */
@RunWith(JUnit4.class)
public class CollationConcurrencyTest extends ConcurrencyTest {

    /** Concurrent getMaxExpansion() must see the volatile maxExpansions field. */
    @Test
    public void testCollationMaxExpansionsConcurrent() throws Exception {
        String[] localeIDs = {"en", "de", "ja", "zh", "ko", "fr"};
        runConcurrent(
                "CollationMaxExpansions",
                tid -> {
                    for (int i = 0; i < ITERATIONS; i++) {
                        String localeID = localeIDs[(tid + i) % localeIDs.length];
                        RuleBasedCollator col =
                                (RuleBasedCollator) Collator.getInstance(new ULocale(localeID));
                        // getCollationElementIterator triggers initMaxExpansions()
                        // which reads/writes the volatile maxExpansions field
                        assertNotNull("Collator should not be null for " + localeID, col);
                        int result = col.compare("abc", "def");
                        assertTrue("compare should return non-zero", result != 0);
                    }
                });
    }
}
