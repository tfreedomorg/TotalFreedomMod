package me.totalfreedom.totalfreedommod.cmd.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

public class FuzzyMatch
{
    private FuzzyMatch() {}

    public static boolean matches(String candidate, String query)
    {
        return score(candidate, query) != null;
    }

    /**
     * Filters {@code candidates} to those fuzzy-matching {@code query}, sorted by match quality
     * (tighter, earlier matches first). An empty {@code query} returns all candidates, unsorted.
     */
    public static List<String> filter(List<String> candidates, String query)
    {
        if (query.isEmpty()) return List.copyOf(candidates);

        List<Scored> scored = new ArrayList<>();
        for (String candidate : candidates)
        {
            Integer score = score(candidate, query);
            if (score != null) scored.add(new Scored(candidate, score));
        }
        scored.sort(Comparator.comparingInt(Scored::score));

        return scored.stream().map(Scored::candidate).toList();
    }

    /**
     * Returns only the strongest matches while keeping work and retained results bounded for
     * candidate sources that may intentionally be unlimited.
     */
    public static List<String> filter(List<String> candidates, String query, int limit)
    {
        if (limit < 1)
            return List.of();
        if (query.isEmpty())
            return candidates.stream().limit(limit).toList();

        final Comparator<Scored> strongestFirst = Comparator
                .comparingInt(Scored::score)
                .thenComparing(Scored::candidate, String.CASE_INSENSITIVE_ORDER);
        final PriorityQueue<Scored> strongest = new PriorityQueue<>(limit, strongestFirst.reversed());
        for (final String candidate : candidates)
        {
            final Integer candidateScore = score(candidate, query);
            if (candidateScore == null)
                continue;

            final Scored scoredCandidate = new Scored(candidate, candidateScore);
            if (strongest.size() < limit)
            {
                strongest.add(scoredCandidate);
            }
            else if (strongestFirst.compare(scoredCandidate, strongest.peek()) < 0)
            {
                strongest.poll();
                strongest.add(scoredCandidate);
            }
        }

        return strongest.stream()
                .sorted(strongestFirst)
                .map(Scored::candidate)
                .toList();
    }

    private static Integer score(String candidate, String query)
    {
        String c = candidate.toLowerCase();
        String q = query.toLowerCase();

        int searchFrom = 0;
        int gapPenalty = 0;
        int firstMatch = -1;
        for (int qi = 0; qi < q.length(); qi++)
        {
            int idx = c.indexOf(q.charAt(qi), searchFrom);
            if (idx < 0) return null;
            if (firstMatch < 0) firstMatch = idx;
            gapPenalty += idx - searchFrom;
            searchFrom = idx + 1;
        }
        return firstMatch + gapPenalty;
    }

    private record Scored(String candidate, int score) {}
}
