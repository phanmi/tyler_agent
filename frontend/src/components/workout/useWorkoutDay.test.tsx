import { StrictMode } from 'react'
import { act, cleanup, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '../../api'
import { useWorkoutDay } from './useWorkoutDay'
import { A, B, dateA, dateB, entry, setupApi, suggestion } from './workoutTestSupport'

vi.mock('../../api')
let requests: ReturnType<typeof setupApi>
beforeEach(() => { requests = setupApi() })
afterEach(() => { cleanup(); vi.useRealTimers() })

type Day = ReturnType<typeof useWorkoutDay>
type Kind = 'create' | 'update' | 'delete' | 'schedule'
const kinds: Kind[] = ['create', 'update', 'delete', 'schedule']
function start(day: Day, kind: Kind) {
  if (kind === 'delete') {
    day.requestDelete(entry())
    return day.confirmDelete()
  }
  if (kind === 'schedule') return day.schedule(suggestion())
  if (kind === 'update') day.edit(entry())
  day.changeDraft('workoutName', 'Submitted')
  day.changeDraft('rep', '4X12')
  return day.save()
}

describe('selected-day reads', () => {
  it('ignores old success and failure, including repeated reads for the same date', async () => {
    const { result } = renderHook(useWorkoutDay)
    act(() => { result.current.chooseDate(dateB()) })
    act(() => { void result.current.reload() })
    expect(requests.reads.map((read) => read.date)).toEqual([A, B, B])
    await requests.read(2, [entry(B, 2, 'Current')])
    await requests.read(0, [entry()])
    await act(async () => { requests.reads[1].pending.reject(new Error('Obsolete failure')) })
    expect(result.current.workouts).toEqual([entry(B, 2, 'Current')])
    expect(result.current.loadError).toBe('')
    expect(result.current.loading).toBe(false)
  })

  it('clears stale entries immediately and rejects a response containing another date', async () => {
    const { result } = renderHook(useWorkoutDay)
    await requests.read(0, [entry()])
    act(() => { result.current.chooseDate(dateB()) })
    expect(result.current.workouts).toEqual([])
    expect(result.current.loaded).toBe(false)
    await requests.read(1, [entry()])
    expect(result.current.loaded).toBe(false)
    expect(result.current.loadError).toContain('different date')
    act(() => { result.current.edit(entry()); void result.current.schedule(suggestion(B)) })
    expect(result.current.editingId).toBe(null)
    expect(requests.writes).toHaveLength(0)
  })

  it('survives StrictMode cleanup and ignores the first mount request', async () => {
    const { result } = renderHook(useWorkoutDay, { wrapper: StrictMode })
    expect(requests.reads).toHaveLength(2)
    await requests.read(1, [entry(A, 2)])
    await requests.read(0, [entry(A, 1)])
    expect(result.current.workouts[0].id).toBe(2)
  })
})

describe.each(kinds)('%s workflow', (kind) => {
  it.each([false, true])('completion after navigation cannot affect a newer pending operation (failure=%s)', async (failure) => {
    const { result } = renderHook(useWorkoutDay)
    await requests.read(0, [entry()])
    act(() => { void start(result.current, kind) })
    act(() => { result.current.chooseDate(dateB()) })
    await requests.read(1, [entry(B, 2, 'B record')])
    act(() => {
      result.current.edit(entry(B, 2, 'B record'))
      result.current.changeDraft('workoutName', 'B submitted')
      void result.current.save()
      result.current.changeDraft('workoutName', 'B newer typing')
    })
    const before = result.current
    await requests.write(0, failure)
    expect(requests.reads).toHaveLength(2) // No stale A refresh is started.
    expect(result.current.workouts).toEqual(before.workouts)
    expect(result.current.draft).toEqual(before.draft)
    expect(result.current.editingId).toBe(2)
    expect(result.current.notice).toBe(before.notice)
    expect(result.current.error).toBe(before.error)
    expect(result.current.saving).toBe(true)
    expect(api.updateWorkout).toHaveBeenLastCalledWith(2, expect.objectContaining({ workoutDate: B }))
    await requests.write(1)
    await requests.read(2, [entry(B, 2, 'B submitted')])
    expect(result.current.draft.workoutName).toBe('B newer typing')
    expect(result.current.saving).toBe(false)
  })

  it.each([false, true])('old refresh completion cannot overwrite the new date (failure=%s)', async (failure) => {
    const { result } = renderHook(useWorkoutDay)
    await requests.read(0, [entry()])
    act(() => { void start(result.current, kind) })
    await requests.write(0)
    expect(requests.reads[1].date).toBe(A)
    act(() => { result.current.chooseDate(dateB()) })
    await requests.read(2, [entry(B, 2, 'B record')])
    act(() => {
      result.current.changeDraft('workoutName', 'B draft')
      void result.current.save() // A current validation error must survive the old refresh.
    })
    const before = result.current
    if (failure) await act(async () => { requests.reads[1].pending.reject(new Error('A refresh failed')) })
    else await requests.read(1, [entry(A, 5, 'Old refreshed record')])
    expect(result.current.workouts).toEqual(before.workouts)
    expect(result.current.draft).toEqual(before.draft)
    expect(result.current.notice).toBe(before.notice)
    expect(result.current.error).toBe(before.error)
    expect(result.current.saving).toBe(false)
  })

  it('guards synchronous handler re-entry and conflicting workflows, through the refresh', async () => {
    const { result } = renderHook(useWorkoutDay)
    await requests.read(0, [entry()])
    act(() => {
      void start(result.current, kind)
      void start(result.current, kind)
      void result.current.save()
      void result.current.schedule(suggestion())
      result.current.requestDelete(entry())
      void result.current.confirmDelete()
    })
    expect(requests.writes).toHaveLength(1)
    await requests.write(0)
    act(() => { void result.current.save(); void result.current.schedule(suggestion()) })
    expect(requests.writes).toHaveLength(1)
    await requests.read(1, [])
    expect(result.current.saving).toBe(false)
  })

  it.each(['write', 'refresh'] as const)('ignores completion after unmount during %s', async (stage) => {
    const { result, unmount } = renderHook(useWorkoutDay)
    await requests.read(0, [entry()])
    act(() => { void start(result.current, kind) })
    if (stage === 'refresh') await requests.write(0)
    const readCount = requests.reads.length
    unmount()
    if (stage === 'write') await requests.write(0)
    else await requests.read(1, [entry()])
    expect(requests.reads).toHaveLength(readCount)
  })
})

it.each(['create', 'update'] as const)('preserves newer typing after %s, and retains the intended stable ID/date', async (kind) => {
  const { result } = renderHook(useWorkoutDay)
  await requests.read(0, [entry()])
  act(() => { void start(result.current, kind) })
  act(() => { result.current.changeDraft('workoutName', 'Newer name') })
  await requests.write(0)
  await requests.read(1, [entry(A, kind === 'create' ? 101 : 1, 'Submitted')])
  expect(result.current.draft.workoutName).toBe('Newer name')
  expect(result.current.editingId).toBe(kind === 'create' ? 101 : 1)
  act(() => { void result.current.save() })
  expect(api.updateWorkout).toHaveBeenLastCalledWith(kind === 'create' ? 101 : 1,
    expect.objectContaining({ workoutName: 'Newer name', workoutDate: A }))
  await requests.write(1, true)
  expect(result.current.draft.workoutName).toBe('Newer name')
  expect(result.current.error).toBe('Write failed')
})

it('a return to the original day gets a fresh list without reviving old feedback or clearing the new draft', async () => {
  const { result } = renderHook(useWorkoutDay)
  await requests.read(0, [entry()])
  act(() => { void start(result.current, 'update') })
  act(() => { result.current.chooseDate(dateB()) })
  act(() => { result.current.chooseDate(dateA()) })
  await requests.read(2, [entry()])
  act(() => { result.current.changeDraft('workoutName', 'New visit') })
  await requests.write(0)
  await requests.read(3, [entry(A, 1, 'Submitted')])
  expect(result.current.draft.workoutName).toBe('New visit')
  expect(result.current.notice).toBe('')
  expect(result.current.workouts[0].workout.workoutName).toBe('Submitted')
  await requests.read(1, [entry(B)])
  expect(result.current.dateLabel).toBe(A)
})

it('unmount invalidates a pending read', async () => {
  const { result, unmount } = renderHook(useWorkoutDay)
  const before = result.current
  unmount()
  await requests.read(0, [entry()])
  expect(result.current).toBe(before)
})
