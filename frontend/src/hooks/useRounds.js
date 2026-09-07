import { useCallback } from 'react';
import { candidatesApi, interviewersApi } from '../api/endpoints';
import { useFetch } from './useAsync';

/**
 * There is no "list every interview" endpoint on the backend. The staff-facing
 * interview list is assembled the only way the API allows: list candidates, then read
 * each one's pipeline (which carries every process with all of its rounds).
 *
 * That's one request per candidate, so it's fine for a demo-sized dataset and would
 * want a real aggregate endpoint before it grew. Individual pipeline failures are
 * skipped rather than failing the whole page.
 */
export function useAllRounds() {
  const fetcher = useCallback(async () => {
    const candidates = await candidatesApi.list();
    const pipelines = await Promise.all(
      candidates.map((c) =>
        candidatesApi
          .pipeline(c.id)
          .then((entries) => ({ candidate: c, entries }))
          .catch(() => ({ candidate: c, entries: [] })),
      ),
    );

    const rounds = [];
    for (const { candidate, entries } of pipelines) {
      for (const entry of entries || []) {
        for (const round of entry.rounds || []) {
          rounds.push({ ...round, candidate, process: entry.process });
        }
      }
    }
    // Scheduled first (soonest first), then everything unscheduled.
    rounds.sort((a, b) => {
      if (!a.scheduledStart && !b.scheduledStart) return 0;
      if (!a.scheduledStart) return 1;
      if (!b.scheduledStart) return -1;
      return new Date(a.scheduledStart) - new Date(b.scheduledStart);
    });
    return { candidates, pipelines, rounds };
  }, []);

  return useFetch(fetcher, []);
}

/** interviewerId -> name, for labelling slots and assigned rounds. */
export function useInterviewerNames() {
  const { data, ...rest } = useFetch(() => interviewersApi.list(), []);
  const byId = {};
  const byUserId = {};
  for (const i of data || []) {
    byId[i.id] = i.name;
    byUserId[i.userId] = i.name;
  }
  return { interviewers: data || [], namesById: byId, namesByUserId: byUserId, ...rest };
}
