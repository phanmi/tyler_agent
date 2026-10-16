import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import * as api from '../../src/api'
import WorkoutPage from '../../src/components/workout/WorkoutPage'
import { A, B, dateA, dateB, deferred, entry, plan, setupApi, suggestion } from './workoutTestSupport'
import type { WorkoutPlan } from '../../src/types'

vi.mock('../../src/api')
// Calendar's date selection is the only third-party UI boundary replaced in these workflow tests.
vi.mock('react-calendar', () => ({
  default: ({ onChange }: { onChange: (date: Date) => void }) => (
    <nav aria-label="Calendar dates">
      <button onClick={() => onChange(dateA())}>Select A</button>
      <button onClick={() => onChange(dateB())}>Select B</button>
    </nav>
  ),
}))

let requests: ReturnType<typeof setupApi>
beforeEach(() => { requests = setupApi() })
afterEach(() => { cleanup(); vi.useRealTimers() })

function typeDraft(name = 'My exercise') {
  fireEvent.change(screen.getByLabelText('Exercise name'), { target: { value: name } })
  fireEvent.change(screen.getByLabelText('Sets and reps'), { target: { value: '4X12' } })
}
function submitForm() {
  const form = screen.getByLabelText('Exercise name').closest('form')!
  fireEvent.submit(form)
}

it('shows scheduling availability only after the selected day loads and keeps old records hidden', async () => {
  render(<WorkoutPage />)
  await act(async () => {}) // Resolve the independent preview request.
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Checking schedule...' }).disabled).toBe(true)
  expect(screen.queryByText('No saved exercises for this date.')).toBeNull()
  await requests.read(0, [entry(A, 1, suggestion(A).workoutName)])
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Scheduled' }).disabled).toBe(true)
  fireEvent.click(screen.getByRole('button', { name: 'Select B' }))
  expect(screen.queryByText(suggestion(A).workoutName)).toBeNull()
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Checking schedule...' }).disabled).toBe(true)
  await act(async () => { requests.reads[1].pending.reject(new Error('Load failed')) })
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Schedule unavailable' }).disabled).toBe(true)
  expect(screen.queryByText('No saved exercises for this date.')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Retry loading exercises' }))
  await requests.read(2, [])
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Add to calendar' }).disabled).toBe(false)
  expect(screen.getByText('No saved exercises for this date.')).toBeTruthy()
})

it('blocks click and form-submit re-entry while preserving typing during a save and its refresh', async () => {
  render(<WorkoutPage />)
  await requests.read(0, [])
  typeDraft('Submitted')
  act(() => { submitForm(); submitForm() })
  fireEvent.click(screen.getByRole('button', { name: 'Add to calendar' }))
  expect(api.createWorkout).toHaveBeenCalledTimes(1)
  typeDraft('Newer typing')
  await requests.write(0)
  submitForm()
  expect(api.createWorkout).toHaveBeenCalledTimes(1)
  expect(screen.getByLabelText<HTMLInputElement>('Exercise name').value).toBe('Newer typing')
  await requests.read(1, [entry(A, 101, 'Submitted')])
  submitForm()
  expect(api.updateWorkout).toHaveBeenCalledWith(101, expect.objectContaining({ workoutName: 'Newer typing', workoutDate: A }))
  await requests.write(1, true)
  expect(screen.getByLabelText<HTMLInputElement>('Exercise name').value).toBe('Newer typing')
  expect(screen.getByRole('alert').textContent).toBe('Write failed')
})

it('keeps the submitted draft correctable after a rejected save', async () => {
  render(<WorkoutPage />)
  await requests.read(0, [])
  typeDraft('Keep me')
  submitForm()
  await requests.write(0, true)
  expect(screen.getByLabelText<HTMLInputElement>('Exercise name').value).toBe('Keep me')
  expect(screen.getByLabelText<HTMLInputElement>('Sets and reps').value).toBe('4X12')
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Save exercise' }).disabled).toBe(false)
})

it('does not let an old save replace a new date, draft, or feedback', async () => {
  render(<WorkoutPage />)
  await requests.read(0, [entry()])
  fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
  submitForm()
  fireEvent.click(screen.getByRole('button', { name: 'Select B' }))
  typeDraft('B draft')
  await requests.read(1, [entry(B, 2, 'B saved')])
  await requests.write(0)
  expect(requests.reads).toHaveLength(2)
  expect(screen.getByRole('heading', { name: `Exercises for ${B}` })).toBeTruthy()
  expect(screen.getByText('B saved')).toBeTruthy()
  expect(screen.queryByText('Saved exercise')).toBeNull()
  expect(screen.queryByRole('status')).toBeNull()
  expect(screen.getByLabelText<HTMLInputElement>('Exercise name').value).toBe('B draft')
  fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
  submitForm()
  expect(api.updateWorkout).toHaveBeenLastCalledWith(2, expect.objectContaining({ workoutDate: B }))
  await requests.write(1)
  await requests.read(2, [entry(B, 2, 'B saved')])
})

it('reports saved-but-refresh-failed and requires a confirmed reload before offering another addition', async () => {
  render(<WorkoutPage />)
  await requests.read(0, [])
  fireEvent.click(screen.getByRole('button', { name: 'Add to calendar' }))
  await requests.write(0)
  await act(async () => { requests.reads[1].pending.reject(new Error('Refresh failed')) })
  expect(screen.getByRole('status').textContent).toContain('Exercise added')
  expect(screen.getByRole('alert').textContent).toContain('Changes were saved')
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Schedule unavailable' }).disabled).toBe(true)
  expect(screen.queryByText('No saved exercises for this date.')).toBeNull()
  fireEvent.click(screen.getByRole('button', { name: 'Retry loading exercises' }))
  await requests.read(2, [entry(A, 101, suggestion().workoutName)])
  expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Scheduled' }).disabled).toBe(true)
  expect(api.createWorkout).toHaveBeenCalledTimes(1)
})

it('captures the deletion target, supports cancel, and clears confirmation and draft on a date change', async () => {
  render(<WorkoutPage />)
  await requests.read(0, [entry()])
  fireEvent.click(screen.getByRole('button', { name: 'Edit' }))
  fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
  expect(screen.getByRole('dialog').textContent).toContain(`from ${A}`)
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }))
  expect(api.deleteWorkout).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
  fireEvent.click(screen.getByRole('button', { name: 'Select B' }))
  expect(screen.queryByRole('dialog')).toBeNull()
  expect(screen.getByLabelText<HTMLInputElement>('Exercise name').value).toBe('')
  expect(api.deleteWorkout).not.toHaveBeenCalled()
  await requests.read(1, [entry(B, 2)])
  fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
  fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Delete' }))
  expect(api.deleteWorkout).toHaveBeenCalledWith(2)
  await requests.write(0)
  await requests.read(2, [])
})

it('keys plan reads by options and ignores obsolete failures', async () => {
  const old = deferred<WorkoutPlan>()
  const current = deferred<WorkoutPlan>()
  vi.mocked(api.loadWorkoutPlan).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
  render(<WorkoutPage />)
  await requests.read(0, [])
  fireEvent.change(screen.getByLabelText('Goal'), { target: { value: 'strength' } })
  await act(async () => { current.resolve({ ...plan, days: plan.days.map((day) => ({ ...day, focus: 'New focus' })) }) })
  await act(async () => { old.reject(new Error('Old plan failed')) })
  expect(screen.getByRole('heading', { name: 'Suggested plan · New focus' })).toBeTruthy()
  expect(screen.queryByText('Old plan failed')).toBeNull()
})
