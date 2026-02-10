package uk.org.openseizuredetector;

import org.junit.Test;
import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;

import uk.org.openseizuredetector.alg.AlgorithmResult;
import uk.org.openseizuredetector.alg.VotingStrategy;

/**
 * Unit tests for VotingStrategy class.
 * Tests different voting strategies for combining algorithm results.
 */
public class VotingStrategyTest {

    @Test
    public void testAnyVotingStrategy() {
        VotingStrategy strategy = new VotingStrategy(VotingStrategy.Strategy.ANY);
        List<AlgorithmResult> results = new ArrayList<>();
        
        // Test: No alarms - should return OK (0)
        results.add(new AlgorithmResult("OSD", 0, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 0, 1.0f, 1.0f));
        assertEquals(0, strategy.vote(results));
        
        // Test: One alarm - should return ALARM (2)
        results.clear();
        results.add(new AlgorithmResult("OSD", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 0, 1.0f, 1.0f));
        assertEquals(2, strategy.vote(results));
        
        // Test: One warning - should return WARNING (1)
        results.clear();
        results.add(new AlgorithmResult("OSD", 0, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 1, 1.0f, 1.0f));
        assertEquals(1, strategy.vote(results));
    }

    @Test
    public void testMajorityVotingStrategy() {
        VotingStrategy strategy = new VotingStrategy(VotingStrategy.Strategy.MAJORITY);
        List<AlgorithmResult> results = new ArrayList<>();
        
        // Test: Two out of three algorithms report alarm - should return ALARM (2)
        results.add(new AlgorithmResult("OSD", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("ML", 0, 1.0f, 1.0f));
        assertEquals(2, strategy.vote(results));
        
        // Test: One out of three algorithms report alarm - should not return ALARM
        results.clear();
        results.add(new AlgorithmResult("OSD", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 0, 1.0f, 1.0f));
        results.add(new AlgorithmResult("ML", 0, 1.0f, 1.0f));
        assertEquals(1, strategy.vote(results)); // Should be WARNING
        
        // Test: Two out of two - majority achieved
        results.clear();
        results.add(new AlgorithmResult("OSD", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 2, 1.0f, 1.0f));
        assertEquals(2, strategy.vote(results));
    }

    @Test
    public void testUnanimousVotingStrategy() {
        VotingStrategy strategy = new VotingStrategy(VotingStrategy.Strategy.UNANIMOUS);
        List<AlgorithmResult> results = new ArrayList<>();
        
        // Test: All algorithms report alarm - should return ALARM (2)
        results.add(new AlgorithmResult("OSD", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("ML", 2, 1.0f, 1.0f));
        assertEquals(2, strategy.vote(results));
        
        // Test: Not all algorithms report alarm - should not return ALARM
        results.clear();
        results.add(new AlgorithmResult("OSD", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("ML", 0, 1.0f, 1.0f));
        assertEquals(0, strategy.vote(results)); // Should be OK since not unanimous
        
        // Test: One warning among alarms
        results.clear();
        results.add(new AlgorithmResult("OSD", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 1, 1.0f, 1.0f));
        assertEquals(1, strategy.vote(results)); // Should be WARNING
    }

    @Test
    public void testWeightedVotingStrategy() {
        VotingStrategy strategy = new VotingStrategy(VotingStrategy.Strategy.WEIGHTED);
        List<AlgorithmResult> results = new ArrayList<>();
        
        // Test: Weighted average > 1.5 should return ALARM (2)
        // (2 * 1.0 * 1.0 + 2 * 1.0 * 1.0) / 2.0 = 2.0
        results.add(new AlgorithmResult("OSD", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 2, 1.0f, 1.0f));
        assertEquals(2, strategy.vote(results));
        
        // Test: Weighted average between 0.5 and 1.5 should return WARNING (1)
        // (2 * 1.0 * 1.0 + 0 * 1.0 * 1.0) / 2.0 = 1.0
        results.clear();
        results.add(new AlgorithmResult("OSD", 2, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 0, 1.0f, 1.0f));
        assertEquals(1, strategy.vote(results));
        
        // Test: Weighted average < 0.5 should return OK (0)
        results.clear();
        results.add(new AlgorithmResult("OSD", 0, 1.0f, 1.0f));
        results.add(new AlgorithmResult("HR", 0, 1.0f, 1.0f));
        assertEquals(0, strategy.vote(results));
        
        // Test: Different weights
        // (2 * 1.0 * 2.0 + 0 * 1.0 * 1.0) / 3.0 = 1.333
        results.clear();
        results.add(new AlgorithmResult("OSD", 2, 1.0f, 2.0f)); // Higher weight
        results.add(new AlgorithmResult("HR", 0, 1.0f, 1.0f));
        assertEquals(1, strategy.vote(results)); // Should be WARNING
    }

    @Test
    public void testWeightedVotingWithConfidence() {
        VotingStrategy strategy = new VotingStrategy(VotingStrategy.Strategy.WEIGHTED);
        List<AlgorithmResult> results = new ArrayList<>();
        
        // Test: Low confidence should reduce impact
        // (2 * 0.3 * 1.0 + 0 * 1.0 * 1.0) / 2.0 = 0.3
        results.add(new AlgorithmResult("ML", 2, 0.3f, 1.0f)); // Low confidence
        results.add(new AlgorithmResult("HR", 0, 1.0f, 1.0f));
        assertEquals(0, strategy.vote(results)); // Should be OK due to low confidence
        
        // Test: High confidence
        // (2 * 0.9 * 1.0 + 0 * 1.0 * 1.0) / 2.0 = 0.9
        results.clear();
        results.add(new AlgorithmResult("ML", 2, 0.9f, 1.0f)); // High confidence
        results.add(new AlgorithmResult("HR", 0, 1.0f, 1.0f));
        assertEquals(1, strategy.vote(results)); // Should be WARNING
    }

    @Test
    public void testFromString() {
        assertEquals(VotingStrategy.Strategy.ANY, VotingStrategy.fromString("ANY"));
        assertEquals(VotingStrategy.Strategy.ANY, VotingStrategy.fromString("any"));
        assertEquals(VotingStrategy.Strategy.MAJORITY, VotingStrategy.fromString("MAJORITY"));
        assertEquals(VotingStrategy.Strategy.UNANIMOUS, VotingStrategy.fromString("UNANIMOUS"));
        assertEquals(VotingStrategy.Strategy.WEIGHTED, VotingStrategy.fromString("WEIGHTED"));
        assertEquals(VotingStrategy.Strategy.ANY, VotingStrategy.fromString("invalid"));
        assertEquals(VotingStrategy.Strategy.ANY, VotingStrategy.fromString(null));
        assertEquals(VotingStrategy.Strategy.ANY, VotingStrategy.fromString(""));
    }

    @Test
    public void testEmptyResultsList() {
        VotingStrategy strategy = new VotingStrategy(VotingStrategy.Strategy.ANY);
        List<AlgorithmResult> results = new ArrayList<>();
        
        // Empty list should return OK (0)
        assertEquals(0, strategy.vote(results));
        
        // Null list should return OK (0)
        assertEquals(0, strategy.vote(null));
    }
}

