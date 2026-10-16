import { act } from '@testing-library/react'
import { vi } from 'vitest'
import * as api from '../../api'
import type { Workout, WorkoutEntry, WorkoutPlan } from '../../types'

export const A = '2026-10-16'
export const B = '2026-10-17'
export const dateA = () => new Date(2026, 9, 16, 12)
export const dateB = () => new Date(2026, 9, 17, 12)
export const entry = (date = A, id = 1, name = 'Saved exercise'): WorkoutEntry => ({
  id, workout: { workoutName: name, rep: '4X12', weight: 10, workoutDate: date },
})
export const suggestion = (date = A) => ({ workoutName: `Suggestion ${date}`, rep: '3X12', workoutDate: date })
export const plan: WorkoutPlan = {
  startDate: '2026-10-12', goal: 'general_fitness', equipment: 'bodyweight', weightGuidance: 'Choose a weight.',
  days: [A, B].map((date) => ({ date, focus: 'Strength', workouts: [{ ...suggestion(date), weight: null }],
    activity: null, durationMinutes: 0 })),
}

export function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

export function setupApi() {
  vi.resetAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(dateA())
  const reads: { date: string; pending: ReturnType<typeof deferred<WorkoutEntry[]>> }[] = []
  const writes: { resolve: () => void; reject: (reason: Error) => void }[] = []
  vi.mocked(api.loadWorkoutsByDate).mockImplementation((date) => {
    const pending = deferred<WorkoutEntry[]>()
    reads.push({ date, pending })
    return pending.promise
  })
  const write = (workout: Workout, id: number) => {
    const pending = deferred<WorkoutEntry>()
    writes.push({ resolve: () => pending.resolve({ id, workout }), reject: pending.reject })
    return pending.promise
  }
  vi.mocked(api.createWorkout).mockImplementation((workout) => write(workout, 101))
  vi.mocked(api.updateWorkout).mockImplementation((id, workout) => write(workout, id))
  vi.mocked(api.deleteWorkout).mockImplementation(() => {
    const pending = deferred<void>()
    writes.push({ resolve: () => pending.resolve(), reject: pending.reject })
    return pending.promise
  })
  vi.mocked(api.loadWorkoutPlan).mockResolvedValue(plan)
  return {
    reads, writes,
    read: async (index: number, entries: WorkoutEntry[]) => {
      await act(async () => { reads[index].pending.resolve(entries) })
    },
    write: async (index: number, failure = false) => {
      await act(async () => {
        if (failure) writes[index].reject(new Error('Write failed'))
        else writes[index].resolve()
      })
    },
  }
}
