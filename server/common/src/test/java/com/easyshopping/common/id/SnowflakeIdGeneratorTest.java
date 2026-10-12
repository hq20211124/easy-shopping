package com.easyshopping.common.id;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnowflakeIdGeneratorTest {

    @Test
    void idsAreUniqueAndPositive() {
        SnowflakeIdGenerator generator = new SnowflakeIdGenerator(3);
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            long id = generator.nextId();
            assertTrue(id > 0);
            assertTrue(ids.add(id), "duplicate id: " + id);
        }
        assertEquals(10_000, ids.size());
    }

    @Test
    void workerIdOutOfRangeRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(32));
        assertThrows(IllegalArgumentException.class, () -> new SnowflakeIdGenerator(-1));
    }
}
