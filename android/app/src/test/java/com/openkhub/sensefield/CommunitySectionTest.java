package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

/**
 * Contract tests for the community sections.
 *
 * <p>The load-bearing assertion is the unknown-key one: a key this build does not recognise must
 * come back as null rather than quietly becoming the first section. A post silently filed under
 * 「活动」 that the author never chose is invisible to a screen-reader user and cannot be undone
 * from the phone.
 */
public final class CommunitySectionTest {

    @Test public void hasExactlySevenSections() {
        assertEquals(7, CommunitySection.values().length);
    }

    @Test public void labelsMatchTheEnumOrderAndCount() {
        String[] labels = CommunitySection.labels();
        CommunitySection[] sections = CommunitySection.values();
        assertEquals(sections.length, labels.length);
        for (int i = 0; i < sections.length; i++) {
            assertEquals(sections[i].label(), labels[i]);
        }
    }

    @Test public void everyLabelIsReadable() {
        for (String label : CommunitySection.labels()) {
            assertNotNull(label);
            if (label.trim().isEmpty()) fail("板块名不能是空的");
        }
    }

    @Test public void knownKeysRoundTrip() {
        for (CommunitySection section : CommunitySection.values()) {
            assertEquals(section, CommunitySection.fromKey(section.key()));
        }
    }

    @Test public void keysAreUnique() {
        Set<String> seen = new HashSet<String>();
        for (CommunitySection section : CommunitySection.values()) {
            assertTrue("板块 key 重复：" + section.key(), seen.add(section.key()));
        }
        assertEquals(CommunitySection.values().length, seen.size());
    }

    @Test public void unknownKeyIsNullNotTheFirstSection() {
        assertNull(CommunitySection.fromKey("nope"));
        assertNull(CommunitySection.fromKey(""));
        assertNull(CommunitySection.fromKey("活动"));  // 中文名不是 key
        assertNull(CommunitySection.fromKey("ACTIVITY"));  // 大小写敏感
        assertNull(CommunitySection.fromKey("activity "));  // 首尾空格也算不认识
    }

    @Test public void nullKeyIsNull() {
        assertNull(CommunitySection.fromKey(null));
    }

    @Test public void unknownKeyLookupIsRepeatable() {
        // 同一次调用返回 null 就够了，连续两次必须一致——不能第一次认、第二次不认。
        assertNull(CommunitySection.fromKey("nope"));
        assertNull(CommunitySection.fromKey("nope"));
    }
}
