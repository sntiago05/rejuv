package sntiago05.rejuv.domain;

/** Rank used to pick benchmarks. tier 0..11, subrank 0 when unknown. */
public record MatchRank(int tier, int subrank) {
}
